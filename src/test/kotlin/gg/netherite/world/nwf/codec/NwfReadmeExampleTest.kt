package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.DictionaryLookup
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.HeightmapPolicy
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.Sha256
import gg.netherite.world.nwf.WorldHeader
import gg.netherite.world.nwf.convert.WorldBuildOptions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** The usage example of the public README, kept compiling and correct. */
class NwfReadmeExampleTest {
    @Test
    fun `the README example writes a world and reads the chunk back`() {
        val chunkNbt = nbtBytes(TestChunks.chunk(0, 0, PaletteStyle.FULL, 1))

        val pipeline = ZstdFramePipeline()
        val rootCodec = NwfV1RootCodec()

        val builder = NwfV1WorldBuilderFactory(pipeline, rootCodec).create(
            WorldBuildOptions(
                contentClass = "example",
                segmentPrefix = "world-1",
                saveLevel = 3,
                lightPolicy = LightPolicy.STRIP,
                inlineBudgetBytes = 256L * 1024,
                segmentTargetBytes = 4L * 1024 * 1024,
            ),
        )
        builder.header(
            WorldHeader(dataVersion = 4440, writer = "example/1", dimensions = listOf("minecraft:overworld")),
        )
        builder.chunk(ChunkKey(0, ChunkKind.CHUNK, 0, 0), ByteBuffer.wrap(chunkNbt))
        val world = builder.finish()
        assertTrue(world.segments.isEmpty())
        val rootBytes = rootCodec.write(world.root)

        val root = rootCodec.parse(ByteBuffer.wrap(rootBytes))
        val entry = root.find(ChunkKey(0, ChunkKind.CHUNK, 0, 0))!!
        val frame = EncodedFrame(NwfFrameHeaders.of(entry), entry.dataVersion, root.inlinePayload(entry))
        val nbt: ByteBuffer = pipeline.decode(frame, DictionaryLookup { null }, null)

        val expected = LightStripNbtFilter.scan(ChunkKind.CHUNK, chunkNbt, LightPolicy.STRIP, HeightmapPolicy.KEEP)
        assertArrayEquals(expected.filtered.nbtView().toBytes(), nbt.toBytes())
    }

    @Test
    fun `the README segmented example reads the frame after its header`() {
        val chunkNbt = nbtBytes(TestChunks.chunk(0, 0, PaletteStyle.FULL, 1))
        val pipeline = ZstdFramePipeline()
        val rootCodec = NwfV1RootCodec()
        val builder = NwfV1WorldBuilderFactory(pipeline, rootCodec).create(
            WorldBuildOptions(
                contentClass = "example",
                segmentPrefix = "world-1",
                saveLevel = 3,
                lightPolicy = LightPolicy.STRIP,
                inlineBudgetBytes = 0,
                segmentTargetBytes = 4L * 1024 * 1024,
            ),
        )
        builder.header(
            WorldHeader(dataVersion = 4440, writer = "example/1", dimensions = listOf("minecraft:overworld")),
        )
        builder.chunk(ChunkKey(0, ChunkKind.CHUNK, 0, 0), ByteBuffer.wrap(chunkNbt))
        val world = builder.finish()
        val stored = world.segments.associateBy { it.sha256 }
        val fetchSegment = { sha: Sha256 -> stored.getValue(sha).bytes() }
        val root = rootCodec.parse(ByteBuffer.wrap(rootCodec.write(world.root)))
        val entry = root.find(ChunkKey(0, ChunkKind.CHUNK, 0, 0))!!

        // The README snippet, verbatim.
        val ref = root.segments[entry.location]
        val segmentBytes: ByteArray = fetchSegment(ref.sha256)
        val segment = NwfV1SegmentCodec.parse(ByteBuffer.wrap(segmentBytes), ref.sha256)
        val segmentFrame = EncodedFrame(NwfFrameHeaders.of(entry), entry.dataVersion, segment.payload(entry))
        val chunk: ByteBuffer = pipeline.decode(segmentFrame, DictionaryLookup { null }, null)

        val expected = LightStripNbtFilter.scan(ChunkKind.CHUNK, chunkNbt, LightPolicy.STRIP, HeightmapPolicy.KEEP)
        assertArrayEquals(expected.filtered.nbtView().toBytes(), chunk.toBytes())
    }
}
