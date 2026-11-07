@file:Suppress("MaxLineLength") // dense assertion tables read better unwrapped

package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.EntryFlags
import gg.netherite.world.nwf.HeightmapPolicy
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.NwfFormatError
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class LightStripNbtFilterTest {
    private fun filter(
        kind: ChunkKind,
        nbt: ByteArray,
        light: LightPolicy,
        heightmaps: HeightmapPolicy = HeightmapPolicy.KEEP
    ) =
        LightStripNbtFilter.filter(kind, ByteBuffer.wrap(nbt), light, heightmaps)

    @Test
    fun `strip removes exactly the light tags and leaves every other byte identical`() {
        for (style in PaletteStyle.entries) {
            for (blockEntities in listOf(0, 1, 4)) {
                val input = nbtBytes(TestChunks.chunk(3, -7, style, 42, blockEntities = blockEntities))
                val expected =
                    nbtBytes(TestChunks.chunk(3, -7, style, 42, blockEntities = blockEntities, light = false))
                val out = filter(ChunkKind.CHUNK, input, LightPolicy.STRIP)
                assertArrayEquals(expected, out.nbtView().toBytes(), "$style / $blockEntities")
                assertEquals(EntryFlags.LIGHT_STRIPPED, out.entryFlags)
                assertEquals(TestChunks.DATA_VERSION, out.dataVersion)
                assertTrue(expected.size < input.size)
            }
        }
    }

    @Test
    fun `keep passes the chunk through byte for byte`() {
        val input = nbtBytes(TestChunks.chunk(0, 0, PaletteStyle.FULL, 1))
        val out = filter(ChunkKind.CHUNK, input, LightPolicy.KEEP)
        assertArrayEquals(input, out.nbtView().toBytes())
        assertEquals(0, out.entryFlags)
    }

    @Test
    fun `heightmap strip removes only Heightmaps`() {
        val input = nbtBytes(TestChunks.chunk(1, 1, PaletteStyle.SINGLE, 5))
        val expected = nbtBytes(TestChunks.chunk(1, 1, PaletteStyle.SINGLE, 5, heightmaps = false))
        val out = filter(ChunkKind.CHUNK, input, LightPolicy.KEEP, HeightmapPolicy.STRIP)
        assertArrayEquals(expected, out.nbtView().toBytes())
        assertEquals(EntryFlags.HEIGHTMAPS_STRIPPED, out.entryFlags)

        val both = filter(ChunkKind.CHUNK, input, LightPolicy.STRIP, HeightmapPolicy.STRIP)
        val expectedBoth = nbtBytes(TestChunks.chunk(1, 1, PaletteStyle.SINGLE, 5, heightmaps = false, light = false))
        assertArrayEquals(expectedBoth, both.nbtView().toBytes())
        assertEquals(EntryFlags.LIGHT_STRIPPED or EntryFlags.HEIGHTMAPS_STRIPPED, both.entryFlags)
    }

    @Test
    fun `entities and poi pass through unchanged under strip`() {
        val entities = nbtBytes(TestChunks.entities(2, 3, 4, 9))
        val poi = nbtBytes(TestChunks.poi(9))
        val e = filter(ChunkKind.ENTITIES, entities, LightPolicy.STRIP, HeightmapPolicy.STRIP)
        val p = filter(ChunkKind.POI, poi, LightPolicy.STRIP, HeightmapPolicy.STRIP)
        assertArrayEquals(entities, e.nbtView().toBytes())
        assertArrayEquals(poi, p.nbtView().toBytes())
        assertEquals(0, e.entryFlags)
        assertEquals(TestChunks.DATA_VERSION, p.dataVersion)
    }

    @Test
    fun `light-named tags outside section compounds are kept`() {
        val section = compound(
            "Y" to TByte(0),
            "block_states" to compound("BlockLight" to TByteArray(ByteArray(4)), "palette" to compoundList(emptyList())),
            "SkyLight" to TByteArray(ByteArray(2048)),
        )
        val root = compound(
            "DataVersion" to TInt(TestChunks.DATA_VERSION),
            "BlockLight" to TByteArray(ByteArray(8)),
            "other" to compound("SkyLight" to TByteArray(ByteArray(8)), "isLightOn" to TByte(1)),
            "structures" to compound("sections" to compoundList(listOf(compound("BlockLight" to TInt(1))))),
            "sections" to compoundList(listOf(section)),
        )
        val expectedSection = compound(
            "Y" to TByte(0),
            "block_states" to compound("BlockLight" to TByteArray(ByteArray(4)), "palette" to compoundList(emptyList())),
        )
        val expected = TCompound(root.entries.dropLast(1) + ("sections" to compoundList(listOf(expectedSection))))
        val out = filter(ChunkKind.CHUNK, nbtBytes(root), LightPolicy.STRIP)
        assertArrayEquals(nbtBytes(expected), out.nbtView().toBytes())
    }

    @Test
    fun `a named root and an empty sections list round trip`() {
        val root = compound("DataVersion" to TInt(7), "sections" to list(0, emptyList()), "isLightOn" to TByte(1))
        val out = filter(ChunkKind.CHUNK, nbtBytes(root, "named-root"), LightPolicy.STRIP)
        assertArrayEquals(nbtBytes(root.without("isLightOn"), "named-root"), out.nbtView().toBytes())
        assertEquals(7, out.dataVersion)
    }

    @Test
    fun `pre-flattening Level layout passes through and is not flagged`() {
        val level = compound(
            "xPos" to TInt(1),
            "zPos" to TInt(2),
            "Sections" to compoundList(listOf(compound("Y" to TByte(0), "BlockLight" to TByteArray(ByteArray(2048))))),
            "isLightOn" to TByte(1),
        )
        val input = nbtBytes(compound("DataVersion" to TInt(1343), "Level" to level))
        val scan = LightStripNbtFilter.scan(ChunkKind.CHUNK, input, LightPolicy.STRIP, HeightmapPolicy.KEEP)
        assertArrayEquals(input, scan.filtered.nbtView().toBytes())
        assertEquals(0, scan.filtered.entryFlags)
        assertTrue(scan.legacyLayout)
        assertEquals(1343, scan.filtered.dataVersion)
    }

    @Test
    fun `scan reports the coordinates the NBT carries`() {
        val chunk = LightStripNbtFilter.scan(
            ChunkKind.CHUNK, nbtBytes(TestChunks.chunk(-5, 9, PaletteStyle.EMPTY, 1)), LightPolicy.KEEP, HeightmapPolicy.KEEP,
        )
        assertEquals(-5, chunk.x)
        assertEquals(9, chunk.z)
        val entities = LightStripNbtFilter.scan(
            ChunkKind.ENTITIES, nbtBytes(TestChunks.entities(4, -2, 1, 1)), LightPolicy.KEEP, HeightmapPolicy.KEEP,
        )
        assertEquals(4, entities.x)
        assertEquals(-2, entities.z)
    }

    @Test
    fun `missing DataVersion reads as zero`() {
        val out = filter(ChunkKind.POI, nbtBytes(compound("Sections" to compound())), LightPolicy.STRIP)
        assertEquals(0, out.dataVersion)
    }

    @Test
    fun `every truncation fails closed`() {
        val input = nbtBytes(TestChunks.chunk(0, 0, PaletteStyle.FULL, 3, sections = 2))
        for (length in 0 until input.size) {
            assertFormatError(NwfFormatError.BAD_PAYLOAD) {
                filter(ChunkKind.CHUNK, input.copyOf(length), LightPolicy.STRIP)
            }
        }
    }

    @Test
    fun `malformed NBT fails closed`() {
        val good = nbtBytes(compound("DataVersion" to TInt(1)))
        assertFormatError(NwfFormatError.BAD_PAYLOAD) { filter(ChunkKind.CHUNK, good + byteArrayOf(0), LightPolicy.KEEP) }
        // Root that is not a compound.
        assertFormatError(NwfFormatError.BAD_PAYLOAD) {
            filter(ChunkKind.CHUNK, byteArrayOf(3, 0, 0, 0, 0, 0, 1), LightPolicy.KEEP)
        }
        // Unknown tag type 13 inside the root.
        assertFormatError(NwfFormatError.BAD_PAYLOAD) {
            filter(ChunkKind.CHUNK, byteArrayOf(10, 0, 0, 13, 0, 1, 'a'.code.toByte(), 0, 0), LightPolicy.KEEP)
        }
        // Negative byte array length.
        assertFormatError(NwfFormatError.BAD_PAYLOAD) {
            filter(ChunkKind.CHUNK, byteArrayOf(10, 0, 0, 7, 0, 1, 'a'.code.toByte(), -1, -1, -1, -1, 0), LightPolicy.KEEP)
        }
        // A list with elements but no element type.
        assertFormatError(NwfFormatError.BAD_PAYLOAD) {
            filter(ChunkKind.CHUNK, byteArrayOf(10, 0, 0, 9, 0, 1, 'a'.code.toByte(), 0, 0, 0, 0, 1, 0), LightPolicy.KEEP)
        }
    }

    @Test
    fun `nesting deeper than the limit fails closed without overflowing the stack`() {
        var tag: TCompound = compound()
        repeat(LightStripNbtFilter.MAX_DEPTH + 10) { tag = compound("n" to tag) }
        assertFormatError(NwfFormatError.BAD_PAYLOAD) { filter(ChunkKind.CHUNK, nbtBytes(tag), LightPolicy.STRIP) }

        var ok: TCompound = compound()
        repeat(LightStripNbtFilter.MAX_DEPTH - 2) { ok = compound("n" to ok) }
        val bytes = nbtBytes(ok)
        assertArrayEquals(bytes, filter(ChunkKind.CHUNK, bytes, LightPolicy.STRIP).nbtView().toBytes())
    }
}
