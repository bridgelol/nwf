@file:Suppress("MaxLineLength") // dense assertion tables read better unwrapped

package gg.netherite.world.nwf.codec

import com.github.luben.zstd.Zstd
import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.DictionaryLookup
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.FrameEncodeOptions
import gg.netherite.world.nwf.HeightmapPolicy
import gg.netherite.world.nwf.IndexEntry
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.NwfDictionary
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.NwfFormatException
import gg.netherite.world.nwf.WorldHeader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32C

fun assertFormatError(expected: NwfFormatError, block: () -> Unit): NwfFormatException {
    val e = assertThrows<NwfFormatException> { block() }
    assertEquals(expected, e.reason, e.message)
    return e
}

/** Asserts [block] either returns or throws [NwfFormatException], never anything else. */
fun failsClosedOrParses(block: () -> Unit): Boolean =
    try {
        block()
        true
    } catch (_: NwfFormatException) {
        false
    }

fun ByteBuffer.toBytes(): ByteArray = copyRemaining()

fun ByteArray.flipBit(bit: Int): ByteArray = copyOf().also {
    it[bit / 8] = (it[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
}

fun crc(bytes: ByteArray): Int = CRC32C().apply { update(bytes) }.value.toInt()

val NO_DICTS: DictionaryLookup = DictionaryLookup { null }

fun header(dimensions: List<String> = listOf("minecraft:overworld")): WorldHeader =
    WorldHeader(writer = "nwf-test", dataVersion = TestChunks.DATA_VERSION, contentClass = "test", dimensions = dimensions)

fun options(
    light: LightPolicy = LightPolicy.STRIP,
    level: Int = 3,
    dictionary: NwfDictionary? = null,
    slot: Int = NwfFormat.NONE_U16,
): FrameEncodeOptions = FrameEncodeOptions(level, light, HeightmapPolicy.KEEP, dictionary, slot)

/** Dictionaries trained once per test JVM on synthetic chunks. */
object TestDictionaries {
    val chunk: NwfDictionary by lazy {
        NwfDictionaries.of(ZstdDictionaryTrainer.train(samples(10_000, PaletteStyle.FULL), 16 * 1024))
    }

    val other: NwfDictionary by lazy {
        NwfDictionaries.of(ZstdDictionaryTrainer.train(samples(50_000, PaletteStyle.SINGLE), 8 * 1024))
    }

    private fun samples(seedBase: Int, style: PaletteStyle): List<ByteArray> = (0 until 300).map { i ->
        val nbt = nbtBytes(TestChunks.chunk(i, -i, style, seedBase + i, sections = 4, blockEntities = i % 4))
        LightStripNbtFilter.scan(ChunkKind.CHUNK, nbt, LightPolicy.STRIP, HeightmapPolicy.KEEP)
            .filtered.nbtView().toBytes()
    }
}

fun lookupOf(vararg dicts: NwfDictionary): DictionaryLookup = DictionaryLookup { dicts.getOrNull(it) }

/** An index entry for an encoded frame. */
fun entryOf(frame: EncodedFrame, location: Int, offset: Int): IndexEntry {
    val h = frame.header
    return IndexEntry(
        h.x, h.z, h.dim, h.kind, h.codec, h.flags, location, h.dictSlot, offset, h.storedLength, h.rawLength,
        h.crc32c, frame.dataVersion,
    )
}

/** Varied synthetic content of one world: every kind and palette style, with and without block entities. */
fun sampleNbt(): Map<ChunkKey, ByteArray> {
    val out = sortedMapOf<ChunkKey, ByteArray>()
    PaletteStyle.entries.forEachIndexed { i, style ->
        for (blockEntities in listOf(0, 3)) {
            val x = i * 2 + blockEntities - 40
            val z = 33 - i
            out[ChunkKey(0, ChunkKind.CHUNK, x, z)] = nbtBytes(TestChunks.chunk(x, z, style, i * 10 + blockEntities, blockEntities = blockEntities))
        }
    }
    out[ChunkKey(0, ChunkKind.ENTITIES, 0, 0)] = nbtBytes(TestChunks.entities(0, 0, 5, 1))
    out[ChunkKey(0, ChunkKind.ENTITIES, -1, 31)] = nbtBytes(TestChunks.entities(-1, 31, 0, 2))
    out[ChunkKey(0, ChunkKind.POI, 0, 0)] = nbtBytes(TestChunks.poi(3))
    return out
}

/**
 * An independent little-endian root assembler for tests: computes TOC offsets, section CRCs and `headerCrc` from the
 * spec text, so tests can build roots the writer would refuse to write.
 */
object RawRoot {
    class Sec(
        val type: Int,
        val flags: Int,
        val stored: ByteArray,
        val rawLength: Int = stored.size,
        val crc: Int? = null
    )

    class Idx(
        val x: Int,
        val z: Int,
        val dim: Int = 0,
        val kind: Int = 0,
        val codec: Int = 1,
        val flags: Int = 0,
        val location: Int = 0xFFFF,
        val dictSlot: Int = 0xFFFF,
        val offset: Int = 0,
        val stored: Int = 0,
        val raw: Int = 0,
        val crc: Int = 0,
        val dataVersion: Int = TestChunks.DATA_VERSION,
    )

    fun assemble(
        sections: List<Sec>,
        flags: Int = 0,
        generation: Long = 1,
        major: Int = 1,
        minor: Int = 0,
    ): ByteArray {
        val tocEnd = 32 + 32 * sections.size
        val total = tocEnd + sections.sumOf { it.stored.size }
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x4E, 0x57, 0x46, 0x52)).putShort(major.toShort()).putShort(minor.toShort())
        b.putInt(flags).putInt(sections.size).putLong(generation).putInt(0).putInt(0)
        var offset = tocEnd.toLong()
        for (s in sections) {
            b.putShort(s.type.toShort()).putShort(s.flags.toShort()).putShort(0xFFFF.toShort()).putShort(0)
            b.putInt(s.crc ?: crc(s.stored)).putInt(s.rawLength).putLong(offset).putLong(s.stored.size.toLong())
            offset += s.stored.size
        }
        for (s in sections) b.put(s.stored)
        val bytes = b.array()
        val headerCrc = CRC32C().apply {
            update(bytes, 0, 24)
            update(bytes, 32, tocEnd - 32)
        }.value.toInt()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(24, headerCrc)
        return bytes
    }

    fun headerSection(h: WorldHeader = header()): Sec = Sec(1, 0, NwfV1RootCodec.encodeHeader(h))

    fun indexSection(entries: List<Idx>): Sec {
        val raw = ByteBuffer.allocate(4 + 36 * entries.size).order(ByteOrder.LITTLE_ENDIAN)
        raw.putInt(entries.size)
        for (e in entries) {
            raw.putInt(e.x).putInt(e.z).put(e.dim.toByte()).put(e.kind.toByte()).put(e.codec.toByte())
                .put(e.flags.toByte()).putShort(e.location.toShort()).putShort(e.dictSlot.toShort())
                .putInt(e.offset).putInt(e.stored).putInt(e.raw).putInt(e.crc).putInt(e.dataVersion)
        }
        val bytes = raw.array()
        return Sec(4, 2, Zstd.compress(bytes, 3), bytes.size)
    }

    fun inlineSection(bytes: ByteArray): Sec = Sec(5, 0, bytes)

    /** Rewrites `headerCrc` after a test patched bytes [0,24) or the TOC. */
    fun fixHeaderCrc(bytes: ByteArray): ByteArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val tocEnd = 32 + 32 * b.getInt(12)
        val headerCrc = CRC32C().apply {
            update(bytes, 0, 24)
            update(bytes, 32, tocEnd - 32)
        }.value.toInt()
        b.putInt(24, headerCrc)
        return bytes
    }
}
