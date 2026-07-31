package com.example.lcb.parking.feature.game

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** 来自 Android UI 的意图；只有 vehicleId 会跨过棋盘展示边界。 */
sealed interface MainGameCommand {
    data class TapVehicle(val vehicleId: String) : MainGameCommand
    data object Pause : MainGameCommand
    data object Resume : MainGameCommand
    data object NextLevel : MainGameCommand
    /** 从首页或地图进入指定已解锁关卡；规则适配器负责校验解锁并创建/恢复 Attempt。 */
    data class OpenLevel(val levelNumber: Int) : MainGameCommand
    data object QuitToHome : MainGameCommand
    data object RestartCurrentLevel : MainGameCommand
    data object Retry : MainGameCommand
    data object HostStopped : MainGameCommand
    data class PresentationCompleted(
        val effectId: String,
        val vehicleId: String? = null,
    ) : MainGameCommand
    data object TerminalPresented : MainGameCommand
}

/**
 * game-domain 的 reducer 适配端口。实现负责把 [MainGameCommand] 映射为领域 GameCommand，
 * 但不得持有 Android 类型或执行持久化。
 */
fun interface GameSessionReducer<Aggregate : Any, DomainPresentationIntent : Any> {
    fun reduce(
        aggregate: Aggregate,
        command: MainGameCommand,
    ): GameSessionDecision<Aggregate, DomainPresentationIntent>
}

data class GameSessionDecision<Aggregate : Any, DomainPresentationIntent : Any>(
    val aggregate: Aggregate,
    val presentationIntents: List<DomainPresentationIntent> = emptyList(),
    /** 无状态变化且无恢复价值的命令可跳过磁盘写入。 */
    val requiresPersistence: Boolean = true,
)

interface GameSessionStore<Aggregate : Any> {
    suspend fun load(): Aggregate
    suspend fun persist(previous: Aggregate, next: Aggregate)
}

fun interface MainGameUiMapper<Aggregate : Any> {
    fun map(aggregate: Aggregate): MainGameUiState
}

fun interface PresentationEffectMapper<Aggregate : Any, DomainPresentationIntent : Any> {
    fun map(aggregate: Aggregate, intent: DomainPresentationIntent): GamePresentationEffect?
}

/** 非泛型控制面让 Android ViewModel 与具体领域 Aggregate 保持解耦。 */
interface GameSessionController : Closeable {
    val uiState: StateFlow<MainGameUiState>
    val presentationEffects: Flow<GamePresentationEffect>

    fun start(scope: CoroutineScope)
    fun submit(command: MainGameCommand): Boolean

    /** 动画/终态确认与生命周期暂停不能和可丢弃的高频点击争抢同一队列容量。 */
    fun submitCritical(command: MainGameCommand): Boolean
}

/**
 * 有界、单消费者的会话协调器。
 *
 * 严格顺序为：领域 reducer -> 必要时 IO 持久化 -> 发布表现 Intent -> 发布 UI State。
 * 因此磁盘失败不会把未提交状态或奖励暴露给页面。所有 dispatcher 均可注入以便测试，
 * 类内不创建线程、不 runBlocking，也不持有 Activity/View。
 */
class GameSessionCoordinator<Aggregate : Any, DomainPresentationIntent : Any>(
    private val store: GameSessionStore<Aggregate>,
    private val reducer: GameSessionReducer<Aggregate, DomainPresentationIntent>,
    private val uiMapper: MainGameUiMapper<Aggregate>,
    private val presentationEffectMapper: PresentationEffectMapper<Aggregate, DomainPresentationIntent>,
    private val domainDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    commandCapacity: Int = DEFAULT_COMMAND_CAPACITY,
    effectCapacity: Int = DEFAULT_EFFECT_CAPACITY,
    criticalCommandCapacity: Int = DEFAULT_CRITICAL_COMMAND_CAPACITY,
) : GameSessionController {

    init {
        require(commandCapacity > 0) { "commandCapacity must be positive" }
        require(effectCapacity > 0) { "effectCapacity must be positive" }
        require(criticalCommandCapacity > effectCapacity) {
            "criticalCommandCapacity must exceed effectCapacity"
        }
    }

    private val mutableUiState = MutableStateFlow(MainGameUiState())
    override val uiState: StateFlow<MainGameUiState> = mutableUiState.asStateFlow()

    private val commandChannel = Channel<MainGameCommand>(capacity = commandCapacity)
    private val criticalCommandChannel = Channel<MainGameCommand>(capacity = criticalCommandCapacity)
    /*
     * 表现事件会解锁 transient lock / completion gate，不能采用 DROP_* 策略。
     * 消费端暂时停止时让后台会话在此处施加有界背压；恢复收集后会继续处理，不会占用主线程。
     */
    private val effectChannel = Channel<GamePresentationEffect>(capacity = effectCapacity)
    override val presentationEffects: Flow<GamePresentationEffect> = effectChannel.receiveAsFlow()

    private val started = AtomicBoolean(false)
    private var consumerJob: Job? = null
    private var aggregate: Aggregate? = null

    override fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        consumerJob = scope.launch(domainDispatcher) {
            loadFromStore()
            while (true) {
                val command = receiveNextCommand() ?: break
                if (command == MainGameCommand.Retry && mutableUiState.value.phase == GameScreenPhase.ERROR) {
                    loadFromStore()
                } else {
                    process(command)
                }
            }
        }
    }

    /** 非阻塞入队；队列满时返回 false，由调用方保留当前稳定 UI。 */
    override fun submit(command: MainGameCommand): Boolean {
        return started.get() && commandChannel.trySend(command).isSuccess
    }

    override fun submitCritical(command: MainGameCommand): Boolean {
        require(
            command is MainGameCommand.PresentationCompleted ||
                command == MainGameCommand.TerminalPresented ||
                command == MainGameCommand.HostStopped,
        ) { "Only presentation acknowledgements and lifecycle pause are critical commands" }
        return started.get() && criticalCommandChannel.trySend(command).isSuccess
    }

    /** 优先清空规则解锁回调，避免用户连续点击让动画确认饥饿。 */
    private suspend fun receiveNextCommand(): MainGameCommand? {
        criticalCommandChannel.tryReceive().getOrNull()?.let { return it }
        return select {
            criticalCommandChannel.onReceiveCatching { result -> result.getOrNull() }
            commandChannel.onReceiveCatching { result -> result.getOrNull() }
        }
    }

    private suspend fun loadFromStore() {
        mutableUiState.value = mutableUiState.value.copy(
            phase = GameScreenPhase.LOADING,
            errorMessage = null,
        )
        try {
            val loadedAggregate = withContext(ioDispatcher) { store.load() }
            aggregate = loadedAggregate
            mutableUiState.value = uiMapper.map(loadedAggregate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            aggregate = null
            publishError(throwable)
        }
    }

    private suspend fun process(command: MainGameCommand) {
        val currentAggregate = aggregate ?: return
        try {
            val decision = reducer.reduce(currentAggregate, command)
            if (decision.requiresPersistence) {
                withContext(ioDispatcher) { store.persist(currentAggregate, decision.aggregate) }
            }
            aggregate = decision.aggregate
            publishEffects(decision.aggregate, decision.presentationIntents)
            mutableUiState.value = uiMapper.map(decision.aggregate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            publishError(throwable)
        }
    }

    private suspend fun publishEffects(
        aggregate: Aggregate,
        intents: List<DomainPresentationIntent>,
    ) {
        var index = 0
        while (index < intents.size) {
            val effect = presentationEffectMapper.map(aggregate, intents[index])
            if (effect != null) effectChannel.send(effect)
            index++
        }
    }

    private fun publishError(throwable: Throwable) {
        mutableUiState.value = mutableUiState.value.copy(
            phase = GameScreenPhase.ERROR,
            errorMessage = throwable.message?.take(MAX_ERROR_LENGTH),
        )
    }

    override fun close() {
        commandChannel.close()
        criticalCommandChannel.close()
        effectChannel.close()
        consumerJob?.cancel()
        consumerJob = null
    }

    private companion object {
        const val DEFAULT_COMMAND_CAPACITY = 32
        const val DEFAULT_EFFECT_CAPACITY = 32
        const val DEFAULT_CRITICAL_COMMAND_CAPACITY = 64
        const val MAX_ERROR_LENGTH = 160
    }
}
