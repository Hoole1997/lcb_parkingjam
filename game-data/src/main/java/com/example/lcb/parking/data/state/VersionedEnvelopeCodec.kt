package com.example.lcb.parking.data.state

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException

/**
 * 只处理版本 envelope，不了解业务状态。业务 DTO 映射与版本迁移由调用方显式提供。
 */
internal class VersionedEnvelopeCodec<T>(
    private val gson: Gson,
    private val currentSchemaVersion: Int,
    private val encodePayload: (T) -> JsonElement,
    private val decodePayload: (JsonElement) -> T,
    private val migrations: Map<Int, (JsonElement) -> JsonElement> = emptyMap(),
) {

    init {
        require(currentSchemaVersion > 0) { "currentSchemaVersion must be positive" }
    }

    fun encode(value: T): String {
        val envelope = JsonObject().apply {
            addProperty(SCHEMA_VERSION, currentSchemaVersion)
            add(PAYLOAD, encodePayload(value))
        }
        return gson.toJson(envelope)
    }

    fun decode(raw: String): EnvelopeDecodeResult<T> {
        return try {
            val envelope = gson.fromJson(raw, JsonObject::class.java)
                ?: return EnvelopeDecodeResult.Corrupt("Envelope is null")
            val schemaVersion = envelope.get(SCHEMA_VERSION)?.takeIf { it.isJsonPrimitive }?.asInt
                ?: return EnvelopeDecodeResult.Corrupt("Missing schemaVersion")
            val payload = envelope.get(PAYLOAD)
                ?: return EnvelopeDecodeResult.Corrupt("Missing payload")

            when {
                schemaVersion > currentSchemaVersion -> {
                    EnvelopeDecodeResult.FutureVersion(
                        foundVersion = schemaVersion,
                        supportedVersion = currentSchemaVersion,
                    )
                }

                schemaVersion <= 0 -> {
                    EnvelopeDecodeResult.Corrupt("Invalid schemaVersion=$schemaVersion")
                }

                else -> migrateAndDecode(schemaVersion, payload)
            }
        } catch (error: JsonParseException) {
            EnvelopeDecodeResult.Corrupt(error.message ?: "Malformed JSON")
        } catch (error: IllegalStateException) {
            EnvelopeDecodeResult.Corrupt(error.message ?: "Invalid envelope value")
        } catch (error: NumberFormatException) {
            EnvelopeDecodeResult.Corrupt(error.message ?: "Invalid schemaVersion")
        } catch (error: IllegalArgumentException) {
            // Migration functions use require(...) for malformed historical payloads.
            EnvelopeDecodeResult.Corrupt(error.message ?: "Invalid envelope")
        } catch (_: NullPointerException) {
            EnvelopeDecodeResult.Corrupt("Envelope is missing a required field")
        }
    }

    private fun migrateAndDecode(
        initialVersion: Int,
        initialPayload: JsonElement,
    ): EnvelopeDecodeResult<T> {
        var version = initialVersion
        var payload = initialPayload
        while (version < currentSchemaVersion) {
            val migration = migrations[version]
                ?: return EnvelopeDecodeResult.MigrationMissing(
                    fromVersion = version,
                    toVersion = version + 1,
                )
            payload = migration(payload)
            version += 1
        }

        return try {
            EnvelopeDecodeResult.Success(
                value = decodePayload(payload),
                originalVersion = initialVersion,
                schemaVersion = currentSchemaVersion,
            )
        } catch (error: JsonParseException) {
            EnvelopeDecodeResult.Corrupt(error.message ?: "Malformed payload")
        } catch (error: IllegalArgumentException) {
            EnvelopeDecodeResult.Corrupt(error.message ?: "Invalid payload")
        } catch (error: IllegalStateException) {
            EnvelopeDecodeResult.Corrupt(error.message ?: "Invalid payload state")
        } catch (_: NullPointerException) {
            EnvelopeDecodeResult.Corrupt("Payload is missing a required field")
        }
    }

    private companion object {
        const val SCHEMA_VERSION = "schemaVersion"
        const val PAYLOAD = "payload"
    }
}

internal sealed interface EnvelopeDecodeResult<out T> {
    data class Success<T>(
        val value: T,
        val originalVersion: Int,
        val schemaVersion: Int,
    ) : EnvelopeDecodeResult<T>

    data class FutureVersion(
        val foundVersion: Int,
        val supportedVersion: Int,
    ) : EnvelopeDecodeResult<Nothing>

    data class MigrationMissing(
        val fromVersion: Int,
        val toVersion: Int,
    ) : EnvelopeDecodeResult<Nothing>

    data class Corrupt(
        val reason: String,
    ) : EnvelopeDecodeResult<Nothing>
}
