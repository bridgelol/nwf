# NWF

NWF is a chunk-granular storage format for Minecraft worlds, with a Kotlin reader and writer.

A world is stored as the server's own chunk NBT, one frame per chunk and data kind (chunk, entities, points of
interest). Each frame is compressed on its own with zstd, optionally with a dictionary trained for that kind of
content, and carries a CRC32C that is checked before decompression. A world is one root object: a small header, a
sorted index and, for small worlds, the frames themselves (inline layout). Larger worlds keep their frames in
immutable, content-addressed segment objects that the root references (segmented layout), so a save rewrites only
the root and the frames that changed.

Other properties:

- Light can be stripped on write and recomputed by the server on load.
- A world can fall through to an immutable base snapshot, so a copy of a template stores only the chunks it changed.
- Every structure is length-checked and checksummed; malformed input fails with one exception type and never yields
  plausible but wrong chunks.
- An offline checker verifies a world and can rebuild a lost index from its segments.

The byte format is specified in [SPEC.md](SPEC.md).

## Status

0.1, draft format. The format may change before 1.0. Version 1 roots and segments written by this release are
covered by golden-file tests in `src/test/resources/nwf/`.

## Requirements

- JDK 25
- [zstd-jni](https://github.com/luben/zstd-jni), the only runtime dependency besides the Kotlin standard library

## Installation

Releases are published through [JitPack](https://jitpack.io). With Gradle (Kotlin DSL):

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.bridgelol:nwf:<tag>")
}
```

Replace `<tag>` with a release tag of this repository.

## Build

```sh
./gradlew build
```

## Usage

Write a world with the generation-1 builder, then read one chunk back.

```kotlin
import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.DictionaryLookup
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.WorldHeader
import gg.netherite.world.nwf.codec.NwfFrameHeaders
import gg.netherite.world.nwf.codec.NwfV1RootCodec
import gg.netherite.world.nwf.codec.NwfV1WorldBuilderFactory
import gg.netherite.world.nwf.codec.ZstdFramePipeline
import gg.netherite.world.nwf.convert.WorldBuildOptions
import java.nio.ByteBuffer

val pipeline = ZstdFramePipeline()
val rootCodec = NwfV1RootCodec()

// chunkNbt: uncompressed binary NBT of chunk (0, 0), as the server serializes it.
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
builder.header(WorldHeader(dataVersion = 4440, writer = "example/1", dimensions = listOf("minecraft:overworld")))
builder.chunk(ChunkKey(0, ChunkKind.CHUNK, 0, 0), ByteBuffer.wrap(chunkNbt))
val world = builder.finish() // world.segments is empty: everything fits inline
val rootBytes = rootCodec.write(world.root)

val root = rootCodec.parse(ByteBuffer.wrap(rootBytes))
val entry = root.find(ChunkKey(0, ChunkKind.CHUNK, 0, 0))!!
val frame = EncodedFrame(NwfFrameHeaders.of(entry), entry.dataVersion, root.inlinePayload(entry))
val nbt: ByteBuffer = pipeline.decode(frame, DictionaryLookup { null }, null) // light stripped
```

In a segmented world an entry that is not inline names its segment by index. `root.segments[entry.location]` is a
`SegmentRef`; fetch the segment object it names by its `sha256` (from wherever the segments are stored), parse it
with that SHA-256, and read the payload of the frame whose 28-byte frame header starts at `entry.offset`:

```kotlin
import gg.netherite.world.nwf.codec.NwfV1SegmentCodec

val ref = root.segments[entry.location]
val segmentBytes: ByteArray = fetchSegment(ref.sha256) // your storage: the object holding segment ref.sha256
val segment = NwfV1SegmentCodec.parse(ByteBuffer.wrap(segmentBytes), ref.sha256)
val segmentFrame = EncodedFrame(NwfFrameHeaders.of(entry), entry.dataVersion, segment.payload(entry))
val chunk: ByteBuffer = pipeline.decode(segmentFrame, DictionaryLookup { null }, null)
```

`segment.payload(entry)` checks the frame header against the index entry on every read.

`NwfV1Fsck` checks a whole world offline. Each issue has a severity: `FsckReport.clean` means nothing but informational
issues (such as frames a later commit superseded), `corrupt` means damage, and `complete` is false when a segment,
dictionary or base frame could not be read. One rule covers segments and bases: one the store says does not exist is
damage (a dangling reference, `*_MISSING`), one that exists but is damaged is `*_INVALID`, and one whose read failed
only makes the report incomplete (`*_UNREADABLE`). Dictionaries are shared objects, so a missing or unreadable one is
`UNAVAILABLE` and a damaged one `DICTIONARY_DAMAGED` (each naming the dictionary), never world corruption. `SegmentFetcher`, `DictionaryLookup` and `BaseFrameLookup`
signal these as null, `NwfFormatException` and `IOException`; any other exception is a defect and propagates. An
incomplete report means "re-run the check"; the checker does not retry by itself. A caller that fetches the root itself reports a failed fetch with
`FsckReport.rootNotChecked(ROOT_UNREADABLE, ...)` and an absent root with `ROOT_MISSING`. A POI record outside its
frame's chunk is a warning (`POI_RECORD_OUTSIDE_CHUNK`): the world is not clean but not corrupt. `rebuildIndex`
recovers a lost index from segments verified against their SHA-256, cross-checking every CHUNK and ENTITIES frame's
NBT coordinates against its header; it refuses worlds with a base, whose deletions only the index holds.

## License

MIT, see [LICENSE](LICENSE).
