package com.example.lcb.parking.data.level

import com.example.lcb.parking.domain.model.CanonicalAction
import com.example.lcb.parking.domain.model.AttemptBusinessState
import com.example.lcb.parking.domain.model.AttemptChainId
import com.example.lcb.parking.domain.model.AttemptId
import com.example.lcb.parking.domain.model.EffectId
import com.example.lcb.parking.domain.model.GameSnapshot
import com.example.lcb.parking.domain.model.VehicleId
import com.example.lcb.parking.domain.model.VehicleColor
import com.example.lcb.parking.domain.rules.DomainFact
import com.example.lcb.parking.domain.rules.GameCommand
import com.example.lcb.parking.domain.rules.GameReducer
import com.example.lcb.parking.domain.rules.RuleDecision
import com.example.lcb.parking.domain.validation.LevelSolutionValidator
import com.example.lcb.parking.domain.validation.LevelValidator
import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelAssetMapperTest {

    private val gson = Gson()

    @Test
    fun `built-in pack contains exactly 30 contiguous publication-valid levels`() {
        val expectedNames = (1..30).map { number ->
            "main_${number.toString().padStart(3, '0')}.json"
        }
        val assetFiles = levelAssetFiles()

        assertEquals(expectedNames, assetFiles.map(File::getName))
        assetFiles.forEachIndexed { index, assetFile ->
            val number = index + 1
            val dto = gson.fromJson(assetFile.readText(), LevelAssetDto::class.java)
            val result = LevelAssetMapper.map(dto)

            assertTrue("${assetFile.name} should map", result is LevelMappingResult.Mapped)
            val level = (result as LevelMappingResult.Mapped).level
            assertEquals("main_${number.toString().padStart(3, '0')}", level.id.value)
            assertEquals(number, level.displayNumber)
            assertEquals(2, level.levelVersion)
            assertEquals(2, level.ruleVersion)
            assertEquals(
                "${assetFile.name} progression prerequisites",
                dto.progression.prerequisiteLevelIds.toSet(),
                level.progression.prerequisiteLevelIds.mapTo(linkedSetOf()) { it.value },
            )
            assertEquals(dto.progression.skippable, level.progression.skippable)
            assertEquals(dto.contentTags.toSet(), level.contentTags)
            val report = LevelValidator.validateStructure(level)
            assertTrue(
                "${assetFile.name} validation issues: ${report.issues}",
                report.isValid,
            )
            val solutionReport = LevelSolutionValidator().validate(level)
            assertTrue(
                "${assetFile.name} solution issues: ${solutionReport.issues}",
                solutionReport.isValid,
            )
        }
    }

    @Test
    fun `full boundary segment expands to deterministic boundary exits`() {
        val result = LevelAssetMapper.map(readDto(number = 1)) as LevelMappingResult.Mapped

        assertEquals(5, result.level.exits.size)
        assertEquals("north_full_0", result.level.exits.first().id.value)
        assertEquals("north_full_4", result.level.exits.last().id.value)
        assertEquals(0, result.level.exits.first().boundaryCell.y)
    }

    @Test
    fun `L4 canonical solution preserves both dependency chains`() {
        val result = LevelAssetMapper.map(readDto(number = 4)) as LevelMappingResult.Mapped

        val solutionVehicleIds = result.level.canonicalSolution.map { action ->
            (action as CanonicalAction.ExitVehicle).vehicleId
        }
        assertEquals(
            listOf("A", "D", "B", "E", "C").map(::VehicleId),
            solutionVehicleIds,
        )
    }

    @Test
    fun `parking progression and canonical color groups are explicit in every level`() {
        (1..30).forEach { number ->
            val dto = readDto(number)
            val level = (LevelAssetMapper.map(dto) as LevelMappingResult.Mapped).level
            val expectedCapacity = when (number) {
                in 1..2 -> 2
                in 3..5 -> 3
                in 6..15 -> 4
                else -> 5
            }

            assertEquals("main_$number parking capacity", expectedCapacity, level.parkingRules.capacity)
            assertEquals(dto.vehicles.size, level.vehicles.size)
            assertTrue("main_$number has explicit colors", dto.vehicles.all { it.colorId.isNotBlank() })

            val colorsById = level.vehicles.associate { vehicle -> vehicle.id to vehicle.color }
            val canonicalIds = level.canonicalSolution.map { action ->
                (action as CanonicalAction.ExitVehicle).vehicleId
            }
            var offset = 0
            level.parkingRules.orders.forEach { order ->
                val group = canonicalIds.subList(offset, offset + order.requiredCount)
                assertTrue(
                    "main_$number order ${order.id.value} canonical colors",
                    group.all { vehicleId -> colorsById[vehicleId] == order.color },
                )
                offset += order.requiredCount
            }
            assertEquals("main_$number orders cover canonical", canonicalIds.size, offset)
        }
    }

    @Test
    fun `rescue distractors never reuse an ordered color`() {
        listOf(22, 25, 30).forEach { number ->
            val level = (LevelAssetMapper.map(readDto(number)) as LevelMappingResult.Mapped).level
            val canonicalIds = level.canonicalSolution.mapTo(linkedSetOf()) { action ->
                (action as CanonicalAction.ExitVehicle).vehicleId
            }
            val orderedColors = level.parkingRules.orders.mapTo(linkedSetOf()) { it.color }
            val distractorColors = level.vehicles
                .filterNot { it.id in canonicalIds }
                .mapTo(linkedSetOf()) { it.color }

            assertTrue("main_$number needs distractors", distractorColors.isNotEmpty())
            assertTrue(
                "main_$number distractors overlap orders: ${distractorColors intersect orderedColors}",
                distractorColors.intersect(orderedColors).isEmpty(),
            )
            assertTrue(
                "main_$number distractors should use mint/red",
                distractorColors.all { it == VehicleColor.MINT || it == VehicleColor.RED },
            )
        }
    }

    @Test
    fun `production reducer replays all canonical solutions without parking overflow`() {
        (1..30).forEach { number ->
            val level = (LevelAssetMapper.map(readDto(number)) as LevelMappingResult.Mapped).level
            var snapshot = GameSnapshot.initial(
                level,
                AttemptId("canonical-attempt-$number"),
                AttemptChainId("canonical-chain-$number"),
            )

            level.canonicalSolution.forEachIndexed { actionIndex, action ->
                val vehicleId = (action as CanonicalAction.ExitVehicle).vehicleId
                val decision = GameReducer.reduce(
                    level,
                    snapshot,
                    GameCommand.TapVehicle(
                        vehicleId,
                        EffectId("canonical-$number-$actionIndex-${vehicleId.value}"),
                    ),
                )
                assertTrue(
                    "main_$number canonical step ${actionIndex + 1} rejected: $decision",
                    decision is RuleDecision.Applied,
                )
                snapshot = (decision as RuleDecision.Applied).snapshot
                assertTrue(
                    "main_$number canonical step should not occupy waiting slots",
                    snapshot.parkingLot.slots.all { it == null },
                )
                assertTrue(
                    "main_$number canonical step overflowed",
                    decision.facts.none { it is DomainFact.ParkingOverflowRecorded },
                )
            }

            assertEquals(
                "main_$number canonical completion",
                AttemptBusinessState.COMPLETE,
                snapshot.attempt.businessState,
            )
        }
    }

    @Test
    fun `all levels expose their declared safe first move count`() {
        val tutorialExpectedIds = mapOf(
            1 to setOf("A"),
            2 to setOf("A", "B", "C"),
            3 to setOf("A"),
            4 to setOf("A", "D"),
            5 to setOf("A", "D"),
        )

        (1..30).forEach { number ->
            val dto = readDto(number)
            val level = (LevelAssetMapper.map(dto) as LevelMappingResult.Mapped).level
            val initial = GameSnapshot.initial(
                level,
                AttemptId("attempt-$number"),
                AttemptChainId("chain-$number"),
            )
            val safeIds = level.vehicles.mapNotNull { vehicle ->
                val decision = GameReducer.reduce(
                    level,
                    initial,
                    GameCommand.TapVehicle(vehicle.id, EffectId("effect-$number-${vehicle.id.value}")),
                ) as? RuleDecision.Applied
                vehicle.id.value.takeIf {
                    decision?.facts?.any { fact ->
                        fact is DomainFact.VehicleExitCommitted && fact.vehicleId == vehicle.id
                    } == true
                }
            }.toSet()

            assertEquals(
                "main_$number safe first move count",
                dto.difficultyMetrics.safeFirstMoves,
                safeIds.size,
            )
            tutorialExpectedIds[number]?.let { expectedIds ->
                assertEquals("main_$number safe first vehicles", expectedIds, safeIds)
            }
        }
    }

    @Test
    fun `progression is contiguous except optional hard preview`() {
        (1..30).forEach { number ->
            val progression = readDto(number).progression
            val expectedPrerequisites = when (number) {
                1 -> emptyList()
                27 -> listOf("main_025")
                else -> listOf("main_${(number - 1).toString().padStart(3, '0')}")
            }

            assertEquals("main_$number prerequisites", expectedPrerequisites, progression.prerequisiteLevelIds)
            assertEquals("main_$number skippable", number == 26, progression.skippable)
        }
    }

    @Test
    fun `future asset schema is an explicit unsupported result`() {
        val result = LevelAssetMapper.map(readDto(number = 1).copy(schemaVersion = 3))

        assertEquals(LevelMappingResult.UnsupportedAssetSchema(3), result)
    }

    @Test
    fun `out of bounds exit segment is rejected`() {
        val dto = readDto(number = 1)
        val invalidExit = dto.exits.single().copy(offset = 4, length = 2)

        val result = LevelAssetMapper.map(dto.copy(exits = listOf(invalidExit)))

        assertTrue(result is LevelMappingResult.Invalid)
    }

    private fun readDto(number: Int): LevelAssetDto {
        val assetName = "main_${number.toString().padStart(3, '0')}.json"
        val json = File("src/main/assets/levels/$assetName").readText()
        return gson.fromJson(json, LevelAssetDto::class.java)
    }

    private fun levelAssetFiles(): List<File> =
        checkNotNull(File("src/main/assets/levels").listFiles()) {
            "Built-in level asset directory is missing"
        }
            .filter { file -> file.isFile && LEVEL_ASSET_NAME.matches(file.name) }
            .sortedBy(File::getName)

    private companion object {
        val LEVEL_ASSET_NAME = Regex("main_[0-9]{3}\\.json")
    }
}
