package com.example.lcb.parking.feature.game

import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.AttemptChainId
import com.example.lcb.parking.domain.model.AttemptId
import com.example.lcb.parking.domain.model.AttemptPresentationState
import com.example.lcb.parking.domain.model.BoardDefinition
import com.example.lcb.parking.domain.model.CanonicalAction
import com.example.lcb.parking.domain.model.Cell
import com.example.lcb.parking.domain.model.ColorOrderDefinition
import com.example.lcb.parking.domain.model.DifficultyTier
import com.example.lcb.parking.domain.model.Direction
import com.example.lcb.parking.domain.model.EffectId
import com.example.lcb.parking.domain.model.ExitDefinition
import com.example.lcb.parking.domain.model.ExitId
import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.InitialSafety
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.model.LevelMode
import com.example.lcb.parking.domain.model.LevelObjective
import com.example.lcb.parking.domain.model.OrderId
import com.example.lcb.parking.domain.model.ParkingRules
import com.example.lcb.parking.domain.model.PlayerProgress
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.model.VehicleDefinition
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleType
import com.example.lcb.parking.domain.ports.GameStateCommit
import com.example.lcb.parking.domain.ports.GameStateStore
import com.example.lcb.parking.domain.ports.LevelLoadResult
import com.example.lcb.parking.domain.ports.LevelSource
import com.example.lcb.parking.domain.ports.StoredGameResult
import com.example.lcb.parking.domain.ports.StoredProgressResult
import com.example.lcb.parking.domain.ports.StoreWriteResult
import com.example.lcb.parking.domain.ports.StoredStateBundleResult
import com.example.lcb.parking.domain.rules.GameCommand
import com.example.lcb.parking.domain.rules.GameReducer
import com.example.lcb.parking.domain.rules.RuleDecision
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainGameSessionStoreTest {

    @Test
    fun `persisting a newly created next level expects revision zero`() = runTest {
        val levelOne = level(1, "car_a")
        val levelTwo = level(2, "car_b")
        val completedSnapshot = completedSnapshot(
            level = levelOne,
            presentationState = AttemptPresentationState.PRESENTED,
            revision = 7L,
        )
        val previous = DomainGameSessionAggregate(
            projection = DomainGameProjection(levelOne, completedSnapshot),
            progress = PlayerProgress(),
            levels = listOf(levelOne, levelTwo),
            snapshotsByLevel = mapOf(levelOne.id to completedSnapshot),
        )
        val nextDecision = DomainGameReducerAdapter(FixedIdFactory()).reduce(
            previous,
            MainGameCommand.NextLevel,
        )
        val stateStore = RecordingStateStore()
        val store = createStore(listOf(levelOne, levelTwo), stateStore)

        store.persist(previous, nextDecision.aggregate)

        val commit = stateStore.commits.single()
        assertEquals(0L, commit.expectedSnapshotRevision)
        assertEquals(levelTwo.id, commit.snapshot.levelId)
        assertTrue(nextDecision.requiresPersistence)
        assertTrue(commit.facts.isNotEmpty())
    }

    @Test
    fun `load selects first incomplete level and creates only its missing attempt`() = runTest {
        val levelOne = level(1, "car_a")
        val levelTwo = level(2, "car_b")
        val completedSnapshot = completedSnapshot(
            level = levelOne,
            presentationState = AttemptPresentationState.PRESENTED,
            revision = 3L,
        )
        val stateStore = RecordingStateStore(
            storedGames = mutableMapOf(levelOne.id to completedSnapshot),
            storedProgress = PlayerProgress(completedLevelIds = setOf(levelOne.id), revision = 2L),
        )
        val store = createStore(listOf(levelOne, levelTwo), stateStore)

        val loaded = store.load()

        assertEquals(1, loaded.currentLevelIndex)
        assertEquals(levelTwo.id, loaded.projection.level.id)
        assertEquals(setOf(levelOne.id, levelTwo.id), loaded.snapshotsByLevel.keys)
        assertFalse(loaded.projection.hasNextLevel)
        assertEquals(0L, stateStore.commits.single().expectedSnapshotRevision)
        assertEquals(1, stateStore.bundleLoadCount)
        assertEquals(0, stateStore.individualGameLoadCount)
        assertEquals(0, stateStore.individualProgressLoadCount)
    }

    @Test
    fun `load restores pending result before advancing to next incomplete level`() = runTest {
        val levelOne = level(1, "car_a")
        val levelTwo = level(2, "car_b")
        val pendingResult = completedSnapshot(
            level = levelOne,
            presentationState = AttemptPresentationState.COMPLETION_PENDING,
            revision = 3L,
        )
        val stateStore = RecordingStateStore(
            storedGames = mutableMapOf(levelOne.id to pendingResult),
            storedProgress = PlayerProgress(
                coins = 35L,
                rewardedCoinsByLevel = mapOf(levelOne.id to 35),
                completedLevelIds = setOf(levelOne.id),
                revision = 2L,
            ),
        )
        val store = createStore(listOf(levelOne, levelTwo), stateStore)

        val loaded = store.load()

        assertEquals(0, loaded.currentLevelIndex)
        assertEquals(levelOne.id, loaded.projection.level.id)
        assertEquals(35, loaded.projection.earnedCoins)
        assertEquals(35L, loaded.projection.coinBalance)
        assertEquals(GameScreenPhase.RESULT, DomainGameUiProjector().map(loaded.projection).phase)
        assertTrue(stateStore.commits.isEmpty())
    }

    @Test
    fun `load rejects snapshot whose parking state conflicts with authored level`() = runTest {
        val level = level(1, "car_a")
        val invalidSnapshot = initialSnapshot(level).let { snapshot ->
            snapshot.copy(parkingLot = snapshot.parkingLot.copy(slots = emptyList()))
        }
        val store = createStore(
            levels = listOf(level),
            stateStore = RecordingStateStore(
                storedGames = mutableMapOf(level.id to invalidSnapshot),
            ),
        )

        val failure = runCatching { store.load() }.exceptionOrNull()

        assertTrue(failure is DomainSessionLoadException)
        assertTrue(failure?.message.orEmpty().contains("PARKING_CAPACITY"))
    }

    private fun createStore(
        levels: List<LevelDefinition>,
        stateStore: RecordingStateStore,
    ): DomainGameSessionStore {
        val levelsById = levels.associateBy(LevelDefinition::id)
        val source = object : LevelSource {
            override suspend fun load(levelId: LevelId): LevelLoadResult {
                val level = levelsById[levelId]
                return if (level == null) LevelLoadResult.NotFound else LevelLoadResult.Loaded(level)
            }
        }
        return DomainGameSessionStore(
            levelSource = source,
            gameStateStore = stateStore,
            levelIds = levels.map(LevelDefinition::id),
            idFactory = FixedIdFactory(),
        )
    }

    private fun initialSnapshot(level: LevelDefinition): GameSnapshot {
        return GameSnapshot.initial(level, AttemptId("attempt_${level.displayNumber}"), AttemptChainId("chain"))
    }

    /** Produces a rule-valid completed save instead of hand-editing terminal flags in fixtures. */
    private fun completedSnapshot(
        level: LevelDefinition,
        presentationState: AttemptPresentationState,
        revision: Long,
    ): GameSnapshot {
        val vehicleId = level.vehicles.single().id
        val decision = GameReducer.reduce(
            level = level,
            snapshot = initialSnapshot(level),
            command = GameCommand.TapVehicle(vehicleId, EffectId("complete-${level.id.value}")),
        )
        check(decision is RuleDecision.Applied) { "Test level must be solvable: $decision" }
        return decision.snapshot.copy(
            attempt = decision.snapshot.attempt.copy(presentationState = presentationState),
            revision = revision,
        )
    }

    private fun level(number: Int, vehicleId: String): LevelDefinition {
        val vehicle = VehicleDefinition(
            id = VehicleId(vehicleId),
            type = VehicleType.CAR,
            color = VehicleColor.BLUE,
            anchor = Cell(2, 2),
            direction = Direction.NORTH,
            length = 2,
        )
        return LevelDefinition(
            id = LevelId("main_${number.toString().padStart(3, '0')}"),
            levelVersion = 1,
            ruleVersion = 1,
            chapterId = "chapter_1",
            displayNumber = number,
            mode = LevelMode.TUTORIAL,
            difficultyTier = DifficultyTier.D1,
            board = BoardDefinition(width = 5, height = 6),
            vehicles = listOf(vehicle),
            exits = listOf(
                ExitDefinition(
                    id = ExitId("exit_top"),
                    boundaryCell = Cell(2, 0),
                    direction = Direction.NORTH,
                ),
            ),
            parkingRules = ParkingRules(
                capacity = 3,
                orders = listOf(
                    ColorOrderDefinition(OrderId("blue"), VehicleColor.BLUE, 1),
                ),
            ),
            objective = LevelObjective.ClearAll(setOf(vehicle.id)),
            initialSafety = InitialSafety.TutorialUnlimited,
            canonicalSolution = listOf(CanonicalAction.ExitVehicle(vehicle.id)),
        )
    }

    private class FixedIdFactory : DomainSessionIdFactory {
        override fun newEffectId(): EffectId = EffectId("effect")
        override fun newAttemptId(): AttemptId = AttemptId("new_attempt")
        override fun newAttemptChainId(): AttemptChainId = AttemptChainId("new_chain")
    }

    private class RecordingStateStore(
        private val storedGames: MutableMap<LevelId, GameSnapshot> = mutableMapOf(),
        private val storedProgress: PlayerProgress? = PlayerProgress(),
    ) : GameStateStore {
        val commits = mutableListOf<GameStateCommit>()
        var bundleLoadCount: Int = 0
        var individualGameLoadCount: Int = 0
        var individualProgressLoadCount: Int = 0

        override suspend fun loadGame(levelId: LevelId): StoredGameResult {
            individualGameLoadCount += 1
            return storedGames[levelId]?.let(StoredGameResult::Loaded) ?: StoredGameResult.Missing
        }

        override suspend fun loadPlayerProgress(): StoredProgressResult {
            individualProgressLoadCount += 1
            return storedProgress?.let(StoredProgressResult::Loaded) ?: StoredProgressResult.Missing
        }

        override suspend fun loadStateBundle(
            levelIds: Collection<LevelId>,
        ): StoredStateBundleResult {
            bundleLoadCount += 1
            val requested = levelIds.toSet()
            val snapshots = storedGames.filterKeys(requested::contains)
            return StoredStateBundleResult.Loaded(
                snapshotsByLevel = snapshots,
                missingLevelIds = requested - snapshots.keys,
                progress = storedProgress,
            )
        }

        override suspend fun commit(commit: GameStateCommit): StoreWriteResult {
            commits += commit
            return StoreWriteResult.Saved(commit.snapshot.revision)
        }
    }
}
