@file:Suppress("MaxLineLength") // dense assertion tables read better unwrapped

package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.EntryFlags
import gg.netherite.world.nwf.FrameCodec
import gg.netherite.world.nwf.FrameEncodeOptions
import gg.netherite.world.nwf.FrameHeader
import gg.netherite.world.nwf.HeightmapPolicy
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.NwfDictionary
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.Sha256
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZstdFramePipelineTest {
    private val pipeline = ZstdFramePipeline()

    @AfterAll
    fun close() = pipeline.close()

    private fun expected(key: ChunkKey, nbt: ByteArray, light: LightPolicy): ByteArray =
        LightStripNbtFilter.scan(key.kind, nbt, light, HeightmapPolicy.KEEP).filtered.nbtView().toBytes()

    private fun encode(key: ChunkKey, nbt: ByteArray, opts: FrameEncodeOptions) =
        pipeline.encode(key, ByteBuffer.wrap(nbt), opts)

    @Test
    fun `every kind and palette style round trips with and without a dictionary and at both levels`() {
        val dict = TestDictionaries.chunk
        for ((key, nbt) in sampleNbt()) {
            for (light in LightPolicy.entries) {
                val want = expected(key, nbt, light)
                val variants = listOf(
                    options(light) to FrameCodec.ZSTD,
                    options(light, level = 19) to FrameCodec.ZSTD,
                    options(light, dictionary = dict, slot = 0) to FrameCodec.ZSTD_DICT,
                    options(light, level = 19, dictionary = dict, slot = 0) to FrameCodec.ZSTD_DICT,
                    options(light).copy(raw = true) to FrameCodec.RAW,
                )
                for ((opts, codec) in variants) {
                    val frame = encode(key, nbt, opts)
                    assertEquals(codec, frame.header.codec)
                    assertEquals(want.size, frame.header.rawLength)
                    assertEquals(TestChunks.DATA_VERSION, frame.dataVersion)
                    assertEquals(key, frame.key)
                    val decoded = pipeline.decode(frame, lookupOf(dict), null).toBytes()
                    assertArrayEquals(want, decoded, "$key $light $codec level ${opts.level}")
                }
            }
        }
    }

    @Test
    fun `dictionary on and off decode to the same bytes, and the dictionary helps small frames`() {
        val dict = TestDictionaries.chunk
        val key = ChunkKey(0, ChunkKind.CHUNK, 7, 7)
        val nbt = nbtBytes(TestChunks.chunk(7, 7, PaletteStyle.FULL, 777, sections = 4))
        val plain = encode(key, nbt, options())
        val withDict = encode(key, nbt, options(dictionary = dict, slot = 0))
        assertArrayEquals(pipeline.decode(plain, NO_DICTS, null).toBytes(), pipeline.decode(withDict, lookupOf(dict), null).toBytes())
        assertEquals(0, withDict.header.dictSlot)
        assertTrue(withDict.header.storedLength < plain.header.storedLength, "dict ${withDict.header.storedLength} vs ${plain.header.storedLength}")
    }

    @Test
    fun `light strip sets the flag on chunk frames only`() {
        for ((key, nbt) in sampleNbt()) {
            val frame = encode(key, nbt, options(LightPolicy.STRIP))
            val stripped = frame.header.flags and EntryFlags.LIGHT_STRIPPED != 0
            assertEquals(key.kind == ChunkKind.CHUNK, stripped, "$key")
        }
    }

    @Test
    fun `encoding is deterministic`() {
        for ((key, nbt) in sampleNbt()) {
            val a = encode(key, nbt, options(dictionary = TestDictionaries.chunk, slot = 0))
            val b = encode(key, nbt, options(dictionary = TestDictionaries.chunk, slot = 0))
            assertArrayEquals(a.payloadView().toBytes(), b.payloadView().toBytes())
            assertEquals(a.header, b.header)
        }
    }

    @Test
    fun `base delta round trips and stores a near-identical chunk in far fewer bytes`() {
        val key = ChunkKey(0, ChunkKind.CHUNK, 2, 2)
        val baseNbt =
            expected(key, nbtBytes(TestChunks.chunk(2, 2, PaletteStyle.FULL, 5, lastUpdate = 1)), LightPolicy.STRIP)
        val changed = nbtBytes(TestChunks.chunk(2, 2, PaletteStyle.FULL, 5, lastUpdate = 99_999))
        val opts = options().copy(baseNbt = ByteBuffer.wrap(baseNbt))
        val delta = encode(key, changed, opts)
        val plain = encode(key, changed, options())
        assertEquals(FrameCodec.ZSTD_BASE_DELTA, delta.header.codec)
        assertTrue(delta.header.flags and EntryFlags.BASE_DELTA != 0)
        assertEquals(NwfFormat.NONE_U16, delta.header.dictSlot)
        assertTrue(delta.header.storedLength * 10 < plain.header.storedLength, "${delta.header.storedLength} vs ${plain.header.storedLength}")
        val decoded = pipeline.decode(delta, NO_DICTS, ByteBuffer.wrap(baseNbt)).toBytes()
        assertArrayEquals(expected(key, changed, LightPolicy.STRIP), decoded)

        assertFormatError(NwfFormatError.DANGLING_REFERENCE) { pipeline.decode(delta, NO_DICTS, null) }
        val wrongBase = expected(key, nbtBytes(TestChunks.chunk(2, 2, PaletteStyle.FULL, 6)), LightPolicy.STRIP)
        assertFormatError(NwfFormatError.BAD_PAYLOAD) { pipeline.decode(delta, NO_DICTS, ByteBuffer.wrap(wrongBase)) }
    }

    @Test
    fun `a flipped payload bit fails the CRC before decompression`() {
        val key = ChunkKey(0, ChunkKind.CHUNK, 0, 0)
        val frame = encode(key, nbtBytes(TestChunks.chunk(0, 0, PaletteStyle.SINGLE, 1)), options())
        val payload = frame.payloadView().toBytes()
        for (bit in 0 until payload.size * 8 step 7) {
            val broken = EncodedFrame(frame.header, frame.dataVersion, ByteBuffer.wrap(payload.flipBit(bit)))
            assertFormatError(NwfFormatError.CRC_MISMATCH) { pipeline.decode(broken, NO_DICTS, null) }
        }
    }

    @Test
    fun `garbage with a matching CRC fails to decode, never returns bytes`() {
        val key = ChunkKey(0, ChunkKind.CHUNK, 0, 0)
        val frame = encode(key, nbtBytes(TestChunks.chunk(0, 0, PaletteStyle.SINGLE, 1)), options())
        val payload = frame.payloadView().toBytes()
        for (bit in 0 until payload.size * 8 step 5) {
            val bad = payload.flipBit(bit)
            val header = frame.header.copy(crc32c = crc(bad))
            var decoded: ByteArray? = null
            failsClosedOrParses {
                decoded = pipeline.decode(EncodedFrame(header, 0, ByteBuffer.wrap(bad)), NO_DICTS, null).toBytes()
            }
            // Either refused, or (a bit zstd ignores) exactly the original bytes: never different bytes.
            decoded?.let { assertArrayEquals(pipeline.decode(frame, NO_DICTS, null).toBytes(), it, "bit $bit") }
        }
        val wrongLength = frame.header.copy(rawLength = frame.header.rawLength - 1)
        assertFormatError(NwfFormatError.BAD_PAYLOAD) {
            pipeline.decode(EncodedFrame(wrongLength, 0, ByteBuffer.wrap(payload)), NO_DICTS, null)
        }
    }

    @Test
    fun `dictionary frames need the right dictionary`() {
        val key = ChunkKey(0, ChunkKind.CHUNK, 1, 1)
        val frame =
            encode(key, nbtBytes(TestChunks.chunk(1, 1, PaletteStyle.FULL, 2)), options(dictionary = TestDictionaries.chunk, slot = 0))
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) { pipeline.decode(frame, NO_DICTS, null) }
        assertFormatError(NwfFormatError.BAD_PAYLOAD) { pipeline.decode(frame, lookupOf(TestDictionaries.other), null) }
        val forged =
            NwfDictionary(Sha256(ByteArray(32) { 7 }), TestDictionaries.chunk.zstdDictId, TestDictionaries.chunk.bytes())
        assertFormatError(NwfFormatError.SHA_MISMATCH) { pipeline.decode(frame, lookupOf(forged), null) }
    }

    @Test
    fun `inconsistent frame headers fail closed`() {
        val key = ChunkKey(0, ChunkKind.CHUNK, 0, 0)
        val frame = encode(key, nbtBytes(TestChunks.chunk(0, 0, PaletteStyle.EMPTY, 1)), options())
        fun with(h: FrameHeader) = EncodedFrame(h, 0, frame.payloadView())
        assertFormatError(NwfFormatError.UNKNOWN_FLAGS) {
            pipeline.decode(with(frame.header.copy(flags = 0x80)), NO_DICTS, null)
        }
        assertFormatError(NwfFormatError.UNKNOWN_FLAGS) {
            pipeline.decode(with(frame.header.copy(flags = EntryFlags.TOMBSTONE)), NO_DICTS, null)
        }
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) {
            pipeline.decode(with(frame.header.copy(dictSlot = 0)), NO_DICTS, null)
        }
        assertFormatError(NwfFormatError.UNKNOWN_FLAGS) {
            pipeline.decode(with(frame.header.copy(codec = FrameCodec.ZSTD_BASE_DELTA)), NO_DICTS, ByteBuffer.allocate(1))
        }
    }

    @Test
    fun `encode refuses NBT that belongs to another chunk`() {
        val nbt = nbtBytes(TestChunks.chunk(5, 5, PaletteStyle.EMPTY, 1))
        assertFormatError(NwfFormatError.BAD_PAYLOAD) { encode(ChunkKey(0, ChunkKind.CHUNK, 5, 6), nbt, options()) }
        val entities = nbtBytes(TestChunks.entities(1, 2, 1, 1))
        assertFormatError(NwfFormatError.BAD_PAYLOAD) { encode(ChunkKey(0, ChunkKind.ENTITIES, 2, 1), entities, options()) }
        assertFormatError(NwfFormatError.BAD_PAYLOAD) {
            encode(ChunkKey(0, ChunkKind.CHUNK, 0, 0), byteArrayOf(1, 2, 3), options())
        }
    }

    @Test
    fun `invalid options are programming errors`() {
        val key = ChunkKey(0, ChunkKind.CHUNK, 0, 0)
        val nbt = nbtBytes(TestChunks.chunk(0, 0, PaletteStyle.EMPTY, 1))
        assertThrows<IllegalArgumentException> { encode(key, nbt, options(dictionary = TestDictionaries.chunk)) }
        assertThrows<IllegalArgumentException> { encode(key, nbt, options(slot = 0)) }
        assertThrows<IllegalArgumentException> { encode(key, nbt, options(level = 99)) }
        assertThrows<IllegalArgumentException> {
            encode(key, nbt, options(dictionary = TestDictionaries.chunk, slot = 0).copy(raw = true))
        }
    }
}
