@file:Suppress("MaxLineLength") // dense assertion tables read better unwrapped

package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.DictRef
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.EntryFlags
import gg.netherite.world.nwf.FrameCodec
import gg.netherite.world.nwf.IndexEntry
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.RootDraft
import gg.netherite.world.nwf.SectionType
import gg.netherite.world.nwf.SegmentRef
import gg.netherite.world.nwf.Sha256
import gg.netherite.world.nwf.WorldHeader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class NwfV1RootCodecTest {
    private val codec = NwfV1RootCodec()
    private val pipeline = ZstdFramePipeline()

    private fun frames(): Map<ChunkKey, EncodedFrame> =
        sampleNbt().mapValues { (key, nbt) -> pipeline.encode(key, ByteBuffer.wrap(nbt), options()) }

    private fun inlineDraft(frames: Map<ChunkKey, EncodedFrame> = frames()): RootDraft {
        val inline = ByteArrayOutputStream()
        val entries = frames.toSortedMap().map { (_, frame) ->
            val offset = inline.size()
            inline.write(frame.payloadView().toBytes())
            entryOf(frame, NwfFormat.NONE_U16, offset)
        }
        return RootDraft(0, 1, header(), emptyList(), emptyList(), entries, ByteBuffer.wrap(inline.toByteArray()))
    }

    private fun tombstone(key: ChunkKey) = IndexEntry(
        key.x, key.z, key.dim, key.kind, FrameCodec.RAW, EntryFlags.TOMBSTONE, NwfFormat.NONE_U16, NwfFormat.NONE_U16,
        0, 0, 0, 0, 0,
    )

    private fun smallRoot(): ByteArray {
        val key = ChunkKey(0, ChunkKind.POI, 0, 0)
        return codec.write(inlineDraft(mapOf(key to pipeline.encode(key, ByteBuffer.wrap(nbtBytes(TestChunks.poi(1))), options()))))
    }

    @Test
    fun `inline root round trips every entry and payload`() {
        val frames = frames()
        val draft = inlineDraft(frames)
        val bytes = codec.write(draft)
        val view = codec.parse(ByteBuffer.wrap(bytes))
        assertEquals(1L, view.generation)
        assertEquals(0, view.minor)
        assertEquals(draft.header, view.header)
        assertEquals(draft.entries, view.entries().toList())
        assertEquals(sha256(bytes), view.rootSha)
        assertEquals(bytes.size.toLong(), view.storedLength)
        for ((key, frame) in frames) {
            val entry = view.find(key) ?: error("missing $key")
            assertArrayEquals(frame.payloadView().toBytes(), view.inlinePayload(entry).toBytes())
            assertFalse(view.hasNoEntries(key.dim, key.x, key.z))
        }
        assertTrue(view.hasNoEntries(0, 1000, 1000))
        assertNull(view.find(ChunkKey(0, ChunkKind.CHUNK, 1000, 1000)))
        // Writing is deterministic: the same draft gives the same bytes.
        assertArrayEquals(bytes, codec.write(draft))
    }

    @Test
    fun `segments, dictionaries, tombstones, base, stats and unknown minor all carry over`() {
        val segSha = Sha256(ByteArray(32) { it.toByte() })
        val segEntry = IndexEntry(4, 4, 1, ChunkKind.CHUNK, FrameCodec.ZSTD_DICT, 0, 0, 1, 32, 100, 300, 123, 77)
        val deltaEntry = IndexEntry(
            5, 4, 1, ChunkKind.CHUNK, FrameCodec.ZSTD_BASE_DELTA, EntryFlags.BASE_DELTA or EntryFlags.LIGHT_STRIPPED,
            0, NwfFormat.NONE_U16, 160, 20, 300, 9, 77,
        )
        val h = WorldHeader(
            dimensions = listOf("minecraft:overworld", "minecraft:the_nether"),
            baseRootSha = ByteArray(32) { 1 },
            baseKey = "templates/example",
            contentClass = "example",
            parentRootSha = ByteArray(32) { 2 },
        )
        val draft = RootDraft(
            flags = NwfFormat.ROOT_FLAG_HAS_BASE,
            generation = 42,
            header = h,
            dictionaries = listOf(DictRef(11, Sha256(ByteArray(32) { 3 })), DictRef(-5, Sha256(ByteArray(32) { 4 }))),
            segments = listOf(SegmentRef(segSha, 7, 4096, 2)),
            entries = listOf(tombstone(ChunkKey(0, ChunkKind.CHUNK, 0, 0)), segEntry, deltaEntry),
            inline = ByteBuffer.allocate(0),
            stats = byteArrayOf(8, 1),
        )
        val bytes = codec.write(draft)
        val view = codec.parse(ByteBuffer.wrap(bytes))
        assertEquals(42L, view.generation)
        assertTrue(view.hasBase)
        assertFalse(view.isTemplate)
        assertEquals(draft.dictionaries, view.dictionaries)
        assertEquals(draft.segments, view.segments)
        assertEquals(draft.entries, view.entries().toList())
        assertEquals(h, view.header)
        assertArrayEquals(byteArrayOf(8, 1), view.stats())
        assertEquals(0L, view.inlineLength)
        assertTrue(view.find(ChunkKey(0, ChunkKind.CHUNK, 0, 0))!!.isTombstone)
        assertEquals(setOf(1, 2, 3, 4, 6), view.toc.map { it.type }.toSet())

        // A higher minor is accepted.
        val minor = bytes.copyOf()
        ByteBuffer.wrap(minor).order(ByteOrder.LITTLE_ENDIAN).putShort(6, 3)
        assertEquals(3, codec.parse(ByteBuffer.wrap(RawRoot.fixHeaderCrc(minor))).minor)
    }

    @Test
    fun `an empty world is HEADER plus INDEX`() {
        val bytes = codec.write(RootDraft(0, 1, header(), emptyList(), emptyList(), emptyList(), ByteBuffer.allocate(0)))
        val view = codec.parse(ByteBuffer.wrap(bytes))
        assertEquals(0, view.entryCount)
        assertEquals(listOf(SectionType.HEADER.id, SectionType.INDEX.id), view.toc.map { it.type })
        assertEquals(NwfFormat.TOC_FLAG_ZSTD, view.toc[1].flags)
    }

    @Test
    fun `packed inline compresses INLINE as one stream and reads back the same payloads`() {
        val frames = sampleNbt().mapValues { (key, nbt) ->
            pipeline.encode(key, ByteBuffer.wrap(nbt), options().copy(raw = true))
        }
        val draft = inlineDraft(frames)
        val packedCodec = NwfV1RootCodec(RootCodecOptions(packInlineMaxRawBytes = 16L * 1024 * 1024))
        val plain = codec.write(draft)
        val packed = packedCodec.write(draft)
        assertTrue(packed.size < plain.size, "${packed.size} vs ${plain.size}")
        val view = codec.parse(ByteBuffer.wrap(packed))
        val inlineToc = view.toc.single { it.type == SectionType.INLINE.id }
        assertEquals(NwfFormat.TOC_FLAG_ZSTD, inlineToc.flags)
        for ((key, frame) in frames) {
            val entry = view.find(key)!!
            assertEquals(FrameCodec.RAW, entry.codec)
            val decoded = pipeline.decode(EncodedFrame(NwfFrameHeaders.of(entry), entry.dataVersion, view.inlinePayload(entry)), NO_DICTS, null)
            assertArrayEquals(pipeline.decode(frame, NO_DICTS, null).toBytes(), decoded.toBytes())
        }
    }

    @Test
    fun `a draft's packInlineLevel packs INLINE, and the parse decompresses it once, never per payload read`() {
        val frames = sampleNbt().mapValues { (key, nbt) ->
            pipeline.encode(key, ByteBuffer.wrap(nbt), options().copy(raw = true))
        }
        val packed = codec.write(inlineDraft(frames).copy(packInlineLevel = 3))
        val before = NwfZstdCounters.decompressions()
        val view = codec.parse(ByteBuffer.wrap(packed))
        assertEquals(2, NwfZstdCounters.decompressions() - before, "INDEX and INLINE, once each")
        assertTrue(view.isInlinePacked)
        val afterParse = NwfZstdCounters.decompressions()
        repeat(3) {
            for (key in frames.keys) {
                val entry = view.find(key)!!
                pipeline.decode(EncodedFrame(NwfFrameHeaders.of(entry), entry.dataVersion, view.inlinePayload(entry)), NO_DICTS, null)
            }
        }
        assertEquals(afterParse, NwfZstdCounters.decompressions(), "RAW frames of a packed root never decompress")
        assertTrue(!codec.parse(ByteBuffer.wrap(codec.write(inlineDraft(frames)))).isInlinePacked, "no level: unpacked")
    }

    @Test
    fun `layout matches an independent assembly of the same sections`() {
        val bytes = codec.write(inlineDraft())
        val view = codec.parse(ByteBuffer.wrap(bytes))
        val sections = view.toc.map { t ->
            RawRoot.Sec(t.type, t.flags, bytes.copyOfRange(t.offset.toInt(), (t.offset + t.storedLength).toInt()), t.rawLength.toInt())
        }
        assertArrayEquals(bytes, RawRoot.assemble(sections, generation = 1))
        assertArrayEquals(NwfFormat.ROOT_MAGIC, bytes.copyOf(4))
    }

    @Test
    fun `bad magic and unknown major fail closed`() {
        val bytes = smallRoot()
        assertFormatError(NwfFormatError.BAD_MAGIC) {
            codec.parse(ByteBuffer.wrap(bytes.copyOf().also { it[0] = 'X'.code.toByte() }))
        }
        assertFormatError(NwfFormatError.BAD_MAGIC) {
            codec.parse(ByteBuffer.wrap(bytes.copyOf().also { System.arraycopy(NwfFormat.SEGMENT_MAGIC, 0, it, 0, 4) }))
        }
        for (major in listOf(0, 2, 0xFFFF)) {
            val patched = bytes.copyOf()
            ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN).putShort(4, major.toShort())
            assertFormatError(NwfFormatError.UNSUPPORTED_MAJOR) { codec.parse(ByteBuffer.wrap(patched)) }
            assertFormatError(NwfFormatError.UNSUPPORTED_MAJOR) { codec.parse(ByteBuffer.wrap(RawRoot.fixHeaderCrc(patched))) }
        }
    }

    @Test
    fun `every truncation fails closed`() {
        val bytes = smallRoot()
        for (length in 0 until bytes.size) {
            assertTrue(!failsClosedOrParses { codec.parse(ByteBuffer.wrap(bytes.copyOf(length))) }, "length $length parsed")
        }
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) { codec.parse(ByteBuffer.wrap(bytes + byteArrayOf(0))) }
    }

    @Test
    fun `every single bit flip fails closed`() {
        val bytes = smallRoot()
        for (bit in 0 until bytes.size * 8) {
            assertTrue(!failsClosedOrParses { codec.parse(ByteBuffer.wrap(bytes.flipBit(bit))) }, "bit $bit parsed")
        }
    }

    @Test
    fun `fuzzed roots throw nothing but NwfFormatException`() {
        val base = codec.write(inlineDraft())
        val random = Random(1234)
        repeat(3000) {
            val mutated = base.copyOf(if (random.nextInt(4) == 0) random.nextInt(base.size) else base.size)
            repeat(1 + random.nextInt(8)) {
                if (mutated.isNotEmpty()) mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte()
            }
            // Re-sealing the header CRC lets mutations reach the TOC, section and index checks.
            if (mutated.size >= 32 && random.nextBoolean()) {
                runCatching { RawRoot.fixHeaderCrc(mutated) }
            }
            failsClosedOrParses { codec.parse(ByteBuffer.wrap(mutated)) }
        }
        repeat(500) {
            failsClosedOrParses { codec.parse(ByteBuffer.wrap(Random(it).nextBytes(it))) }
        }
    }

    @Test
    fun `unknown optional sections are skipped and unknown required ones refused`() {
        val index = RawRoot.indexSection(emptyList())
        val optional = RawRoot.assemble(listOf(RawRoot.headerSection(), RawRoot.Sec(99, NwfFormat.TOC_FLAG_OPTIONAL, byteArrayOf(1, 2, 3)), index))
        assertEquals(3, codec.parse(ByteBuffer.wrap(optional)).toc.size)
        val required = RawRoot.assemble(listOf(RawRoot.headerSection(), RawRoot.Sec(99, 0, byteArrayOf(1)), index))
        assertFormatError(NwfFormatError.UNKNOWN_REQUIRED_SECTION) { codec.parse(ByteBuffer.wrap(required)) }
    }

    @Test
    fun `section structure is enforced`() {
        val h = RawRoot.headerSection()
        val index = RawRoot.indexSection(emptyList())
        assertFormatError(NwfFormatError.BAD_SECTIONS) { codec.parse(ByteBuffer.wrap(RawRoot.assemble(listOf(h)))) }
        assertFormatError(NwfFormatError.BAD_SECTIONS) {
            codec.parse(ByteBuffer.wrap(RawRoot.assemble(listOf(h, index, index))))
        }
        assertFormatError(NwfFormatError.BAD_SECTIONS) { codec.parse(ByteBuffer.wrap(RawRoot.assemble(emptyList()))) }
        assertFormatError(NwfFormatError.CRC_MISMATCH) {
            codec.parse(ByteBuffer.wrap(RawRoot.assemble(listOf(RawRoot.Sec(1, 0, h.stored, crc = 1), index))))
        }
        assertFormatError(NwfFormatError.UNKNOWN_FLAGS) {
            codec.parse(ByteBuffer.wrap(RawRoot.assemble(listOf(RawRoot.Sec(1, 0x10, h.stored), index))))
        }
        assertFormatError(NwfFormatError.BAD_SECTIONS) {
            codec.parse(ByteBuffer.wrap(RawRoot.assemble(listOf(RawRoot.Sec(1, NwfFormat.TOC_FLAG_DICT, h.stored), index))))
        }
        assertFormatError(NwfFormatError.INVALID_HEADER) {
            codec.parse(ByteBuffer.wrap(RawRoot.assemble(listOf(RawRoot.Sec(1, 0, byteArrayOf(-1, -1, -1)), index))))
        }
        assertFormatError(NwfFormatError.UNKNOWN_FLAGS) {
            codec.parse(ByteBuffer.wrap(RawRoot.assemble(listOf(h, index), flags = 0x10)))
        }
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) {
            codec.parse(ByteBuffer.wrap(RawRoot.assemble(listOf(h, index), generation = 0)))
        }
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) {
            codec.parse(ByteBuffer.wrap(RawRoot.assemble(listOf(h, index), flags = NwfFormat.ROOT_FLAG_HAS_BASE)))
        }
        val baseHeader = RawRoot.headerSection(WorldHeader(baseRootSha = ByteArray(32), dimensions = listOf("d")))
        assertFormatError(NwfFormatError.NESTED_BASE) {
            codec.parse(
                ByteBuffer.wrap(
                    RawRoot.assemble(listOf(baseHeader, index), flags = NwfFormat.ROOT_FLAG_HAS_BASE or NwfFormat.ROOT_FLAG_TEMPLATE),
                ),
            )
        }
    }

    @Test
    fun `index entries out of range fail closed`() {
        val h = RawRoot.headerSection()
        fun parseWith(vararg entries: RawRoot.Idx, inline: ByteArray = ByteArray(16)) = codec.parse(
            ByteBuffer.wrap(RawRoot.assemble(listOf(h, RawRoot.indexSection(entries.toList()), RawRoot.inlineSection(inline)))),
        )
        val payload = ByteArray(16)
        val ok = RawRoot.Idx(0, 0, codec = 0, stored = 16, raw = 16, crc = crc(payload))
        assertEquals(1, parseWith(ok).entryCount)
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) {
            parseWith(RawRoot.Idx(0, 0, codec = 0, offset = 1, stored = 16, raw = 16))
        }
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) { parseWith(RawRoot.Idx(0, 0, location = 0, stored = 4, raw = 9)) }
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) { parseWith(RawRoot.Idx(0, 0, dim = 1, stored = 4, raw = 9)) }
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) {
            parseWith(RawRoot.Idx(0, 0, codec = 2, dictSlot = 0, stored = 4, raw = 9))
        }
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) { parseWith(RawRoot.Idx(0, 0, kind = 3, stored = 4, raw = 9)) }
        assertFormatError(NwfFormatError.UNKNOWN_FLAGS) { parseWith(RawRoot.Idx(0, 0, flags = 0x10, stored = 4, raw = 9)) }
        assertFormatError(NwfFormatError.LIMIT_EXCEEDED) { parseWith(RawRoot.Idx(0, 0, stored = 4, raw = Int.MAX_VALUE)) }
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) { parseWith(RawRoot.Idx(0, 0, stored = -1, raw = 9)) }
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) {
            parseWith(RawRoot.Idx(0, 0, codec = 3, flags = EntryFlags.BASE_DELTA, stored = 4, raw = 9))
        }
        assertFormatError(NwfFormatError.UNKNOWN_FLAGS) {
            parseWith(RawRoot.Idx(0, 0, flags = EntryFlags.TOMBSTONE, codec = 1))
        }
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) {
            parseWith(RawRoot.Idx(0, 0, flags = EntryFlags.TOMBSTONE, codec = 0, stored = 1, raw = 1))
        }
        // INDEX body length disagreeing with its count.
        val shortIndex = RawRoot.Sec(4, 0, byteArrayOf(2, 0, 0, 0))
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) {
            codec.parse(ByteBuffer.wrap(RawRoot.assemble(listOf(h, shortIndex))))
        }
    }

    @Test
    fun `segment frame counts must fit the segment, on write and on read`() {
        fun draft(frameCount: Int) = RootDraft(
            0, 1, header(), emptyList(), listOf(SegmentRef(Sha256(ByteArray(32)), 0, 200, frameCount)), emptyList(), ByteBuffer.allocate(0),
        )
        codec.write(draft(5)) // (200 - 36) / 28 = 5 frames fit
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) { codec.write(draft(-1)) }
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) { codec.write(draft(6)) }
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) { codec.write(draft(Int.MAX_VALUE)) }

        val tooMany = RawRoot.Sec(
            3, 0,
            ByteBuffer.allocate(2 + 48).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(1)
                put(ByteArray(32))
                putInt(0)
                putLong(200)
                putInt(6)
            }.array(),
        )
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) {
            codec.parse(ByteBuffer.wrap(RawRoot.assemble(listOf(RawRoot.headerSection(), tooMany, RawRoot.indexSection(emptyList())))))
        }
    }

    @Test
    fun `segment entries must lie inside their segment`() {
        val segments = RawRoot.Sec(
            3, 0,
            ByteBuffer.allocate(2 + 48).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(1)
                put(ByteArray(32))
                putInt(0)
                putLong(200)
                putInt(1)
            }.array()
        )
        fun parseWith(
            e: RawRoot.Idx
        ) = codec.parse(ByteBuffer.wrap(RawRoot.assemble(listOf(RawRoot.headerSection(), segments, RawRoot.indexSection(listOf(e))))))
        assertEquals(1, parseWith(RawRoot.Idx(0, 0, location = 0, offset = 32, stored = 100, raw = 300)).segments.size)
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) {
            parseWith(RawRoot.Idx(0, 0, location = 0, offset = 32, stored = 200, raw = 300))
        }
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) {
            parseWith(RawRoot.Idx(0, 0, location = 0, offset = 4, stored = 1, raw = 300))
        }
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) {
            parseWith(RawRoot.Idx(0, 0, location = 1, offset = 32, stored = 1, raw = 3))
        }
    }

    @Test
    fun `unsorted or repeated index keys fail closed on read and write`() {
        val h = RawRoot.headerSection()
        fun parseWith(
            vararg e: RawRoot.Idx
        ) = codec.parse(ByteBuffer.wrap(RawRoot.assemble(listOf(h, RawRoot.indexSection(e.toList())))))
        assertEquals(2, parseWith(RawRoot.Idx(31, 0).tomb(), RawRoot.Idx(32, 0).tomb()).entryCount)
        assertFormatError(NwfFormatError.UNSORTED_INDEX) { parseWith(RawRoot.Idx(32, 0).tomb(), RawRoot.Idx(31, 0).tomb()) }
        assertFormatError(NwfFormatError.UNSORTED_INDEX) { parseWith(RawRoot.Idx(1, 1).tomb(), RawRoot.Idx(1, 1).tomb()) }
        // Region order: (-1, 0) is region -1 and sorts before (0, 0) and (31, 0), which share region 0.
        assertEquals(3, parseWith(RawRoot.Idx(-1, 5).tomb(), RawRoot.Idx(0, 0).tomb(), RawRoot.Idx(31, 0).tomb()).entryCount)
        assertFormatError(NwfFormatError.UNSORTED_INDEX) { parseWith(RawRoot.Idx(0, 40).tomb(), RawRoot.Idx(31, 0).tomb()) }

        val draft =
            RootDraft(0, 1, header(), emptyList(), emptyList(), listOf(tombstone(ChunkKey(0, ChunkKind.CHUNK, 2, 0)), tombstone(ChunkKey(0, ChunkKind.CHUNK, 1, 0))), ByteBuffer.allocate(0))
        assertFormatError(NwfFormatError.UNSORTED_INDEX) { codec.write(draft) }
    }

    private fun RawRoot.Idx.tomb() = RawRoot.Idx(x, z, dim, kind, codec = 0, flags = EntryFlags.TOMBSTONE, dataVersion = 0)

    @Test
    fun `an unknown codec fails closed for that chunk only`() {
        val payload = ByteArray(8) { 1 }
        val root = RawRoot.assemble(
            listOf(
                RawRoot.headerSection(),
                RawRoot.indexSection(
                    listOf(
                        RawRoot.Idx(0, 0, codec = 0, stored = 8, raw = 8, crc = crc(payload)),
                        RawRoot.Idx(1, 0, codec = 9, stored = 8, raw = 8, crc = crc(payload)),
                    ),
                ),
                RawRoot.inlineSection(payload),
            ),
        )
        val view = codec.parse(ByteBuffer.wrap(root))
        assertEquals(FrameCodec.RAW, view.find(ChunkKey(0, ChunkKind.CHUNK, 0, 0))!!.codec)
        assertFormatError(NwfFormatError.UNKNOWN_CODEC) { view.find(ChunkKey(0, ChunkKind.CHUNK, 1, 0)) }
        assertFalse(view.hasNoEntries(0, 1, 0))
    }

    @Test
    fun `the writer refuses drafts that would not read back`() {
        val good = inlineDraft()
        val badCrc = good.copy(entries = good.entries.mapIndexed { i, e -> if (i == 0) e.copy(crc32c = e.crc32c + 1) else e })
        assertFormatError(NwfFormatError.CRC_MISMATCH) { codec.write(badCrc) }
        assertFormatError(NwfFormatError.NESTED_BASE) {
            codec.write(
                good.copy(
                    flags = NwfFormat.ROOT_FLAG_TEMPLATE or NwfFormat.ROOT_FLAG_HAS_BASE,
                    header = WorldHeader(baseRootSha = ByteArray(32), dimensions = listOf("d")),
                ),
            )
        }
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) { codec.write(good.copy(flags = NwfFormat.ROOT_FLAG_HAS_BASE)) }
        assertFormatError(NwfFormatError.UNKNOWN_FLAGS) { codec.write(good.copy(flags = 0x100)) }
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) { codec.write(good.copy(generation = 0)) }
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) { codec.write(good.copy(inline = ByteBuffer.allocate(4))) }
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) { codec.write(good.copy(header = header(emptyList()))) }
    }
}
