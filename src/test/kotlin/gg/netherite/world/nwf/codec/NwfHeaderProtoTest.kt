@file:Suppress("MaxLineLength") // dense assertion tables read better unwrapped

package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.HeaderField
import gg.netherite.world.nwf.HeightmapPolicy
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.NwfFormatException
import gg.netherite.world.nwf.WorldHeader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.HexFormat
import java.util.UUID

class NwfHeaderProtoTest {
    private val hex = HexFormat.of()

    private fun full() = WorldHeader(
        meta = byteArrayOf(1, 2),
        worldUuidBytes = WorldHeader.uuidBytes(UUID(1, 2)),
        worldPdc = byteArrayOf(3),
        levelState = byteArrayOf(0x52, 0x00),
        dataVersion = 4_440,
        writer = "writer/1",
        originId = 1,
        legacyEtag = "\"etag\"",
        contentDigest = ByteArray(32) { 7 },
        contentClass = "class-a",
        parentRootSha = ByteArray(32) { 8 },
        createdAtMs = 1_700_000_000_000,
        committedAtMs = 1_700_000_000_123,
        dimensions = listOf("minecraft:overworld", "minecraft:the_nether"),
        baseRootSha = ByteArray(32) { 9 },
        baseKey = "base/a",
        lightPolicy = LightPolicy.STRIP,
        heightmapPolicy = HeightmapPolicy.STRIP,
        converter = "conv/1",
        sourceUserData = byteArrayOf(5, 6, 7),
        segmentPrefix = "prefix",
        saveId = "save-1",
    )

    @Test
    fun `every field round trips`() {
        val h = full()
        assertEquals(h, NwfHeaderProto.decode(NwfHeaderProto.encode(h)))
        assertEquals(UUID(1, 2), h.worldUuid)
    }

    @Test
    fun `the golden fixture header is written byte for byte`() {
        val h = WorldHeader(
            dataVersion = 4_440,
            writer = "nwf-golden",
            contentClass = "golden",
            createdAtMs = 1_700_000_000_000,
            dimensions = listOf("minecraft:overworld"),
            lightPolicy = LightPolicy.STRIP,
        )
        val expected = "0a0012001a0028d822320a6e77662d676f6c64656e4a00a20106676f6c64656eaa0100b00180d095ffbc31" +
            "c201136d696e6563726166743a6f766572776f726c64ca0100d80101f20100"
        assertEquals(expected, hex.formatHex(NwfHeaderProto.encode(h)))
    }

    @Test
    fun `bytes fields are always written, other defaults never`() {
        // fields 1, 2, 3, 9, 21, 25 and 30 with length 0, nothing else
        assertEquals("0a0012001a004a00aa0100ca0100f20100", hex.formatHex(NwfHeaderProto.encode(WorldHeader())))
        val absent = NwfHeaderProto.decode(ByteArray(0))
        assertEquals(WorldHeader(), absent)
        assertNull(absent.levelState)
        assertNull(absent.legacyEtag)
    }

    @Test
    fun `present but empty optional fields stay present`() {
        val h = WorldHeader(levelState = ByteArray(0), legacyEtag = "")
        val decoded = NwfHeaderProto.decode(NwfHeaderProto.encode(h))
        assertArrayEquals(ByteArray(0), decoded.levelState)
        assertEquals("", decoded.legacyEtag)
    }

    @Test
    fun `negative integers are ten-byte varints`() {
        val h = WorldHeader(dataVersion = -1, createdAtMs = -2)
        val bytes = NwfHeaderProto.encode(h)
        assertEquals("28ffffffffffffffffff01", hex.formatHex(bytes, 6, 6 + 11))
        assertEquals(h, NwfHeaderProto.decode(bytes))
    }

    @Test
    fun `unknown fields are kept and written back in number order`() {
        val unknown = listOf(
            HeaderField(40, HeaderField.LEN, byteArrayOf(1, 2, 3)),
            HeaderField(10, HeaderField.VARINT, byteArrayOf(0x96.toByte(), 0x01)),
            HeaderField(33, HeaderField.I32, byteArrayOf(1, 0, 0, 0)),
            HeaderField(11, HeaderField.I64, ByteArray(8) { it.toByte() }),
        )
        val h = full().copy(unknownFields = unknown)
        val bytes = NwfHeaderProto.encode(h)
        val decoded = NwfHeaderProto.decode(bytes)
        assertEquals(unknown.sortedBy { it.number }, decoded.unknownFields)
        assertArrayEquals(bytes, NwfHeaderProto.encode(decoded))
        // field 10 sits between field 9 and field 20
        val f9 = hex.formatHex(bytes).indexOf("4a20" + "07".repeat(32))
        assertEquals("509601", hex.formatHex(bytes).substring(f9 + 68, f9 + 74))
    }

    @Test
    fun `a known field number is refused as an unknown field`() {
        val known = listOf(HeaderField(5, HeaderField.VARINT, byteArrayOf(1)))
        assertThrows<IllegalArgumentException> { WorldHeader(unknownFields = known) }
    }

    @Test
    fun `malformed headers fail closed`() {
        val cases = listOf(
            "2a00", // field 5 (varint) as length-delimited
            "0a05", // length beyond the end
            "28", // truncated varint
            "d80102", // light policy 2
            "e00105", // heightmap policy 5
            "0b", // group start
            "3202c328", // invalid UTF-8 writer
            "00", // field number 0
            "28ffffffffffffffffffff01", // 11-byte varint
        )
        for (case in cases) {
            val e = assertThrows<NwfFormatException>(case) { NwfV1RootCodec.decodeHeader(hex.parseHex(case)) }
            assertEquals(NwfFormatError.INVALID_HEADER, e.reason, case)
        }
    }

    @Test
    fun `world_uuid must be empty or 16 bytes`() {
        assertEquals(16, NwfV1RootCodec.decodeHeader(hex.parseHex("1210" + "ab".repeat(16))).worldUuidBytes.size)
        assertEquals(0, NwfV1RootCodec.decodeHeader(hex.parseHex("1200")).worldUuidBytes.size)
        for (length in listOf(1, 15, 17, 32)) {
            val bytes = hex.parseHex("12" + "%02x".format(length) + "00".repeat(length))
            val e = assertThrows<NwfFormatException>("$length") { NwfV1RootCodec.decodeHeader(bytes) }
            assertEquals(NwfFormatError.INVALID_HEADER, e.reason)
        }
    }

    @Test
    fun `dimension and unknown-field counts are capped while decoding`() {
        val dimension = "c201" + "01" + "61" // field 24, length 1, "a"
        assertEquals(256, NwfV1RootCodec.decodeHeader(hex.parseHex(dimension.repeat(256))).dimensions.size)
        val tooMany =
            assertThrows<NwfFormatException> { NwfV1RootCodec.decodeHeader(hex.parseHex(dimension.repeat(257))) }
        assertEquals(NwfFormatError.INVALID_HEADER, tooMany.reason)

        val unknown = "d00201" // field 42, varint 1
        assertEquals(1024, NwfV1RootCodec.decodeHeader(hex.parseHex(unknown.repeat(1024))).unknownFields.size)
        val tooManyUnknown =
            assertThrows<NwfFormatException> { NwfV1RootCodec.decodeHeader(hex.parseHex(unknown.repeat(1025))) }
        assertEquals(NwfFormatError.INVALID_HEADER, tooManyUnknown.reason)
    }
}
