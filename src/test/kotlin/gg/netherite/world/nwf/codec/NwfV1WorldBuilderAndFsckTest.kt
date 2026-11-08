@file:Suppress("MaxLineLength") // dense assertion tables read better unwrapped

package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.DictionaryLookup
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.EntryFlags
import gg.netherite.world.nwf.FrameCodec
import gg.netherite.world.nwf.FsckIssue
import gg.netherite.world.nwf.FsckIssueCode
import gg.netherite.world.nwf.FsckReport
import gg.netherite.world.nwf.FsckSeverity
import gg.netherite.world.nwf.HeightmapPolicy
import gg.netherite.world.nwf.IndexEntry
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.NwfFormatException
import gg.netherite.world.nwf.RebuildSegment
import gg.netherite.world.nwf.RootDraft
import gg.netherite.world.nwf.SegmentFetcher
import gg.netherite.world.nwf.SegmentRef
import gg.netherite.world.nwf.convert.BuiltWorld
import gg.netherite.world.nwf.convert.WorldBuildOptions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NwfV1WorldBuilderAndFsckTest {
    private val pipeline = ZstdFramePipeline()
    private val rootCodec = NwfV1RootCodec()
    private val factory = NwfV1WorldBuilderFactory(pipeline, rootCodec)
    private val fsck = NwfV1Fsck(pipeline, rootCodec)
    private val nbt = sampleNbt()

    private fun buildOptions(inlineBudget: Long, segmentTarget: Long = 64 * 1024, dicts: Boolean = false) = WorldBuildOptions(
        contentClass = "example",
        segmentPrefix = "example-prefix",
        saveLevel = 3,
        lightPolicy = LightPolicy.STRIP,
        inlineBudgetBytes = inlineBudget,
        segmentTargetBytes = segmentTarget,
        dictionaries = if (dicts) mapOf(ChunkKind.CHUNK to TestDictionaries.chunk) else emptyMap(),
    )

    private fun build(options: WorldBuildOptions, content: Map<ChunkKey, ByteArray> = nbt): BuiltWorld {
        val builder = factory.create(options)
        builder.header(header())
        content.forEach { (key, bytes) -> builder.chunk(key, ByteBuffer.wrap(bytes)) }
        return builder.finish()
    }

    private fun expected(key: ChunkKey, bytes: ByteArray) =
        LightStripNbtFilter.scan(key.kind, bytes, LightPolicy.STRIP, HeightmapPolicy.KEEP).filtered.nbtView().toBytes()

    private fun fetcher(segments: List<ByteArray?>) = SegmentFetcher { i, _ ->
        segments.getOrNull(i)?.let {
            ByteBuffer.wrap(it)
        }
    }

    /** Reads every live chunk of a built world back through root, segments and pipeline. */
    private fun readBack(world: BuiltWorld): Map<ChunkKey, ByteArray> {
        val view = rootCodec.parse(ByteBuffer.wrap(rootCodec.write(world.root)))
        val segments = world.segments.map { NwfV1SegmentCodec.parse(ByteBuffer.wrap(it.bytes()), it.sha256) }
        return view.entries().filter { !it.isTombstone }.associate { e ->
            // payload(entry) checks the frame header against the entry on every read.
            val payload = if (e.isInline) view.inlinePayload(e) else segments[e.location].payload(e)
            val frame = EncodedFrame(NwfFrameHeaders.of(e), e.dataVersion, payload)
            e.key to pipeline.decode(frame, lookupOf(TestDictionaries.chunk), null).toBytes()
        }
    }

    @Test
    fun `small worlds are one inline root that reads back exactly`() {
        val world = build(buildOptions(inlineBudget = 16L * 1024 * 1024, dicts = true))
        assertTrue(world.segments.isEmpty())
        assertTrue(world.root.entries.all { it.isInline })
        assertEquals("example", world.root.header.contentClass)
        assertEquals(LightPolicy.STRIP, world.root.header.lightPolicy)
        assertEquals("example-prefix", world.root.header.segmentPrefix)
        assertEquals(1L, world.root.generation)
        assertEquals(1, world.root.dictionaries.size)
        assertTrue(world.root.entries.filter { it.kind == ChunkKind.CHUNK }.all { it.codec == FrameCodec.ZSTD_DICT })
        val back = readBack(world)
        assertEquals(nbt.keys, back.keys)
        nbt.forEach { (key, bytes) -> assertArrayEquals(expected(key, bytes), back.getValue(key), "$key") }
    }

    @Test
    fun `worlds over the budget go to sorted segments near the target size`() {
        val world = build(buildOptions(inlineBudget = 0, segmentTarget = 32 * 1024))
        assertTrue(world.segments.size >= 2, "${world.segments.size} segments")
        assertEquals(0, world.root.inline.remaining())
        assertTrue(world.root.entries.none { it.isInline })
        assertEquals(world.segments.map { it.sha256 }, world.root.segments.map { it.sha256 })
        val back = readBack(world)
        nbt.forEach { (key, bytes) -> assertArrayEquals(expected(key, bytes), back.getValue(key), "$key") }
    }

    @Test
    fun `a base adds HAS_BASE and tombstones for base keys the source lacks`() {
        val baseWorld = build(buildOptions(inlineBudget = 16L * 1024 * 1024))
        val base = rootCodec.parse(ByteBuffer.wrap(rootCodec.write(baseWorld.root.copy(flags = NwfFormat.ROOT_FLAG_TEMPLATE))))
        val kept = nbt.entries.take(3).associate { it.key to it.value }
        val world = build(buildOptions(inlineBudget = 16L * 1024 * 1024).copy(base = base), kept)
        assertEquals(NwfFormat.ROOT_FLAG_HAS_BASE, world.root.flags)
        assertArrayEquals(base.rootSha.bytes(), world.root.header.baseRootSha)
        val tombstones = world.root.entries.filter { it.isTombstone }.map { it.key }.toSet()
        assertEquals(nbt.keys - kept.keys, tombstones)
        assertEquals(kept.keys, readBack(world).keys)
    }

    @Test
    fun `the builder fails cleanly past its held-bytes cap and is spent`() {
        val builder = factory.create(buildOptions(16L * 1024 * 1024).copy(maxHeldBytes = 4096))
        builder.header(header())
        val error = assertThrows<gg.netherite.world.nwf.NwfFormatException> {
            nbt.forEach { (key, bytes) -> builder.chunk(key, ByteBuffer.wrap(bytes)) }
        }
        assertEquals(NwfFormatError.LIMIT_EXCEEDED, error.reason)
        assertThrows<IllegalStateException> { builder.finish() }
        assertThrows<IllegalArgumentException> { factory.create(buildOptions(1024).copy(maxHeldBytes = 0)) }
    }

    @Test
    fun `builder misuse is refused`() {
        val builder = factory.create(buildOptions(1024))
        assertThrows<IllegalStateException> { builder.chunk(nbt.keys.first(), ByteBuffer.wrap(nbt.values.first())) }
        builder.header(header())
        assertThrows<IllegalStateException> { builder.header(header()) }
        val (key, bytes) = nbt.entries.first()
        builder.chunk(key, ByteBuffer.wrap(bytes))
        assertThrows<IllegalArgumentException> { builder.chunk(key, ByteBuffer.wrap(bytes)) }
        assertThrows<IllegalArgumentException> { builder.chunk(ChunkKey(5, ChunkKind.CHUNK, 0, 0), ByteBuffer.wrap(bytes)) }
        builder.finish()
        assertThrows<IllegalStateException> { builder.finish() }
    }

    // ---- fsck ---------------------------------------------------------------------------------------------------

    private fun segmented(): BuiltWorld = build(buildOptions(inlineBudget = 0, segmentTarget = 32 * 1024, dicts = true))

    private fun codesOf(root: ByteArray, segments: List<ByteArray?>, dicts: gg.netherite.world.nwf.DictionaryLookup = lookupOf(TestDictionaries.chunk)) =
        fsck.check(ByteBuffer.wrap(root), fetcher(segments), dicts).issues.map { it.code }.toSet()

    /** Replaces segment [i] with [bytes] and re-points the root at it (new SHA-256), optionally editing entries. */
    private fun withSegment(
        world: BuiltWorld,
        i: Int,
        bytes: ByteArray,
        entries: (List<IndexEntry>) -> List<IndexEntry> = { it },
    ): Pair<ByteArray, List<ByteArray>> {
        val segments = world.segments.map { it.bytes() }.toMutableList()
        segments[i] = bytes
        val refs = world.root.segments.toMutableList()
        refs[i] = SegmentRef(sha256(bytes), 0, bytes.size.toLong(), refs[i].frameCount)
        val root = rootCodec.write(world.root.copy(segments = refs, entries = entries(world.root.entries)))
        return root to segments
    }

    private fun firstEntryIn(world: BuiltWorld, segment: Int) = world.root.entries.first {
        !it.isInline && it.location == segment
    }

    @Test
    fun `a clean world passes and every frame decodes`() {
        val world = segmented()
        val report = fsck.check(
            ByteBuffer.wrap(rootCodec.write(world.root)),
            fetcher(
                world.segments.map {
                    it.bytes()
                }
            ),
            lookupOf(TestDictionaries.chunk)
        )
        assertTrue(report.clean, report.issues.toString())
        assertEquals(world.root.entries.size, report.entriesChecked)
        assertEquals(nbt.size, report.framesDecoded)

        val inline = build(buildOptions(inlineBudget = 16L * 1024 * 1024))
        val inlineReport = fsck.check(ByteBuffer.wrap(rootCodec.write(inline.root)), fetcher(emptyList()), NO_DICTS)
        assertTrue(inlineReport.clean, inlineReport.issues.toString())
        assertEquals(nbt.size, inlineReport.framesDecoded)
    }

    @Test
    fun `fsck finds each corruption class`() {
        val world = segmented()
        val root = rootCodec.write(world.root)
        val segments = world.segments.map { it.bytes() }

        assertEquals(setOf(FsckIssueCode.ROOT_INVALID), codesOf(root.flipBit(100), segments))
        assertEquals(setOf(FsckIssueCode.SEGMENT_MISSING), codesOf(root, listOf(null) + segments.drop(1)))
        assertEquals(setOf(FsckIssueCode.SEGMENT_SHA_MISMATCH), codesOf(root, listOf(segments[0].flipBit(400)) + segments.drop(1)))
        assertEquals(setOf(FsckIssueCode.DICTIONARY_MISSING), codesOf(root, segments, NO_DICTS))

        // Payload damage under a re-sealed segment SHA: only the frame CRC can see it.
        val target = firstEntryIn(world, 0)
        val payloadAt = target.offset + NwfFormat.FRAME_HEADER_BYTES + 1
        val (crcRoot, crcSegs) = withSegment(world, 0, segments[0].flipBit(payloadAt * 8))
        assertEquals(setOf(FsckIssueCode.FRAME_CRC_MISMATCH), codesOf(crcRoot, crcSegs))

        // Frame header x changed: header and index disagree.
        val moved = segments[0].copyOf()
        ByteBuffer.wrap(moved).order(ByteOrder.LITTLE_ENDIAN).putInt(target.offset + 16, target.x + 1)
        val (movedRoot, movedSegs) = withSegment(world, 0, moved)
        assertEquals(setOf(FsckIssueCode.FRAME_HEADER_DISAGREES_WITH_INDEX), codesOf(movedRoot, movedSegs))

        // Garbage payload with matching CRCs everywhere: decoding is the only check left.
        val garbage = segments[0].copyOf()
        for (i in 0 until target.storedLength) garbage[target.offset + NwfFormat.FRAME_HEADER_BYTES + i] = (i * 7).toByte()
        val newCrc =
            crc(garbage.copyOfRange(target.offset + NwfFormat.FRAME_HEADER_BYTES, target.offset + NwfFormat.FRAME_HEADER_BYTES + target.storedLength))
        ByteBuffer.wrap(garbage).order(ByteOrder.LITTLE_ENDIAN).putInt(target.offset + 12, newCrc)
        val (badRoot, badSegs) = withSegment(world, 0, garbage) { entries ->
            entries.map { if (it.key == target.key) it.copy(crc32c = newCrc) else it }
        }
        assertEquals(setOf(FsckIssueCode.FRAME_UNDECODABLE), codesOf(badRoot, badSegs))

        // Index DataVersion disagreeing with the frame's NBT.
        val dvRoot = rootCodec.write(
            world.root.copy(
                entries = world.root.entries.map {
                    if (it.key == target.key) it.copy(dataVersion = 1) else it
                }
            )
        )
        assertEquals(setOf(FsckIssueCode.FRAME_UNDECODABLE), codesOf(dvRoot, segments))

        // A frame in a segment that no entry references.
        val orphanRoot = rootCodec.write(world.root.copy(entries = world.root.entries.filter { it.key != target.key }))
        val orphanReport = fsck.check(ByteBuffer.wrap(orphanRoot), fetcher(segments), lookupOf(TestDictionaries.chunk))
        assertEquals(listOf(FsckIssueCode.ORPHAN_FRAME), orphanReport.issues.map { it.code })
        assertEquals(target.key, orphanReport.issues.single().key)
        assertTrue(orphanReport.clean, "ORPHAN_FRAME is informational")
    }

    @Test
    fun `base delta frames are checked against the base when one is given`() {
        val key = ChunkKey(0, ChunkKind.CHUNK, 2, 2)
        val baseNbt = expected(key, nbtBytes(TestChunks.chunk(2, 2, PaletteStyle.FULL, 5, lastUpdate = 1)))
        val frame = pipeline.encode(
            key,
            ByteBuffer.wrap(nbtBytes(TestChunks.chunk(2, 2, PaletteStyle.FULL, 5, lastUpdate = 2))),
            options().copy(baseNbt = ByteBuffer.wrap(baseNbt)),
        )
        val draft = RootDraft(
            flags = NwfFormat.ROOT_FLAG_HAS_BASE,
            generation = 1,
            header = gg.netherite.world.nwf.WorldHeader(
                dimensions = listOf("minecraft:overworld"),
                baseRootSha = ByteArray(32) {
                    9
                }
            ),
            dictionaries = emptyList(),
            segments = emptyList(),
            entries = listOf(entryOf(frame, NwfFormat.NONE_U16, 0)),
            inline = frame.payloadView(),
        )
        val root = ByteBuffer.wrap(rootCodec.write(draft))
        val noBase = fsck.check(root, fetcher(emptyList()), NO_DICTS)
        assertTrue(noBase.clean)
        assertEquals(0, noBase.framesDecoded)
        val withBase = fsck.check(root, fetcher(emptyList()), NO_DICTS) { ByteBuffer.wrap(baseNbt) }
        assertTrue(withBase.clean, withBase.issues.toString())
        assertEquals(1, withBase.framesDecoded)
        val missing = fsck.check(root, fetcher(emptyList()), NO_DICTS) { null }
        assertEquals(listOf(FsckIssueCode.BASE_MISSING), missing.issues.map { it.code })
        assertTrue(missing.corrupt, "an absent base frame is a dangling reference")
        assertEquals(EntryFlags.BASE_DELTA or EntryFlags.LIGHT_STRIPPED, frame.header.flags)

        // A base that could not be read leaves the frame unchecked: incomplete, not corrupt.
        val unreadable = fsck.check(root, fetcher(emptyList()), NO_DICTS) { throw java.io.IOException("timed out") }
        assertEquals(listOf(FsckIssueCode.BASE_UNREADABLE), unreadable.issues.map { it.code })
        assertTrue(!unreadable.corrupt)
        assertTrue(!unreadable.complete)
        assertEquals(0, unreadable.framesDecoded)

        // A base that was read but is damaged is corruption.
        val damaged = fsck.check(root, fetcher(emptyList()), NO_DICTS) {
            throw NwfFormatException(NwfFormatError.CRC_MISMATCH, "base section CRC")
        }
        assertEquals(listOf(FsckIssueCode.BASE_INVALID), damaged.issues.map { it.code })
        assertTrue(damaged.corrupt)
        assertTrue(damaged.complete)
    }

    @Test
    fun `dictionaries are unavailable or damaged, never world corruption, one lookup per slot`() {
        val world = segmented()
        val root = ByteBuffer.wrap(rootCodec.write(world.root))
        val segments = world.segments.map { it.bytes() }
        fun check(dicts: DictionaryLookup) = fsck.check(root.duplicate(), fetcher(segments), dicts)

        val dictSha = world.root.dictionaries.single().sha256
        val missing = check(NO_DICTS)
        assertEquals(setOf(FsckIssueCode.DICTIONARY_MISSING), missing.issues.map { it.code }.toSet())
        assertTrue(!missing.corrupt, "a missing dictionary is not damage to the world")
        assertTrue(missing.unavailable)
        assertTrue(!missing.complete, "its frames are unchecked")
        assertEquals(setOf(dictSha), missing.dictionariesNotUsable, "the issue names the dictionary")

        var lookups = 0
        val unreadable = check {
            lookups++
            throw java.io.IOException("timed out")
        }
        assertEquals(setOf(FsckIssueCode.DICTIONARY_UNREADABLE), unreadable.issues.map { it.code }.toSet())
        assertTrue(!unreadable.corrupt)
        assertTrue(unreadable.unavailable)
        assertTrue(!unreadable.complete)
        assertEquals(1, lookups, "each slot is looked up once")

        val invalid = check { throw NwfFormatException(NwfFormatError.SHA_MISMATCH, "dictionary bytes differ") }
        assertEquals(setOf(FsckIssueCode.DICTIONARY_INVALID), invalid.issues.map { it.code }.toSet())
        assertTrue(!invalid.corrupt, "a damaged dictionary is the dictionary's damage, not the world's")
        assertTrue(invalid.dictionaryDamaged)
        assertTrue(!invalid.complete)
        assertEquals(setOf(dictSha), invalid.dictionariesNotUsable)

        // A lookup that hands back bytes that do not match the dictionary's SHA-256 is invalid too.
        val good = TestDictionaries.chunk
        val tampered = good.bytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        val wrong = check { gg.netherite.world.nwf.NwfDictionary(good.sha256, good.zstdDictId, tampered) }
        assertEquals(setOf(FsckIssueCode.DICTIONARY_INVALID), wrong.issues.map { it.code }.toSet())
        assertTrue(!wrong.corrupt)
        assertTrue(wrong.dictionaryDamaged)

        assertTrue(check(lookupOf(good)).clean)

        // Anything but a format or IO failure is a defect of the lookup, not a verdict: it propagates.
        assertThrows<IllegalStateException> { check { error("bug in the lookup") } }
        val flakySegments = SegmentFetcher { _, _ -> error("bug in the fetcher") }
        assertThrows<IllegalStateException> { fsck.check(root.duplicate(), flakySegments, lookupOf(good)) }
    }

    @Test
    fun `root issues outside the check follow the same absent versus unreadable rule`() {
        val unreadable = FsckReport.rootNotChecked(FsckIssueCode.ROOT_UNREADABLE, "GET timed out")
        assertTrue(!unreadable.corrupt)
        assertTrue(!unreadable.complete)
        val missing = FsckReport.rootNotChecked(FsckIssueCode.ROOT_MISSING, "no object")
        assertTrue(missing.corrupt)
        assertTrue(missing.complete)
        assertTrue(FsckReport.rootNotChecked(FsckIssueCode.ROOT_INVALID, "bad magic").corrupt)
        assertThrows<IllegalArgumentException> { FsckReport.rootNotChecked(FsckIssueCode.SEGMENT_MISSING, "-") }
    }

    private fun poiRecord(x: Int, z: Int) =
        compound("type" to TString("minecraft:home"), "pos" to TIntArray(intArrayOf(x, 64, z)), "free_tickets" to TInt(0))

    private fun poiChunk(vararg records: TCompound) = compound(
        "DataVersion" to TInt(TestChunks.DATA_VERSION),
        "Sections" to compound("4" to compound("Valid" to TByte(1), "Records" to compoundList(records.toList()))),
    )

    @Test
    fun `a POI record outside its frame's chunk is accepted on save and is a warning in fsck`() {
        val key = ChunkKey(0, ChunkKind.POI, 1, -1)
        fun checkOf(vararg records: TCompound): FsckReport {
            val world =
                build(buildOptions(inlineBudget = 16L * 1024 * 1024), mapOf(key to nbtBytes(poiChunk(*records))))
            return fsck.check(ByteBuffer.wrap(rootCodec.write(world.root)), fetcher(emptyList()), NO_DICTS)
        }

        val inChunk = checkOf(poiRecord(16, -16), poiRecord(31, -1))
        assertTrue(inChunk.clean, inChunk.issues.toString())

        for (report in listOf(checkOf(poiRecord(48, -16)), checkOf(poiRecord(16, -16), poiRecord(32, -16)))) {
            assertEquals(listOf(FsckIssueCode.POI_RECORD_OUTSIDE_CHUNK), report.issues.map { it.code }, report.toString())
            assertEquals(FsckSeverity.WARNING, report.issues.single().code.severity)
            assertEquals(key, report.issues.single().key)
            assertEquals(1, report.framesDecoded, "the frame still decodes")
            assertTrue(!report.corrupt)
            assertTrue(report.complete)
            assertTrue(!report.clean)
        }
    }

    private fun rebuildInput(bytes: ByteArray) = RebuildSegment(sha256(bytes), ByteBuffer.wrap(bytes))

    private fun rebuild(vararg segments: ByteArray, dicts: DictionaryLookup = lookupOf(TestDictionaries.chunk)) =
        fsck.rebuildIndex(segments.map { rebuildInput(it) }, dicts, worldHasBase = false)

    @Test
    fun `rebuildIndex recovers the index from verified segments alone, later segments winning`() {
        val world = segmented()
        val segments = world.segments.map { it.bytes() }
        assertEquals(world.root.entries, rebuild(*segments.toTypedArray()))

        // The same key in a later segment replaces the earlier one.
        val key = world.root.entries.first().key
        val newer = pipeline.encode(key, ByteBuffer.wrap(nbtBytes(TestChunks.chunk(key.x, key.z, PaletteStyle.EMPTY, 999))), options())
        val extra = NwfV1SegmentCodec.write(listOf(newer)).bytes()
        val withExtra = rebuild(*(segments + listOf(extra)).toTypedArray())
        val replaced = withExtra.single { it.key == key }
        assertEquals(segments.size, replaced.location)
        assertEquals(newer.header.crc32c, replaced.crc32c)
        assertEquals(world.root.entries.size, withExtra.size)

        assertFormatError(NwfFormatError.DANGLING_REFERENCE) { rebuild(*segments.toTypedArray(), dicts = NO_DICTS) }
    }

    @Test
    fun `rebuildIndex verifies each segment against its SHA-256`() {
        val segment = segmented().segments.first()
        val bytes = segment.bytes()
        val flipped = bytes.copyOf()
        val offset = segment.frameOffsets.values.min()
        ByteBuffer.wrap(flipped).order(ByteOrder.LITTLE_ENDIAN).putInt(offset + 16, 12345) // frame header x
        assertFormatError(NwfFormatError.SHA_MISMATCH) {
            fsck.rebuildIndex(listOf(RebuildSegment(segment.sha256, ByteBuffer.wrap(flipped))), NO_DICTS, worldHasBase = false)
        }
    }

    /** One frame alone in a segment, its header x moved by one and the SHA re-sealed: only the NBT can tell. */
    private fun rekeyed(key: ChunkKey, nbtBytes: ByteArray): ByteArray {
        val frame = pipeline.encode(key, ByteBuffer.wrap(nbtBytes), options())
        val bytes = NwfV1SegmentCodec.write(listOf(frame)).bytes()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(NwfFormat.SEGMENT_HEADER_BYTES + 16, key.x + 1)
        return bytes
    }

    @Test
    fun `rebuildIndex refuses a CHUNK or ENTITIES frame whose NBT coordinates disagree with its header`() {
        for (kind in listOf(ChunkKind.CHUNK, ChunkKind.ENTITIES)) {
            val (key, bytes) = nbt.entries.first { it.key.kind == kind }
            val frame = pipeline.encode(key, ByteBuffer.wrap(bytes), options())
            assertEquals(listOf(key), rebuild(NwfV1SegmentCodec.write(listOf(frame)).bytes(), dicts = NO_DICTS).map { it.key })
            assertFormatError(NwfFormatError.BAD_PAYLOAD) { rebuild(rekeyed(key, bytes), dicts = NO_DICTS) }
        }
    }

    @Test
    fun `rebuildIndex refuses chunks without coordinates and keeps POI frames whatever their record positions`() {
        val bare = ChunkKey(0, ChunkKind.CHUNK, 3, 3)
        val bareFrame = pipeline.encode(bare, ByteBuffer.wrap(nbtBytes(compound("DataVersion" to TInt(1)))), options())
        assertFormatError(NwfFormatError.BAD_PAYLOAD) {
            rebuild(NwfV1SegmentCodec.write(listOf(bareFrame)).bytes(), dicts = NO_DICTS)
        }

        val key = ChunkKey(0, ChunkKind.POI, 1, -1)
        val inChunk = pipeline.encode(key, ByteBuffer.wrap(nbtBytes(poiChunk(poiRecord(16, -16), poiRecord(31, -1)))), options())
        assertEquals(1, rebuild(NwfV1SegmentCodec.write(listOf(inChunk)).bytes(), dicts = NO_DICTS).size)
        // Writers accept POI records anywhere, so record positions prove no key: the rebuild keeps such a frame.
        val spanning = pipeline.encode(key, ByteBuffer.wrap(nbtBytes(poiChunk(poiRecord(16, -16), poiRecord(32, -16)))), options())
        assertEquals(listOf(key), rebuild(NwfV1SegmentCodec.write(listOf(spanning)).bytes(), dicts = NO_DICTS).map { it.key })
        // A POI chunk without records carries no coordinates; the verified SHA-256 is its only key proof.
        val empty = pipeline.encode(key, ByteBuffer.wrap(nbtBytes(poiChunk())), options())
        assertEquals(1, rebuild(NwfV1SegmentCodec.write(listOf(empty)).bytes(), dicts = NO_DICTS).size)
    }

    @Test
    fun `rebuildIndex refuses worlds with a base and base-delta frames`() {
        val segments = segmented().segments.map { rebuildInput(it.bytes()) }
        assertThrows<IllegalArgumentException> {
            fsck.rebuildIndex(segments, lookupOf(TestDictionaries.chunk), worldHasBase = true)
        }

        val key = ChunkKey(0, ChunkKind.CHUNK, 2, 2)
        val base = LightStripNbtFilter.scan(key.kind, nbtBytes(TestChunks.chunk(2, 2, PaletteStyle.FULL, 5, lastUpdate = 1)), LightPolicy.STRIP, HeightmapPolicy.KEEP)
            .filtered.nbtView().toBytes()
        val delta = pipeline.encode(key, ByteBuffer.wrap(nbtBytes(TestChunks.chunk(2, 2, PaletteStyle.FULL, 5, lastUpdate = 2))), options().copy(baseNbt = ByteBuffer.wrap(base)))
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) {
            rebuild(NwfV1SegmentCodec.write(listOf(delta)).bytes(), dicts = NO_DICTS)
        }
    }

    @Test
    fun `an orphan-only report is clean and a failed fetch is incomplete, not corrupt`() {
        val orphanOnly = FsckReport(1, 1, listOf(FsckIssue(FsckIssueCode.ORPHAN_FRAME, null, "superseded")))
        assertTrue(orphanOnly.clean)
        assertTrue(!orphanOnly.corrupt)
        assertTrue(orphanOnly.complete)

        val world = segmented()
        val root = ByteBuffer.wrap(rootCodec.write(world.root))
        val segments = world.segments.map { it.bytes() }
        val flaky =
            SegmentFetcher { i, _ -> if (i == 0) throw java.io.IOException("timed out") else ByteBuffer.wrap(segments[i]) }
        val incomplete = fsck.check(root, flaky, lookupOf(TestDictionaries.chunk))
        assertEquals(listOf(FsckIssueCode.SEGMENT_UNREADABLE), incomplete.issues.map { it.code })
        assertTrue(!incomplete.complete)
        assertTrue(!incomplete.corrupt)
        assertTrue(!incomplete.clean)

        val gone = SegmentFetcher { i, _ -> if (i == 0) null else ByteBuffer.wrap(segments[i]) }
        val missing = fsck.check(root, gone, lookupOf(TestDictionaries.chunk))
        assertEquals(listOf(FsckIssueCode.SEGMENT_MISSING), missing.issues.map { it.code })
        assertTrue(missing.corrupt)
        assertTrue(!missing.clean)
    }
}
