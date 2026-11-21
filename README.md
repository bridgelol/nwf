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

0.4.0. The format may change before 1.0. Version 1 roots and segments written by this release are
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

## Performance

Measured sequentially on JDK 25 (`v0.3.0` → `v0.4.0`) across 25 KiB synthetic Minecraft chunk NBT (484-chunk world, 5.08 MiB segments, zstd level 3), median wall-clock time and exact JVM thread allocations (`ThreadMXBean`):

| Operation | v0.3.0 | v0.4.0 | Speedup | v0.3.0 alloc | v0.4.0 alloc | Alloc Δ |
|---|---:|---:|---:|---:|---:|---:|
| `WorldHeader.equals` (50k calls, ~5 KiB header) | 1,224.6 ms | 6.9 ms | **177.5×** | 2,059.9 MiB | **0 B** | **-100.0%** |
| `WorldHeader.hashCode` (50k calls, ~5 KiB header) | 789.8 ms | 23.0 ms | **34.3×** | 1,026.9 MiB | **0 B** | **-100.0%** |
| `NwfV1SegmentCodec.write` (250 frames, 5.08 MiB seg) | 3.41 ms | 3.17 ms | **1.08×** | 20.45 MiB | 5.17 MiB | **-74.7%** |
| `ZstdFramePipeline.encode` (200×25 KiB, KEEP, L3) | 9.80 ms | 8.49 ms | **1.15×** | 15.70 MiB | 5.93 MiB | **-62.2%** |
| `ZstdFramePipeline.encode` (200×25 KiB, STRIP, L3) | 11.41 ms | 9.61 ms | **1.19×** | 18.33 MiB | 10.92 MiB | **-40.4%** |
| `NwfV1WorldBuilderFactory` build + root bytes (484 chunks) | 27.11 ms | 24.73 ms | **1.10×** | 64.51 MiB | 34.47 MiB | **-46.6%** |
| `ZstdFramePipeline.decode` (200×25 KiB, STRIP, L3) | 3.25 ms | 3.20 ms | **1.02×** | 3.49 MiB | 2.64 MiB | **-24.1%** |
| `ZstdFramePipeline.decode` (200×25 KiB, KEEP, L3) | 3.27 ms | 3.21 ms | **1.02×** | 5.86 MiB | 5.01 MiB | **-14.6%** |

## License

MIT, see [LICENSE](LICENSE).
