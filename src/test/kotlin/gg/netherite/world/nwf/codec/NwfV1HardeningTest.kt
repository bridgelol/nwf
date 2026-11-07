@file:Suppress("MaxLineLength") // dense assertion tables read better unwrapped

package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.HeightmapPolicy
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.convert.BuiltWorld
import gg.netherite.world.nwf.convert.WorldBuildOptions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Hardening checks: TOC rules, decode limits, base rules, one zstd frame, legacy chunks. */
class NwfV1HardeningTest {
    private val pipeline = ZstdFramePipeline()
    private val rootCodec = NwfV1RootCodec()
    private val factory = NwfV1WorldBuilderFactory(pipeline, rootCodec)

    // ---- TOC flag and dictSlot rules apply to known sections only ----------------------------------------------

    @Test
    fun `an unknown optional section is skipped whatever its flag or dict bits say`() {
        val index = RawRoot.indexSection(emptyList())
        val badFlags = RawRoot.assemble(listOf(RawRoot.headerSection(), RawRoot.Sec(99, NwfFormat.TOC_FLAG_OPTIONAL or 0x40, byteArrayOf(1)), index))
        assertEquals(3, rootCodec.parse(ByteBuffer.wrap(badFlags)).toc.size)

        val dictSlot = RawRoot.assemble(listOf(RawRoot.headerSection(), RawRoot.Sec(99, NwfFormat.TOC_FLAG_OPTIONAL, byteArrayOf(1)), index))
        // TOC entry 1 (the unknown section): dictSlot at +4 set to 0.
        ByteBuffer.wrap(dictSlot).order(ByteOrder.LITTLE_ENDIAN).putShort(32 + 32 + 4, 0)
        assertEquals(3, rootCodec.parse(ByteBuffer.wrap(RawRoot.fixHeaderCrc(dictSlot))).toc.size)

        val dictFlag = RawRoot.assemble(listOf(RawRoot.headerSection(), RawRoot.Sec(99, NwfFormat.TOC_FLAG_OPTIONAL or NwfFormat.TOC_FLAG_DICT, byteArrayOf(1)), index))
        assertEquals(3, rootCodec.parse(ByteBuffer.wrap(dictFlag)).toc.size)

        // Its CRC32C is still checked.
        val badCrc = RawRoot.assemble(listOf(RawRoot.headerSection(), RawRoot.Sec(99, NwfFormat.TOC_FLAG_OPTIONAL or 0x40, byteArrayOf(1), crc = 5), index))
        assertFormatError(NwfFormatError.CRC_MISMATCH) { rootCodec.parse(ByteBuffer.wrap(badCrc)) }
    }

    @Test
    fun `an unknown required section is refused, and known sections keep the flag and dict rules`() {
        val index = RawRoot.indexSection(emptyList())
        val required = RawRoot.assemble(listOf(RawRoot.headerSection(), RawRoot.Sec(99, 0x40, byteArrayOf(1)), index))
        assertFormatError(NwfFormatError.UNKNOWN_REQUIRED_SECTION) { rootCodec.parse(ByteBuffer.wrap(required)) }
        val known = RawRoot.assemble(listOf(RawRoot.headerSection(), RawRoot.Sec(6, NwfFormat.TOC_FLAG_OPTIONAL or 0x40, byteArrayOf(1)), index))
        assertFormatError(NwfFormatError.UNKNOWN_FLAGS) { rootCodec.parse(ByteBuffer.wrap(known)) }
        val knownDict = RawRoot.assemble(listOf(RawRoot.headerSection(), RawRoot.Sec(6, NwfFormat.TOC_FLAG_OPTIONAL, byteArrayOf(1)), index))
        ByteBuffer.wrap(knownDict).order(ByteOrder.LITTLE_ENDIAN).putShort(32 + 32 + 4, 0)
        assertFormatError(NwfFormatError.BAD_SECTIONS) { rootCodec.parse(ByteBuffer.wrap(RawRoot.fixHeaderCrc(knownDict))) }
    }

    @Test
    fun `sections that would decode past the root total are refused before decompression`() {
        val big = 200 * 1024 * 1024
        val root = RawRoot.assemble(
            listOf(
                RawRoot.headerSection(),
                RawRoot.Sec(5, NwfFormat.TOC_FLAG_ZSTD, byteArrayOf(1), rawLength = big),
                RawRoot.Sec(6, NwfFormat.TOC_FLAG_OPTIONAL or NwfFormat.TOC_FLAG_ZSTD, byteArrayOf(1), rawLength = big),
                RawRoot.indexSection(emptyList()),
            ),
        )
        assertFormatError(NwfFormatError.LIMIT_EXCEEDED) { rootCodec.parse(ByteBuffer.wrap(root)) }
    }

    @Test
    fun `a known section declaring more than the decode limit is refused`() {
        val over = (NwfFormat.MAX_ROOT_RAW_BYTES + 1).toInt()
        val root = RawRoot.assemble(
            listOf(
                RawRoot.headerSection(),
                RawRoot.Sec(6, NwfFormat.TOC_FLAG_OPTIONAL or NwfFormat.TOC_FLAG_ZSTD, byteArrayOf(1), rawLength = over),
                RawRoot.indexSection(emptyList()),
            ),
        )
        assertFormatError(NwfFormatError.LIMIT_EXCEEDED) { rootCodec.parse(ByteBuffer.wrap(root)) }
    }

    @Test
    fun `unknown optional sections are never decoded, so their declared sizes count toward no limit`() {
        val over = (NwfFormat.MAX_ROOT_RAW_BYTES + 1).toInt()
        val big = 200 * 1024 * 1024
        val root = RawRoot.assemble(
            listOf(
                RawRoot.headerSection(),
                RawRoot.Sec(97, NwfFormat.TOC_FLAG_OPTIONAL or NwfFormat.TOC_FLAG_ZSTD, byteArrayOf(1), rawLength = over),
                RawRoot.Sec(98, NwfFormat.TOC_FLAG_OPTIONAL or NwfFormat.TOC_FLAG_ZSTD, byteArrayOf(1), rawLength = big),
                RawRoot.Sec(99, NwfFormat.TOC_FLAG_OPTIONAL or NwfFormat.TOC_FLAG_ZSTD, byteArrayOf(1), rawLength = big),
                RawRoot.indexSection(emptyList()),
            ),
        )
        val view = rootCodec.parse(ByteBuffer.wrap(root))
        assertEquals(5, view.toc.size)
        assertEquals(0, view.entryCount)

        // The largest u32 a skipped section can declare reads back as itself, never as a negative length.
        val maxU32 = RawRoot.assemble(
            listOf(
                RawRoot.headerSection(),
                RawRoot.Sec(97, NwfFormat.TOC_FLAG_OPTIONAL or NwfFormat.TOC_FLAG_ZSTD, byteArrayOf(1), rawLength = -1),
                RawRoot.indexSection(emptyList()),
            ),
        )
        assertEquals(0xFFFF_FFFFL, rootCodec.parse(ByteBuffer.wrap(maxU32)).toc[1].rawLength)
    }

    // ---- one zstd frame, bounded expansion -------------------------------------------------------------------------

    @Test
    fun `a zstd body with trailing bytes or a second frame fails closed`() {
        val raw = ByteArray(4096) { (it % 7).toByte() }
        val frame = ZstdSupport.compress(raw, 3)
        assertArrayEquals(raw, ZstdSupport.decompress(frame, raw.size, "test"))
        assertFormatError(NwfFormatError.BAD_PAYLOAD) { ZstdSupport.decompress(frame + byteArrayOf(0, 0, 0), raw.size, "test") }
        assertFormatError(NwfFormatError.BAD_PAYLOAD) { ZstdSupport.decompress(frame + frame, raw.size, "test") }
    }

    @Test
    fun `a tiny zstd frame declaring a huge size fails before allocating it`() {
        val declared = 256 * 1024 * 1024
        // Frame header: magic, descriptor (single segment, 8-byte content size), content size; then one last RLE
        // block of 128 KiB (block header 3 bytes, the byte). 17 bytes claiming 256 MiB.
        val frame = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xFD2FB528.toInt()).put(0xE0.toByte()).putLong(declared.toLong())
        val block = (128 * 1024 shl 3) or (1 shl 1) or 1
        frame.put((block and 0xFF).toByte()).put((block shr 8 and 0xFF).toByte()).put((block shr 16 and 0xFF).toByte()).put(0)
        assertFormatError(NwfFormatError.BAD_PAYLOAD) { ZstdSupport.decompress(frame.array(), declared, "test") }
    }

    // ---- base rules ------------------------------------------------------------------------------------------------

    private fun buildOptions(base: NwfV1RootView? = null, dicts: Map<ChunkKind, gg.netherite.world.nwf.NwfDictionary> = emptyMap()) = WorldBuildOptions(
        contentClass = "test",
        segmentPrefix = "test-prefix",
        saveLevel = 3,
        lightPolicy = LightPolicy.STRIP,
        inlineBudgetBytes = 16L * 1024 * 1024,
        segmentTargetBytes = 64 * 1024,
        dictionaries = dicts,
        base = base,
    )

    private fun build(options: WorldBuildOptions, dimensions: List<String>, content: Map<ChunkKey, ByteArray>): BuiltWorld {
        val builder = factory.create(options)
        builder.header(header(dimensions))
        content.forEach { (key, bytes) -> builder.chunk(key, ByteBuffer.wrap(bytes)) }
        return builder.finish()
    }

    private fun chunk(dim: Int, x: Int, z: Int) = ChunkKey(dim, ChunkKind.CHUNK, x, z) to nbtBytes(TestChunks.chunk(x, z, PaletteStyle.FULL, x + z))

    @Test
    fun `a base that has a base, or is no TEMPLATE root, is refused with a format error`() {
        val world = build(buildOptions(), listOf("minecraft:overworld"), mapOf(chunk(0, 0, 0)))
        val plain = rootCodec.parse(ByteBuffer.wrap(rootCodec.write(world.root)))
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) { factory.create(buildOptions(base = plain)) }

        val nested = world.root.copy(
            flags = NwfFormat.ROOT_FLAG_HAS_BASE,
            header = world.root.header.copy(baseRootSha = ByteArray(32) { 1 }),
        )
        val nestedView = rootCodec.parse(ByteBuffer.wrap(rootCodec.write(nested)))
        assertFormatError(NwfFormatError.NESTED_BASE) { factory.create(buildOptions(base = nestedView)) }
    }

    @Test
    fun `a world on a base starts its DICTS and dimensions with the base's`() {
        val baseWorld = build(
            buildOptions(dicts = mapOf(ChunkKind.CHUNK to TestDictionaries.chunk)),
            listOf("minecraft:overworld"),
            mapOf(chunk(0, 0, 0), chunk(0, 1, 0)),
        )
        val base = rootCodec.parse(ByteBuffer.wrap(rootCodec.write(baseWorld.root.copy(flags = NwfFormat.ROOT_FLAG_TEMPLATE))))
        // The converter numbers the nether 0 and the overworld 1, and brings another CHUNK dictionary.
        val world = build(
            buildOptions(base = base, dicts = mapOf(ChunkKind.CHUNK to TestDictionaries.other)),
            listOf("minecraft:the_nether", "minecraft:overworld"),
            mapOf(chunk(0, 5, 5), chunk(1, 0, 0)),
        )
        val view = rootCodec.parse(ByteBuffer.wrap(rootCodec.write(world.root)))
        assertEquals(listOf("minecraft:overworld", "minecraft:the_nether"), view.header.dimensions)
        assertEquals(base.dictionaries, view.dictionaries.take(base.dictionaries.size))
        assertEquals(TestDictionaries.other.sha256, view.dictionaries[1].sha256)
        // Converter dim 1 (overworld) is dim 0 here; converter dim 0 (nether) is dim 1; base key (0, 1, 0) is tombstoned.
        val live = view.entries().filter { !it.isTombstone }.map { it.key }.toSet()
        assertEquals(setOf(ChunkKey(0, ChunkKind.CHUNK, 0, 0), ChunkKey(1, ChunkKind.CHUNK, 5, 5)), live)
        assertEquals(setOf(ChunkKey(0, ChunkKind.CHUNK, 1, 0)), view.entries().filter { it.isTombstone }.map { it.key }.toSet())
        val dicts = lookupOf(TestDictionaries.chunk, TestDictionaries.other)
        for (e in view.entries().filter { !it.isTombstone }) {
            assertEquals(1, e.dictSlot)
            val nbt = pipeline.decode(EncodedFrame(NwfFrameHeaders.of(e), e.dataVersion, view.inlinePayload(e)), dicts, null)
            val scan = LightStripNbtFilter.scan(ChunkKind.CHUNK, nbt.toBytes(), LightPolicy.KEEP, HeightmapPolicy.KEEP)
            assertEquals(e.x, scan.x)
        }
    }

    // ---- legacy chunks pass through ------------------------------------------------------------------------------

    @Test
    fun `a Level chunk with a root sections list passes through unchanged and unflagged`() {
        val input = nbtBytes(
            compound(
                "DataVersion" to TInt(1343),
                "sections" to compoundList(listOf(compound("Y" to TByte(0), "BlockLight" to TByteArray(ByteArray(2048))))),
                "isLightOn" to TByte(1),
                "Level" to compound("xPos" to TInt(1), "zPos" to TInt(2)),
            ),
        )
        val scan = LightStripNbtFilter.scan(ChunkKind.CHUNK, input, LightPolicy.STRIP, HeightmapPolicy.STRIP)
        assertArrayEquals(input, scan.filtered.nbtView().toBytes())
        assertEquals(0, scan.filtered.entryFlags)
    }
}
