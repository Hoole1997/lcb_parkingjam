package com.example.lcb.parking.feature.game

import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.ports.GameStateStore
import com.example.lcb.parking.domain.ports.LevelSource
import kotlinx.coroutines.CoroutineDispatcher

/** App 组合根使用的便捷工厂；dispatcher 必须由宿主显式注入。 */
object DomainGameSessionFactory {
    fun create(
        levelSource: LevelSource,
        gameStateStore: GameStateStore,
        levelIds: List<LevelId>,
        domainDispatcher: CoroutineDispatcher,
        ioDispatcher: CoroutineDispatcher,
        idFactory: DomainSessionIdFactory = UuidDomainSessionIdFactory(),
        tutorialMessageProvider: TutorialMessageProvider = TutorialMessageProvider { null },
    ): GameSessionController {
        val store = DomainGameSessionStore(
            levelSource = levelSource,
            gameStateStore = gameStateStore,
            levelIds = levelIds,
            idFactory = idFactory,
            tutorialMessageProvider = tutorialMessageProvider,
        )
        val uiProjector = DomainGameUiProjector()
        return GameSessionCoordinator(
            store = store,
            reducer = DomainGameReducerAdapter(idFactory),
            uiMapper = MainGameUiMapper { aggregate ->
                uiProjector.map(aggregate.projection).copy(
                    progress = GameProgressUiMapper.map(aggregate),
                )
            },
            presentationEffectMapper = DomainPresentationEffectMapper(),
            domainDispatcher = domainDispatcher,
            ioDispatcher = ioDispatcher,
        )
    }
}
