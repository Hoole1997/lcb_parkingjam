package com.example.lcb.parking.data.level

import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.ports.LevelLoadResult
import com.example.lcb.parking.domain.validation.LevelValidator
import com.google.gson.Gson
import java.io.File
import java.io.FileNotFoundException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetLevelLoaderTest {

    @Test
    fun `load reads requested file maps and validates it`() {
        val requestedPaths = mutableListOf<String>()
        val loader = loader { path ->
            requestedPaths += path
            File("src/main/assets/$path").readText()
        }

        val result = runImmediate { loader.load(LevelId("main_003")) }

        assertEquals(listOf("levels/main_003.json"), requestedPaths)
        assertTrue(result is LevelLoadResult.Loaded)
        assertEquals("main_003", (result as LevelLoadResult.Loaded).level.id.value)
    }

    @Test
    fun `path traversal shaped level id is rejected before reading`() {
        var readerCalled = false
        val loader = loader {
            readerCalled = true
            error("Must not read")
        }

        val result = runImmediate { loader.load(LevelId("../main_001")) }

        assertTrue(result is LevelLoadResult.Corrupt)
        assertEquals(false, readerCalled)
    }

    @Test
    fun `missing asset is reported as not found`() {
        val loader = loader { throw FileNotFoundException("missing") }

        val result = runImmediate { loader.load(LevelId("main_999")) }

        assertEquals(LevelLoadResult.NotFound, result)
    }

    @Test
    fun `unsupported rule version is explicit`() {
        val original = File("src/main/assets/levels/main_001.json").readText()
        val loader = loader { original.replace("\"rule_version\": 2", "\"rule_version\": 3") }

        val result = runImmediate { loader.load(LevelId("main_001")) }

        assertEquals(LevelLoadResult.UnsupportedRuleVersion(3), result)
    }

    private fun loader(reader: LevelAssetReader): AssetLevelLoader = AssetLevelLoader(
        reader = reader,
        ioDispatcher = Dispatchers.Unconfined,
        gson = Gson(),
        supportedRuleVersion = 2,
        validate = LevelValidator::validateStructure,
    )

    /** Executes only test fakes that are guaranteed to complete immediately. */
    private fun <T> runImmediate(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return checkNotNull(outcome) { "Test coroutine unexpectedly suspended" }.getOrThrow()
    }
}
