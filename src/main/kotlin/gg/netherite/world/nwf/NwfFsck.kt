package gg.netherite.world.nwf

import java.nio.ByteBuffer

/** How much an fsck issue weighs: whether it is damage, an unfinished check, or information only. */
enum class FsckSeverity {
    /** The stored bytes are damaged or inconsistent. */
    CORRUPTION,

    /**
     * A dictionary the world references was read, but its bytes fail their SHA-256 or do not load as a dictionary.
     * The shared dictionary object is damaged, not this world's bytes; the frames that need it are unchecked, and the
     * world cannot load until the dictionary is restored. [FsckIssue.dictionary] names it.
     */
    DICTIONARY_DAMAGED,

    /**
     * A dictionary the world references does not exist or could not be read: retryable. The world's own bytes are not
     * judged; the frames that need it are unchecked, and the world does not load until it is readable (a store refuses
     * the open the same way). [FsckIssue.dictionary] names it.
     */
    UNAVAILABLE,

    /** Part of the world could not be checked (for example a transient fetch error); retry before concluding. */
    INCOMPLETE,

    /** The world is intact and loads, but holds something unusual that is worth a look. */
    WARNING,

    /** Worth knowing, not a problem: the world is intact. */
    INFO,
}

/** What an fsck run found. */
enum class FsckIssueCode(val severity: FsckSeverity) {
    /** The root bytes are no valid root: bad magic, over the size limit, a failed CRC or a broken structure. */
    ROOT_INVALID(FsckSeverity.CORRUPTION),

    /**
     * The store answered definitely that the referenced root does not exist. Never produced by [NwfFsck.check], which
     * is handed the root bytes; for callers that fetch the root themselves.
     */
    ROOT_MISSING(FsckSeverity.CORRUPTION),

    /**
     * The root fetch failed (timeout, connection error, server error): nothing was checked. Never produced by
     * [NwfFsck.check]; for callers that fetch the root themselves.
     */
    ROOT_UNREADABLE(FsckSeverity.INCOMPLETE),

    /** The segment fetch answered definitely that the object does not exist. */
    SEGMENT_MISSING(FsckSeverity.CORRUPTION),

    /** The segment fetch failed (timeout, connection error, server error): its frames were not checked. */
    SEGMENT_UNREADABLE(FsckSeverity.INCOMPLETE),
    SEGMENT_SHA_MISMATCH(FsckSeverity.CORRUPTION),
    FRAME_CRC_MISMATCH(FsckSeverity.CORRUPTION),
    FRAME_UNDECODABLE(FsckSeverity.CORRUPTION),
    FRAME_HEADER_DISAGREES_WITH_INDEX(FsckSeverity.CORRUPTION),

    /** A referenced dictionary does not exist: unavailable (retry once it is restored), not damage to the world. */
    DICTIONARY_MISSING(FsckSeverity.UNAVAILABLE),

    /** A referenced dictionary exists but is damaged: its bytes differ from its SHA-256, or it does not load. */
    DICTIONARY_INVALID(FsckSeverity.DICTIONARY_DAMAGED),

    /** A referenced dictionary could not be read (timeout, connection or server error): its frames are unchecked. */
    DICTIONARY_UNREADABLE(FsckSeverity.UNAVAILABLE),

    /** The base snapshot, or its frame for a base-delta key, definitely does not exist: a dangling reference. */
    BASE_MISSING(FsckSeverity.CORRUPTION),

    /** The base snapshot, or its frame for a base-delta key, exists but is damaged or does not decode. */
    BASE_INVALID(FsckSeverity.CORRUPTION),

    /** The base snapshot or its frame could not be read (timeout, connection error, server error): frames unchecked. */
    BASE_UNREADABLE(FsckSeverity.INCOMPLETE),

    /**
     * A POI record's block position lies outside the chunk of its frame key. The frame decodes and loads: the server
     * never validates POI positions, so writers accept such records and a save never fails on them.
     */
    POI_RECORD_OUTSIDE_CHUNK(FsckSeverity.WARNING),

    /**
     * A segment frame no INDEX entry references. Segments are immutable and shared across commits, so frames a later
     * commit superseded stay in older segments until compaction: informational.
     */
    ORPHAN_FRAME(FsckSeverity.INFO),
}

/** One finding. [dictionary] is the SHA-256 of the dictionary a DICTIONARY_* issue is about, when the root names it. */
data class FsckIssue(val code: FsckIssueCode, val key: ChunkKey?, val detail: String, val dictionary: Sha256? = null)

data class FsckReport(val entriesChecked: Int, val framesDecoded: Int, val issues: List<FsckIssue>) {
    /** True when some issue is damage ([FsckSeverity.CORRUPTION]). */
    val corrupt: Boolean get() = issues.any { it.code.severity == FsckSeverity.CORRUPTION }

    /**
     * True when every part of the world was checked: no [FsckSeverity.INCOMPLETE], [FsckSeverity.UNAVAILABLE] or
     * [FsckSeverity.DICTIONARY_DAMAGED] issue (each leaves frames unchecked).
     */
    val complete: Boolean get() = issues.none { it.code.severity in UNCHECKED }

    /** True when a dictionary the world references is missing or unreadable ([FsckSeverity.UNAVAILABLE]). */
    val unavailable: Boolean get() = issues.any { it.code.severity == FsckSeverity.UNAVAILABLE }

    /** True when a dictionary the world references fails its checks ([FsckSeverity.DICTIONARY_DAMAGED]). */
    val dictionaryDamaged: Boolean get() = issues.any { it.code.severity == FsckSeverity.DICTIONARY_DAMAGED }

    /** The dictionaries named by [FsckSeverity.UNAVAILABLE] or [FsckSeverity.DICTIONARY_DAMAGED] issues. */
    val dictionariesNotUsable: Set<Sha256>
        get() = issues.filter { it.code.severity in DICTIONARY_SEVERITIES }.mapNotNull { it.dictionary }.toSet()

    /**
     * Checked completely and nothing to look at: every issue, if any, is [FsckSeverity.INFO] (such as ORPHAN_FRAME).
     * A [FsckSeverity.WARNING] makes a report not clean without making it [corrupt].
     */
    val clean: Boolean get() = issues.all { it.code.severity == FsckSeverity.INFO }

    companion object {
        private val DICTIONARY_SEVERITIES = setOf(FsckSeverity.UNAVAILABLE, FsckSeverity.DICTIONARY_DAMAGED)
        private val UNCHECKED = DICTIONARY_SEVERITIES + FsckSeverity.INCOMPLETE
        private val ROOT_CODES =
            setOf(FsckIssueCode.ROOT_MISSING, FsckIssueCode.ROOT_UNREADABLE, FsckIssueCode.ROOT_INVALID)

        /**
         * The report of a check that never reached the root's contents: [code] is [FsckIssueCode.ROOT_MISSING] (the
         * store says the root does not exist), [FsckIssueCode.ROOT_UNREADABLE] (the fetch failed) or
         * [FsckIssueCode.ROOT_INVALID] (the bytes are no root).
         */
        fun rootNotChecked(code: FsckIssueCode, detail: String): FsckReport {
            require(code in ROOT_CODES) { "$code is no root issue" }
            return FsckReport(0, 0, listOf(FsckIssue(code, null, detail)))
        }
    }
}

/**
 * Fetches a segment of the root under check. The lookup contract every fsck lookup shares ([DictionaryLookup] and
 * the codec's base frame lookup too): return null only when the store answered definitely that the object does not
 * exist (SEGMENT_MISSING, corruption); throw [java.io.IOException] for a fetch that failed (SEGMENT_UNREADABLE, the
 * report is incomplete). Any other exception is a defect, not a verdict, and propagates out of the check. A segment's
 * bytes are verified by the check itself. May do IO.
 */
fun interface SegmentFetcher {
    fun fetch(index: Int, ref: SegmentRef): ByteBuffer?
}

/**
 * One segment handed to [NwfFsck.rebuildIndex]: its bytes and the SHA-256 its object id names. The rebuild verifies
 * the bytes against [sha256], so a damaged segment can never contribute entries.
 */
class RebuildSegment(val sha256: Sha256, bytes: ByteBuffer) {
    private val content: ByteBuffer = bytes.asReadOnlyBuffer()

    fun bytes(): ByteBuffer = content.duplicate()
}

/** Offline consistency check and index rebuild. Never writes anything. */
interface NwfFsck {
    /**
     * Verifies every entry of [root]: segment present and its sha, frame CRC, header agreement, and decode. Bytes that
     * are no valid root give [FsckIssueCode.ROOT_INVALID]; fetching the root, and telling a missing root
     * ([FsckIssueCode.ROOT_MISSING]) from a failed read ([FsckIssueCode.ROOT_UNREADABLE]), is the caller's part (see
     * [FsckReport.rootNotChecked]).
     */
    fun check(root: ByteBuffer, segments: SegmentFetcher, dictionaries: DictionaryLookup): FsckReport

    /**
     * Rebuilds INDEX entries from segments alone, for a world whose root was lost. Segments are given by index; for a
     * key in more than one segment, the later segment index wins.
     *
     * Every segment is verified against its SHA-256 before use, and every frame is decoded and its NBT's own
     * coordinates (CHUNK `xPos`/`zPos`, ENTITIES `Position`) are cross-checked against the frame header, whose key no
     * CRC covers; the frame header has no `DataVersion`, so decoding also recovers it. POI record positions are not
     * checked: writers accept records outside the frame's chunk, so they prove nothing, and the verified SHA-256 is a
     * POI frame's key proof. Any failure throws [NwfFormatException], and an exception from [dictionaries] propagates
     * as is: a rebuilt index never drops or re-keys a frame silently.
     *
     * Refused with [IllegalArgumentException] when [worldHasBase] is true: such a world's deletions live only as INDEX
     * TOMBSTONEs, which no segment holds, so a rebuilt index would resurrect deleted base chunks. Pass true whenever
     * the world may have had a base. A ZSTD_BASE_DELTA frame, which only a world with a base can hold, fails with
     * [NwfFormatError.DANGLING_REFERENCE]. Returns entries in INDEX order.
     */
    fun rebuildIndex(
        segments: List<RebuildSegment>,
        dictionaries: DictionaryLookup,
        worldHasBase: Boolean,
    ): List<IndexEntry>
}
