package gg.netherite.world.nwf

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * A zstd dictionary, identified by the SHA-256 of its bytes. Immutable; load it once per process and share it.
 * [sha256] is the SHA-256 of [bytes]; constructing one does not verify it, [verified] does.
 */
class NwfDictionary(val sha256: Sha256, val zstdDictId: Int, bytes: ByteArray) {
    private val content: ByteArray = bytes.copyOf()

    val size: Int get() = content.size

    fun bytes(): ByteArray = content.copyOf()

    /** True when [sha256] matches the bytes. Callers check it once after a fetch, before caching. */
    fun verified(): Boolean =
        MessageDigest.getInstance("SHA-256").digest(content).contentEquals(sha256.bytes())
}

/**
 * Resolves the dictionary of a root's `dictSlot`. Implementations are thread-safe and do no IO on the hot path.
 *
 * The contract matches [SegmentFetcher]: null only when the dictionary definitely does not exist or the root does not
 * define the slot; [NwfFormatException] when it exists but is damaged (for example its bytes differ from its SHA-256);
 * [java.io.IOException] when it could not be read. Decoding fails closed in every case. [NwfFsck.check] reports them as
 * DICTIONARY_MISSING, DICTIONARY_INVALID (both corruption) and DICTIONARY_UNREADABLE (incomplete); any other exception
 * is a defect and propagates.
 */
fun interface DictionaryLookup {
    /** The dictionary for [slot], or null when it does not exist. */
    fun bySlot(slot: Int): NwfDictionary?
}

/** Encode settings of one frame. [dictionary] / [dictSlot] go together; [baseNbt] is required for base delta. */
data class FrameEncodeOptions(
    val level: Int,
    val lightPolicy: LightPolicy,
    val heightmapPolicy: HeightmapPolicy,
    val dictionary: NwfDictionary? = null,
    val dictSlot: Int = NwfFormat.NONE_U16,
    /** The base snapshot's decoded NBT for the same key, when ZSTD_BASE_DELTA is enabled for the prefix. */
    val baseNbt: ByteBuffer? = null,
    /**
     * Store the filtered NBT uncompressed (codec RAW): the frames of a packed-inline root, whose INLINE body is
     * one zstd stream. Excludes [dictionary] and [baseNbt].
     */
    val raw: Boolean = false,
)

/**
 * The frame pipeline. CPU only, thread-safe, no IO.
 *
 * encode: NBT -> (CHUNK and STRIP) [NbtStreamFilter] -> zstd (dict / base delta) -> frame with CRC32C of the payload.
 * decode is the inverse without the filter: CRC32C checked before decompression; the result is uncompressed binary NBT
 * exactly as `NbtIo.write` produced it (minus stripped tags). Any failure throws [NwfFormatException].
 */
interface FramePipeline {
    fun encode(key: ChunkKey, nbt: ByteBuffer, options: FrameEncodeOptions): EncodedFrame

    /** [baseNbt] is the base snapshot's decoded frame for the same key; required for ZSTD_BASE_DELTA frames. */
    fun decode(frame: EncodedFrame, dictionaries: DictionaryLookup, baseNbt: ByteBuffer?): ByteBuffer
}

/** What [NbtStreamFilter.filter] produced: the NBT to compress, its `DataVersion` and the entry flags to set. */
class FilteredNbt(nbt: ByteBuffer, val dataVersion: Int, val entryFlags: Int) {
    private val bytes: ByteBuffer = nbt.asReadOnlyBuffer()

    fun nbtView(): ByteBuffer = bytes.duplicate()
}

/**
 * Streaming NBT filter, no tree. For CHUNK with STRIP it drops `sections[].BlockLight`, `sections[].SkyLight`,
 * the Starlight state tags and root `isLightOn` / Starlight version; with heightmaps STRIP it drops `Heightmaps`.
 * Every other byte is identical to the input (golden-file tested). ENTITIES and POI pass through unchanged.
 */
interface NbtStreamFilter {
    fun filter(kind: ChunkKind, nbt: ByteBuffer, light: LightPolicy, heightmaps: HeightmapPolicy): FilteredNbt
}

/** Trains zstd dictionaries. Pure CPU. */
interface DictionaryTrainer {
    /** A dictionary of at most [sizeBytes] trained on [samples] (uncompressed, already filtered frames). */
    fun train(samples: List<ByteArray>, sizeBytes: Int): ByteArray
}
