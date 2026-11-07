@file:Suppress("MaxLineLength") // dense assertion tables read better unwrapped

package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.EntryFlags
import gg.netherite.world.nwf.FrameCodec
import gg.netherite.world.nwf.HeightmapPolicy
import gg.netherite.world.nwf.IndexEntry
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.RootDraft
import gg.netherite.world.nwf.WorldHeader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Byte-level stability of the stored format. The fixtures under `src/test/resources/nwf/` were written once by this
 * codec; they must keep parsing to the same content forever (reader stability), and the writer must keep producing
 * them byte for byte (writer stability). A writer failure after a zstd library upgrade only means zstd now emits
 * different (still valid) bytes: confirm the reader test still passes, then regenerate the fixtures deliberately.
 */
class NwfGoldenFixturesTest {
    private val pipeline = ZstdFramePipeline()

    private val header = WorldHeader(
        dataVersion = TestChunks.DATA_VERSION,
        writer = "nwf-golden",
        contentClass = "golden",
        createdAtMs = 1_700_000_000_000,
        dimensions = listOf("minecraft:overworld"),
        lightPolicy = LightPolicy.STRIP,
    )

    private val rootNbt: Map<ChunkKey, ByteArray> = sortedMapOf(
        ChunkKey(0, ChunkKind.CHUNK, 0, 0) to nbtBytes(TestChunks.chunk(0, 0, PaletteStyle.EMPTY, 1, sections = 2, blockEntities = 1)),
        ChunkKey(0, ChunkKind.ENTITIES, 0, 0) to nbtBytes(TestChunks.entities(0, 0, 2, 2)),
        ChunkKey(0, ChunkKind.POI, 0, 0) to nbtBytes(TestChunks.poi(3)),
    )

    private val segmentNbt: Map<ChunkKey, ByteArray> = sortedMapOf(
        ChunkKey(0, ChunkKind.CHUNK, 1, 1) to nbtBytes(TestChunks.chunk(1, 1, PaletteStyle.SINGLE, 4, sections = 2, blockEntities = 2)),
        ChunkKey(0, ChunkKind.ENTITIES, 1, 1) to nbtBytes(TestChunks.entities(1, 1, 1, 5)),
    )

    private fun encode(key: ChunkKey, nbt: ByteArray): EncodedFrame {
        val raw = key.kind == ChunkKind.ENTITIES
        return pipeline.encode(key, ByteBuffer.wrap(nbt), options().copy(raw = raw))
    }

    private fun rootDraft(): RootDraft {
        val inline = ByteArrayOutputStream()
        val entries = rootNbt.map { (key, nbt) ->
            val frame = encode(key, nbt)
            val offset = inline.size()
            inline.write(frame.payloadView().toBytes())
            entryOf(frame, NwfFormat.NONE_U16, offset)
        } + IndexEntry(1, 0, 0, ChunkKind.CHUNK, FrameCodec.RAW, EntryFlags.TOMBSTONE, 0xFFFF, 0xFFFF, 0, 0, 0, 0, 0)
        return RootDraft(
            0, 7, header, emptyList(), emptyList(),
            entries.sortedBy {
                it.key
            },
            ByteBuffer.wrap(inline.toByteArray())
        )
    }

    private fun segmentFrames(): List<EncodedFrame> = segmentNbt.map { (key, nbt) -> encode(key, nbt) }

    private fun expectedNbt(key: ChunkKey, nbt: ByteArray) =
        LightStripNbtFilter.scan(key.kind, nbt, LightPolicy.STRIP, HeightmapPolicy.KEEP).filtered.nbtView().toBytes()

    private fun fixture(name: String, actual: ByteArray): ByteArray {
        val stream = javaClass.getResourceAsStream("/nwf/$name")
        if (stream == null) {
            val out = Path.of(System.getProperty("user.dir"), "build", "nwf-golden", name)
            Files.createDirectories(out.parent)
            Files.write(out, actual)
            fail("fixture /nwf/$name missing: wrote the current output to $out; review it and copy it to src/test/resources/nwf/")
        }
        return stream.use { it.readAllBytes() }
    }

    @Test
    fun `golden root still parses to the same content`() {
        val bytes = fixture("golden-root.nwf", NwfV1RootCodec().write(rootDraft()))
        val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals(NwfFormat.ROOT_MAGIC, bytes.copyOf(4))
        assertEquals(1, le.getShort(4).toInt())
        assertEquals(0, le.getShort(6).toInt())
        assertEquals(7L, le.getLong(16))

        val view = NwfV1RootCodec().parse(ByteBuffer.wrap(bytes))
        assertEquals(header, view.header)
        assertEquals(rootNbt.size + 1, view.entryCount)
        assertEquals(rootDraft().entries, view.entries().toList())
        for ((key, nbt) in rootNbt) {
            val entry = view.find(key)!!
            val frame = EncodedFrame(NwfFrameHeaders.of(entry), entry.dataVersion, view.inlinePayload(entry))
            assertArrayEquals(expectedNbt(key, nbt), pipeline.decode(frame, NO_DICTS, null).toBytes(), "$key")
        }
    }

    @Test
    fun `golden root is written byte for byte`() {
        val actual = NwfV1RootCodec().write(rootDraft())
        assertArrayEquals(fixture("golden-root.nwf", actual), actual)
    }

    @Test
    fun `golden segment still parses to the same content`() {
        val written = NwfV1SegmentCodec.write(segmentFrames())
        val bytes = fixture("golden-segment.nwfs", written.bytes())
        val view = NwfV1SegmentCodec.parse(ByteBuffer.wrap(bytes), sha256(bytes))
        assertEquals(segmentNbt.keys.toList(), view.frames().map { it.header.key }.toList())
        for (ref in view.frames()) {
            val frame = EncodedFrame(ref.header, 0, view.payload(ref))
            val key = ref.header.key
            assertArrayEquals(expectedNbt(key, segmentNbt.getValue(key)), pipeline.decode(frame, NO_DICTS, null).toBytes())
        }
        assertEquals(NwfFormat.SEGMENT_HEADER_BYTES, view.headerOffsets().first())
    }

    @Test
    fun `golden segment is written byte for byte`() {
        val actual = NwfV1SegmentCodec.write(segmentFrames()).bytes()
        assertArrayEquals(fixture("golden-segment.nwfs", actual), actual)
    }
}
