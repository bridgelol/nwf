package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.DictionaryLookup
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.FrameCodec
import gg.netherite.world.nwf.FramePipeline
import gg.netherite.world.nwf.FsckIssue
import gg.netherite.world.nwf.FsckIssueCode
import gg.netherite.world.nwf.FsckReport
import gg.netherite.world.nwf.HeightmapPolicy
import gg.netherite.world.nwf.IndexEntry
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.NwfDictionary
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.NwfFormatException
import gg.netherite.world.nwf.NwfFsck
import gg.netherite.world.nwf.RebuildSegment
import gg.netherite.world.nwf.SegmentFetcher
import gg.netherite.world.nwf.SegmentRef
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The base snapshot's decoded NBT for a key, for checking ZSTD_BASE_DELTA frames, under the lookup contract of
 * [SegmentFetcher]: returns null only when the base definitely has no live frame for the key
 * ([FsckIssueCode.BASE_MISSING]); throws [NwfFormatException] when the frame, or what it is read from, is damaged
 * ([FsckIssueCode.BASE_INVALID]); throws [java.io.IOException] when it could not be read
 * ([FsckIssueCode.BASE_UNREADABLE]: the frame is unchecked, not corrupt). Any other exception is a defect and
 * propagates out of the check. Whether the base root itself exists is the caller's to report. May do IO.
 */
fun interface BaseFrameLookup {
    fun baseNbt(key: ChunkKey): ByteBuffer?
}

/**
 * Offline check and index rebuild. Never writes anything and never throws for bad data: every problem
 * becomes an [FsckIssue]. A lookup exception other than [NwfFormatException] or [IOException] is a defect, not a
 * verdict, and propagates.
 *
 * A data frame counts as decoded when its CRC32C matches, it decompresses to exactly its raw length, its NBT parses,
 * its own coordinates (CHUNK `xPos`/`zPos`, ENTITIES `Position`) match its key and its `DataVersion` matches the index.
 * A CHUNK or ENTITIES coordinate mismatch is corruption ([FsckIssueCode.FRAME_UNDECODABLE]). POI record positions are
 * checked too, but a record outside the frame's chunk is only [FsckIssueCode.POI_RECORD_OUTSIDE_CHUNK], a warning: the
 * server never validates them and writers accept them, so the frame still counts as decoded.
 * ZSTD_BASE_DELTA frames need the base: without a [BaseFrameLookup] they are CRC-checked only and not counted as
 * decoded; with one, a missing base frame is [FsckIssueCode.BASE_MISSING], a damaged one [FsckIssueCode.BASE_INVALID]
 * and an unreadable one [FsckIssueCode.BASE_UNREADABLE]. Dictionaries follow the same rule through [DictionaryLookup]:
 * [FsckIssueCode.DICTIONARY_MISSING], [FsckIssueCode.DICTIONARY_INVALID] (including bytes that differ from the
 * dictionary's SHA-256) or [FsckIssueCode.DICTIONARY_UNREADABLE]; each slot is looked up once. Frames present in a
 * segment but referenced by no INDEX entry are [FsckIssueCode.ORPHAN_FRAME]; a segment written by an earlier commit
 * legitimately keeps frames that later commits superseded, so that code is informational and [FsckReport.clean]
 * ignores it. A segment fetch that throws is [FsckIssueCode.SEGMENT_UNREADABLE] (the report is incomplete, not
 * corrupt); a fetch that returns null, the store's definite "does not exist", is [FsckIssueCode.SEGMENT_MISSING].
 */
class NwfV1Fsck(
    private val pipeline: FramePipeline = ZstdFramePipeline(),
    private val rootCodec: NwfV1RootCodec = NwfV1RootCodec(),
) : NwfFsck {

    override fun check(root: ByteBuffer, segments: SegmentFetcher, dictionaries: DictionaryLookup): FsckReport =
        check(root, segments, dictionaries, null)

    /** As [check], decoding ZSTD_BASE_DELTA frames against [base]. */
    fun check(
        root: ByteBuffer,
        segments: SegmentFetcher,
        dictionaries: DictionaryLookup,
        base: BaseFrameLookup?,
    ): FsckReport {
        val view = try {
            rootCodec.parse(root)
        } catch (e: NwfFormatException) {
            return FsckReport.rootNotChecked(FsckIssueCode.ROOT_INVALID, e.message.orEmpty())
        }
        val run = CheckRun(dictionaries, base)
        val bySegment = HashMap<Int, MutableList<IndexEntry>>()
        for (i in 0 until view.entryCount) {
            val entry = try {
                view.entryAt(i)
            } catch (e: NwfFormatException) {
                run.issue(FsckIssueCode.FRAME_UNDECODABLE, null, "INDEX entry $i: ${e.message}")
                continue
            }
            when {
                entry.isTombstone -> Unit
                entry.isInline -> run.verify(entry, view.inlinePayload(entry))
                else -> bySegment.getOrPut(entry.location) { ArrayList() } += entry
            }
        }
        for ((i, ref) in view.segments.withIndex()) {
            run.checkSegment(i, ref, segments, bySegment[i].orEmpty())
        }
        return FsckReport(view.entryCount, run.decoded, run.issues.toList())
    }

    override fun rebuildIndex(
        segments: List<RebuildSegment>,
        dictionaries: DictionaryLookup,
        worldHasBase: Boolean,
    ): List<IndexEntry> {
        require(!worldHasBase) {
            "cannot rebuild the index of a world with a base: its deletions are INDEX tombstones no segment holds, " +
                "so a rebuild would resurrect deleted base chunks"
        }
        require(segments.size <= NwfFormat.NONE_U16) { "${segments.size} segments, at most ${NwfFormat.NONE_U16}" }
        val latest = HashMap<ChunkKey, IndexEntry>()
        for ((i, input) in segments.withIndex()) {
            // The SHA-256 covers every frame header, whose key no CRC covers: a flipped bit cannot re-key a chunk.
            val segment = NwfV1SegmentCodec.parse(input.bytes(), input.sha256)
            for (ref in segment.frames()) {
                val h = ref.header
                if (h.codec == FrameCodec.ZSTD_BASE_DELTA) {
                    nwfError(NwfFormatError.DANGLING_REFERENCE, "segment $i frame ${h.key}: base-delta frame, no base")
                }
                val decoded = pipeline.decode(EncodedFrame(h, 0, segment.payload(ref)), dictionaries, null)
                val nbt = decoded.copyRemaining()
                val scan = LightStripNbtFilter.scan(h.kind, nbt, LightPolicy.KEEP, HeightmapPolicy.KEEP)
                coordinateMismatch(h.key, scan, required = true)?.let {
                    nwfError(NwfFormatError.BAD_PAYLOAD, "segment $i frame at ${ref.headerOffset}: $it")
                }
                latest[h.key] = IndexEntry(
                    x = h.x,
                    z = h.z,
                    dim = h.dim,
                    kind = h.kind,
                    codec = h.codec,
                    flags = h.flags,
                    location = i,
                    dictSlot = h.dictSlot,
                    offset = ref.headerOffset,
                    storedLength = h.storedLength,
                    rawLength = h.rawLength,
                    crc32c = h.crc32c,
                    dataVersion = scan.filtered.dataVersion,
                )
            }
        }
        return latest.values.sortedBy { it.key }
    }

    private inner class CheckRun(private val dictionaries: DictionaryLookup, private val base: BaseFrameLookup?) {
        val issues = ArrayList<FsckIssue>()
        var decoded = 0

        /** Each dictionary slot's lookup outcome, resolved once per run. */
        private val slots = HashMap<Int, DictOutcome>()

        /** Only the dictionaries that resolved and verified: what decoding may use. */
        private val verifiedDictionaries = DictionaryLookup { slot -> (slots[slot] as? DictOutcome.Found)?.dictionary }

        fun issue(code: FsckIssueCode, key: ChunkKey?, detail: String) {
            issues += FsckIssue(code, key, detail)
        }

        fun checkSegment(index: Int, ref: SegmentRef, fetcher: SegmentFetcher, entries: List<IndexEntry>) {
            val bytes = try {
                fetcher.fetch(index, ref)
            } catch (e: IOException) {
                // Not evidence of damage: the frames are unchecked, so the report is incomplete, not corrupt.
                issue(FsckIssueCode.SEGMENT_UNREADABLE, null, "segment $index (${ref.sha256}): fetch failed: $e")
                return
            } ?: run {
                issue(FsckIssueCode.SEGMENT_MISSING, null, "segment $index (${ref.sha256}) does not exist")
                return
            }
            val segment = try {
                NwfV1SegmentCodec.parse(bytes, ref.sha256)
            } catch (e: NwfFormatException) {
                val code = if (e.reason == NwfFormatError.SHA_MISMATCH) {
                    FsckIssueCode.SEGMENT_SHA_MISMATCH
                } else {
                    FsckIssueCode.FRAME_UNDECODABLE
                }
                issue(code, null, "segment $index (${ref.sha256}): ${e.message}")
                return
            }
            if (segment.frameCount != ref.frameCount) {
                issue(
                    FsckIssueCode.FRAME_HEADER_DISAGREES_WITH_INDEX,
                    null,
                    "segment $index holds ${segment.frameCount} frames, SEGMENTS says ${ref.frameCount}",
                )
            }
            val offsets = segment.headerOffsets().toHashSet()
            val referenced = HashSet<Int>()
            for (entry in entries) {
                if (entry.offset !in offsets) {
                    issue(FsckIssueCode.FRAME_HEADER_DISAGREES_WITH_INDEX, entry.key, "no frame at ${entry.offset}")
                    continue
                }
                referenced += entry.offset
                checkSegmentFrame(segment, index, entry)
            }
            for (offset in segment.headerOffsets()) {
                if (offset in referenced) continue
                val key = runCatching { segment.headerAt(offset).key }.getOrNull()
                issue(FsckIssueCode.ORPHAN_FRAME, key, "segment $index frame at $offset is in no INDEX entry")
            }
        }

        private fun checkSegmentFrame(segment: NwfV1SegmentView, index: Int, entry: IndexEntry) {
            val header = try {
                segment.headerAt(entry.offset)
            } catch (e: NwfFormatException) {
                issue(FsckIssueCode.FRAME_UNDECODABLE, entry.key, "segment $index frame header: ${e.message}")
                return
            }
            val differences = NwfFrameHeaders.disagreements(header, entry)
            if (differences.isNotEmpty()) {
                issue(FsckIssueCode.FRAME_HEADER_DISAGREES_WITH_INDEX, entry.key, differences.joinToString("; "))
                return
            }
            verify(entry, segment.payload(entry))
        }

        fun verify(entry: IndexEntry, payload: ByteBuffer) {
            if (crc32c(payload) != entry.crc32c) {
                issue(FsckIssueCode.FRAME_CRC_MISMATCH, entry.key, "payload CRC32C differs from the index")
                return
            }
            if (entry.codec == FrameCodec.ZSTD_DICT) {
                when (val outcome = slots.getOrPut(entry.dictSlot) { resolve(entry.dictSlot) }) {
                    is DictOutcome.Found -> {
                        Unit
                    }
                    is DictOutcome.NotUsable -> {
                        issue(outcome.code, entry.key, "dictionary slot ${entry.dictSlot}: ${outcome.detail}")
                        return
                    }
                }
            }
            var baseNbt: ByteBuffer? = null
            if (entry.codec == FrameCodec.ZSTD_BASE_DELTA) {
                val lookup = base ?: return
                baseNbt = baseFrame(lookup, entry) ?: return
            }
            try {
                val frame = EncodedFrame(NwfFrameHeaders.of(entry), entry.dataVersion, payload)
                val nbt = pipeline.decode(frame, verifiedDictionaries, baseNbt).copyRemaining()
                checkNbt(entry, nbt)
                decoded++
            } catch (e: NwfFormatException) {
                issue(FsckIssueCode.FRAME_UNDECODABLE, entry.key, e.message.orEmpty())
            }
        }

        private fun resolve(slot: Int): DictOutcome {
            val dictionary = try {
                dictionaries.bySlot(slot)
            } catch (e: NwfFormatException) {
                return DictOutcome.NotUsable(FsckIssueCode.DICTIONARY_INVALID, e.message.orEmpty())
            } catch (e: IOException) {
                // Not evidence of damage: the frames are unchecked, so the report is incomplete, not corrupt.
                return DictOutcome.NotUsable(FsckIssueCode.DICTIONARY_UNREADABLE, "read failed: $e")
            } ?: return DictOutcome.NotUsable(FsckIssueCode.DICTIONARY_MISSING, "does not exist")
            if (!dictionary.verified()) {
                return DictOutcome.NotUsable(
                    FsckIssueCode.DICTIONARY_INVALID,
                    "bytes differ from ${dictionary.sha256}",
                )
            }
            return DictOutcome.Found(dictionary)
        }

        /** The base frame for a base-delta [entry], or null after reporting why it is not there. */
        private fun baseFrame(lookup: BaseFrameLookup, entry: IndexEntry): ByteBuffer? {
            val found = try {
                lookup.baseNbt(entry.key)
            } catch (e: NwfFormatException) {
                issue(FsckIssueCode.BASE_INVALID, entry.key, "base frame: ${e.message}")
                return null
            } catch (e: IOException) {
                // Not evidence of damage: the frame is unchecked, so the report is incomplete, not corrupt.
                issue(FsckIssueCode.BASE_UNREADABLE, entry.key, "base frame: read failed: $e")
                return null
            }
            if (found == null) issue(FsckIssueCode.BASE_MISSING, entry.key, "base snapshot has no frame for the key")
            return found
        }

        private fun checkNbt(entry: IndexEntry, nbt: ByteArray) {
            val scan = LightStripNbtFilter.scan(entry.kind, nbt, LightPolicy.KEEP, HeightmapPolicy.KEEP)
            coordinateMismatch(entry.key, scan, required = false)?.let { nwfError(NwfFormatError.BAD_PAYLOAD, it) }
            if (scan.filtered.dataVersion != entry.dataVersion) {
                nwfError(
                    NwfFormatError.BAD_PAYLOAD,
                    "NBT DataVersion ${scan.filtered.dataVersion}, index says ${entry.dataVersion}",
                )
            }
            poiOutsideChunk(entry.key, scan)?.let { issue(FsckIssueCode.POI_RECORD_OUTSIDE_CHUNK, entry.key, it) }
        }
    }

    private sealed interface DictOutcome {
        class Found(val dictionary: NwfDictionary) : DictOutcome

        class NotUsable(val code: FsckIssueCode, val detail: String) : DictOutcome
    }

    private companion object {
        /**
         * Why a CHUNK or ENTITIES frame's NBT does not belong at [key], or null when it does: CHUNK `xPos`/`zPos` and
         * ENTITIES `Position` must equal the key. With [required], such a frame that carries no coordinates also fails
         * (an index rebuild must prove each key). POI frames always pass here: their record positions are a warning
         * of the check ([poiOutsideChunk]), and the segment SHA-256 already covers their header.
         */
        fun coordinateMismatch(key: ChunkKey, scan: NbtScan, required: Boolean): String? {
            if (key.kind == ChunkKind.POI) return null
            val carried = scan.x != null && scan.z != null
            return when {
                carried && (scan.x != key.x || scan.z != key.z) -> "NBT says chunk (${scan.x}, ${scan.z}), key $key"
                !carried && required -> "NBT carries no coordinates for $key"
                else -> null
            }
        }

        /** Why a POI frame's records do not all lie in [key]'s chunk, or null when they do (or it has none). */
        fun poiOutsideChunk(key: ChunkKey, scan: NbtScan): String? {
            if (key.kind != ChunkKind.POI) return null
            return when {
                scan.mixedPositions -> "POI records lie in more than one chunk"
                scan.x != null && (scan.x != key.x || scan.z != key.z) ->
                    "POI records lie in chunk (${scan.x}, ${scan.z})"
                else -> null
            }
        }
    }
}
