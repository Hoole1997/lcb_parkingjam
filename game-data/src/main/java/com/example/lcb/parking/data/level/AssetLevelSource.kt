package com.example.lcb.parking.data.level

import android.content.res.AssetManager
import com.example.lcb.parking.domain.model.LevelDefinition
import com.example.lcb.parking.domain.model.LevelId
import com.example.lcb.parking.domain.ports.LevelLoadResult
import com.example.lcb.parking.domain.ports.LevelSource
import com.example.lcb.parking.domain.validation.LevelValidationReport
import com.example.lcb.parking.domain.validation.LevelValidator
import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 按 levelId 从 APK assets 单文件读取，避免一次性解析并常驻整个关卡包。
 */
class AssetLevelSource(
    assetManager: AssetManager,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    gson: Gson = Gson(),
    supportedRuleVersion: Int = 2,
) : LevelSource {

    private val delegate = AssetLevelLoader(
        reader = AndroidLevelAssetReader(assetManager),
        ioDispatcher = ioDispatcher,
        gson = gson,
        supportedRuleVersion = supportedRuleVersion,
        validate = LevelValidator::validateStructure,
    )

    override suspend fun load(levelId: LevelId): LevelLoadResult = delegate.load(levelId)
}

/** Internal seam keeps JSON mapping and failure handling testable without Android resources. */
internal class AssetLevelLoader(
    private val reader: LevelAssetReader,
    private val ioDispatcher: CoroutineDispatcher,
    private val gson: Gson,
    private val supportedRuleVersion: Int,
    private val validate: (LevelDefinition) -> LevelValidationReport,
) : LevelSource {

    init {
        require(supportedRuleVersion > 0) { "supportedRuleVersion must be positive" }
    }

    override suspend fun load(levelId: LevelId): LevelLoadResult = withContext(ioDispatcher) {
        val assetName = levelId.toAssetName()
            ?: return@withContext LevelLoadResult.Corrupt("Invalid built-in level ID")
        val json = try {
            reader.read("levels/$assetName")
        } catch (_: FileNotFoundException) {
            return@withContext LevelLoadResult.NotFound
        } catch (error: IOException) {
            return@withContext LevelLoadResult.Unavailable(
                error.message?.take(MAX_ERROR_LENGTH) ?: "Unable to read level asset",
            )
        }

        val dto = try {
            gson.fromJson(json, LevelAssetDto::class.java)
                ?: return@withContext LevelLoadResult.Corrupt("Level JSON is empty")
        } catch (error: JsonParseException) {
            return@withContext LevelLoadResult.Corrupt(
                error.message?.take(MAX_ERROR_LENGTH) ?: "Malformed level JSON",
            )
        } catch (error: IllegalStateException) {
            return@withContext LevelLoadResult.Corrupt(
                error.message?.take(MAX_ERROR_LENGTH) ?: "Invalid level JSON value",
            )
        }

        if (dto.levelId != levelId.value) {
            return@withContext LevelLoadResult.Corrupt("Requested level ID does not match asset content")
        }
        if (dto.ruleVersion != supportedRuleVersion) {
            return@withContext LevelLoadResult.UnsupportedRuleVersion(dto.ruleVersion)
        }

        when (val mapping = LevelAssetMapper.map(dto)) {
            is LevelMappingResult.Invalid -> LevelLoadResult.Corrupt(mapping.reason.take(MAX_ERROR_LENGTH))
            is LevelMappingResult.UnsupportedAssetSchema -> LevelLoadResult.Corrupt(
                "Unsupported level asset schema ${mapping.schemaVersion}",
            )
            is LevelMappingResult.Mapped -> {
                val report = validate(mapping.level)
                if (report.isValid) {
                    LevelLoadResult.Loaded(mapping.level)
                } else {
                    LevelLoadResult.Corrupt(report.toErrorReason())
                }
            }
        }
    }

    private fun LevelId.toAssetName(): String? {
        return value.takeIf(LEVEL_ID_PATTERN::matches)?.plus(".json")
    }

    private fun LevelValidationReport.toErrorReason(): String {
        return issues.asSequence()
            .take(MAX_REPORTED_ISSUES)
            .joinToString(separator = "; ") { issue -> "${issue.code}:${issue.message}" }
            .take(MAX_ERROR_LENGTH)
    }

    private companion object {
        val LEVEL_ID_PATTERN = Regex("main_[0-9]{3}")
        const val MAX_REPORTED_ISSUES = 8
        const val MAX_ERROR_LENGTH = 512
    }
}

internal fun interface LevelAssetReader {
    @Throws(IOException::class)
    fun read(path: String): String
}

private class AndroidLevelAssetReader(
    private val assetManager: AssetManager,
) : LevelAssetReader {

    override fun read(path: String): String {
        assetManager.open(path, AssetManager.ACCESS_STREAMING).use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                val result = StringBuilder(INITIAL_CAPACITY)
                val buffer = CharArray(BUFFER_SIZE)
                var totalChars = 0
                while (true) {
                    val readCount = reader.read(buffer)
                    if (readCount < 0) break
                    totalChars += readCount
                    if (totalChars > MAX_LEVEL_CHARS) {
                        throw IOException("Level asset exceeds $MAX_LEVEL_CHARS characters")
                    }
                    result.append(buffer, 0, readCount)
                }
                return result.toString()
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val INITIAL_CAPACITY = 16 * 1024
        const val MAX_LEVEL_CHARS = 256 * 1024
    }
}
