package com.example.lcb.parking.data.state

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionedEnvelopeCodecTest {

    private val gson = Gson()

    @Test
    fun `encode and decode current envelope`() {
        val codec = stringCodec(currentVersion = 1)

        val raw = codec.encode("active")
        val result = codec.decode(raw)

        assertTrue(result is EnvelopeDecodeResult.Success)
        result as EnvelopeDecodeResult.Success
        assertEquals("active", result.value)
        assertEquals(1, result.originalVersion)
        assertEquals(1, result.schemaVersion)
    }

    @Test
    fun `future schema is reported without decoding payload`() {
        val codec = stringCodec(currentVersion = 1)

        val result = codec.decode("""{"schemaVersion":2,"payload":"future"}""")

        assertEquals(
            EnvelopeDecodeResult.FutureVersion(foundVersion = 2, supportedVersion = 1),
            result,
        )
    }

    @Test
    fun `payload is migrated one version at a time`() {
        val codec = VersionedEnvelopeCodec(
            gson = gson,
            currentSchemaVersion = 2,
            encodePayload = { value: String -> gson.toJsonTree(value) },
            decodePayload = { payload -> payload.asString },
            migrations = mapOf(
                1 to { payload -> gson.toJsonTree("${payload.asString}-migrated") },
            ),
        )

        val result = codec.decode("""{"schemaVersion":1,"payload":"legacy"}""")

        assertTrue(result is EnvelopeDecodeResult.Success)
        result as EnvelopeDecodeResult.Success
        assertEquals("legacy-migrated", result.value)
        assertEquals(1, result.originalVersion)
        assertEquals(2, result.schemaVersion)
    }

    @Test
    fun `missing migration is an explicit result`() {
        val codec = stringCodec(currentVersion = 2)

        val result = codec.decode("""{"schemaVersion":1,"payload":"legacy"}""")

        assertEquals(
            EnvelopeDecodeResult.MigrationMissing(fromVersion = 1, toVersion = 2),
            result,
        )
    }

    @Test
    fun `malformed envelope is an explicit corrupt result`() {
        val result = stringCodec(currentVersion = 1).decode("not-json")

        assertTrue(result is EnvelopeDecodeResult.Corrupt)
    }

    private fun stringCodec(currentVersion: Int): VersionedEnvelopeCodec<String> {
        return VersionedEnvelopeCodec(
            gson = gson,
            currentSchemaVersion = currentVersion,
            encodePayload = { value -> gson.toJsonTree(value) },
            decodePayload = { payload -> payload.asString },
        )
    }
}
