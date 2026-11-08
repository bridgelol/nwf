# NWF v1 byte format

Status: draft, format major 1, minor 0. It may change before library version 1.0.

This document specifies the stored bytes of NWF version 1. The key words MUST, MUST NOT, SHOULD and MAY are used as
in RFC 2119. Sections 1 to 11 are normative. Section 12 (transit envelope) and section 13 (storage layout guidance)
are not. Where this document and the reference implementation in this repository disagree, the implementation's
golden-file tests (`src/test/resources/nwf/`) decide, and this document is corrected.

## 1. Conventions

- All integers are little-endian. `u8`, `u16`, `u32`, `u64` are unsigned; `i32` and `i64` are two's complement.
- CRC32C is the Castagnoli CRC-32 (polynomial 0x1EDC6F41, reflected, initial value and final XOR 0xFFFFFFFF), as
  computed by `java.util.zip.CRC32C`. It is stored as a `u32`.
- SHA-256 digests are 32 bytes.
- "zstd frame" means one Zstandard frame as defined by RFC 8878.
- NBT means Minecraft's uncompressed binary NBT: big-endian, a named root compound, exactly as the server's own
  serializer writes it.
- A reader that rejects input "fails closed": it MUST NOT use any part of the rejected object, and MUST NOT treat a
  rejected chunk as absent (a server would otherwise regenerate it).

## 2. Objects

NWF v1 has three object types:

| Object | Magic | Identity |
|---|---|---|
| Root | `NWFR` (4E 57 46 52) | The world. Rewritten on every save. |
| Segment | `NWFS` (4E 57 46 53) | SHA-256 of the whole object. Immutable. |
| Dictionary | zstd dictionary magic (37 A4 30 EC) | SHA-256 of the whole object. Immutable. |

A root describes the whole world: its HEADER, the dictionaries and segments it uses, and an INDEX with one entry per
stored `(dimension, kind, chunk x, chunk z)`. Each entry points at a frame payload that is either inside the root
(INLINE section) or inside a segment.

The first four bytes classify an object. Fewer than four bytes are never a root or a segment.

## 3. Root object

### 3.1 Fixed header (32 bytes)

| Offset | Type | Field | Rule |
|---|---|---|---|
| 0 | 4 B | magic | `NWFR` |
| 4 | u16 | major | MUST be 1. A reader MUST reject any other value. |
| 6 | u16 | minor | 0 in this version. A reader MUST accept any minor. |
| 8 | u32 | flags | bit 0 TEMPLATE, bit 1 HAS_BASE. Other bits MUST be 0. |
| 12 | u32 | sectionCount | 1 to 32. |
| 16 | u64 | generation | At least 1 and at most 2^63 - 1. 1 for a new world; +1 per save. |
| 24 | u32 | headerCrc | CRC32C over bytes [0, 24) followed by the TOC bytes. |
| 28 | u32 | reserved | MUST be 0. |

TEMPLATE marks an immutable base snapshot (section 9). HAS_BASE marks a world whose lookups fall through to a base.
A root MUST NOT have both flags.

The TOC follows at offset 32: `sectionCount` entries of 32 bytes. Section bodies follow the TOC.

A reader MUST reject: a bad magic, an object shorter than 32 bytes or longer than 64 MiB, another major, a
`sectionCount` outside 1..32, a TOC that does not fit, a wrong `headerCrc`, a non-zero reserved field, an unknown
flag bit, and a generation below 1.

### 3.2 TOC entry (32 bytes)

| Offset | Type | Field | Rule |
|---|---|---|---|
| 0 | u16 | type | Section type, see 3.3. |
| 2 | u16 | flags | bit 0 OPTIONAL, bit 1 ZSTD, bit 2 DICT. Other bits MUST be 0 in a known section. |
| 4 | u16 | dictSlot | MUST be 0xFFFF in a known section in v1. |
| 6 | u16 | reserved | MUST be 0. |
| 8 | u32 | crc32c | CRC32C of the stored body bytes. |
| 12 | u32 | rawLength | Body length after decompression; at most 256 MiB in a section the reader decodes. |
| 16 | u64 | offset | Byte offset of the body in the root. |
| 24 | u64 | storedLength | Stored body length. |

Bodies are contiguous and in TOC order: the first body starts right after the TOC (offset `32 + 32 * sectionCount`),
each next body starts where the previous one ends, and the last body ends exactly at the end of the object. A reader
MUST reject gaps, overlaps, out-of-order bodies, a body beyond the object, and trailing bytes.

A reader MUST check every section's CRC32C, including sections it does not know.

With ZSTD set, the stored body is one zstd frame that MUST decompress to exactly `rawLength` bytes. Without it,
`rawLength` MUST equal `storedLength`. DICT (a dictionary-compressed section body) is reserved: v1 writers MUST NOT set
it, and a reader MUST reject a known section that has it or a `dictSlot` other than 0xFFFF.

The flags and `dictSlot` rules apply to known section types only. A reader MUST skip a section of unknown type that
has OPTIONAL set without checking its other flag bits, its `dictSlot` or its `rawLength` (a later minor may define
them; its CRC32C and the TOC layout rules above still apply), and MUST reject a section of unknown type without OPTIONAL.

Any known section MAY be zstd-compressed. The reference writer compresses INDEX always and INLINE only when packed
(3.8), and writes the others uncompressed.

### 3.3 Section types

| Type | Name | Presence | Body |
|---|---|---|---|
| 1 | HEADER | required | Protobuf message, 3.4. |
| 2 | DICTS | when any entry uses a dictionary | Dictionary references, 3.5. |
| 3 | SEGMENTS | when any entry is in a segment | Segment references, 3.6. |
| 4 | INDEX | required | Index entries, 3.7. |
| 5 | INLINE | when any entry is inline | Concatenated frame payloads, 3.8. |
| 6 | STATS | optional | Informational protobuf, 3.9. |

A known section type MUST NOT appear twice. A reader MUST reject a missing required section. A reader MUST skip a
section of unknown type that has the OPTIONAL flag, and MUST reject one that does not. Section order is free; the
reference writer emits HEADER, DICTS, SEGMENTS, INDEX, INLINE, STATS.

### 3.4 HEADER

The HEADER body is a protobuf (proto3 wire format) message. Field numbers are part of the format: new fields get new
numbers, and numbers are never reused.

| No. | Name | Wire type | Meaning |
|---|---|---|---|
| 1 | meta | bytes | Host world metadata, opaque to NWF. |
| 2 | world_uuid | bytes | World UUID, 16 bytes big-endian (most significant half first), or empty. |
| 3 | world_pdc | bytes | Host persistent data of the world, opaque. |
| 4 | level_state | bytes (message) | Host level state (time, weather, border, game rules), opaque. Optional. |
| 5 | data_version | varint (int32) | Minecraft data version the world was last written with. |
| 6 | writer | string | Identity of the writing software, free text. |
| 7 | origin | varint (int32) | 0 = written natively; other values are host-defined. |
| 8 | legacy_etag | string | Version tag of the source object a converter read. Optional. |
| 9 | content_digest | bytes | Optional content digest for comparing worlds. |
| 20 | content_class | string | Host label for a kind of world; selects dictionaries and light policy. |
| 21 | parent_root_sha | bytes | SHA-256 of the previous root's bytes; empty for generation 1. |
| 22 | created_at_ms | varint (int64) | Creation time, Unix milliseconds. |
| 23 | committed_at_ms | varint (int64) | Time of the save that wrote this root, Unix milliseconds. |
| 24 | dimensions | repeated string | Dimension keys, for example `minecraft:overworld`. Position = `dim` byte. |
| 25 | base_root_sha | bytes | SHA-256 of the base snapshot root (section 9). |
| 26 | base_key | string | Host name of the base snapshot, informational. |
| 27 | light_policy | varint (enum) | 0 KEEP, 1 STRIP (section 7). |
| 28 | heightmap_policy | varint (enum) | 0 KEEP, 1 STRIP (section 7). |
| 29 | converter | string | Converter that produced the world from another format; empty when native. |
| 30 | source_user_data | bytes | The source format's world user data, carried verbatim by converters. |
| 31 | segment_prefix | string | World-private name prefix for its segments, fixed at creation. |
| 32 | save_id | string | Host id of the save that wrote this root. |

Fields 1 to 9 and 30 are host fields: NWF stores and copies them but gives them no meaning beyond their wire type.

Rules:

- `world_uuid` MUST be empty or exactly 16 bytes; a reader MUST reject any other length.
- `dimensions` MUST have at most 256 entries. A reader MUST reject a HEADER with more, counting while it decodes.
- A HEADER MUST NOT carry more than 1024 unknown fields; a reader MUST reject more, counting while it decodes.
- `base_root_sha` MUST be exactly 32 bytes when HAS_BASE is set. A reader MUST reject HAS_BASE without a 32-byte
  `base_root_sha`, and a 32-byte `base_root_sha` without HAS_BASE.
- An unknown `light_policy` or `heightmap_policy` value MUST be rejected.
- A reader MUST reject: a field number of 0, a known field with the wrong wire type, a group (wire types 3 and 4), a
  truncated field, a varint longer than 10 bytes, and a string that is not UTF-8.
- A reader MUST accept fields in any order. For a repeated scalar field the last value wins. An absent bytes field
  and an empty one mean the same.
- Unknown fields SHOULD be preserved verbatim when a root is rewritten. The reference implementation preserves them.

Canonical encoding, as the reference writer produces it (and as the golden files require): fields in ascending number
order, unknown fields merged in by number; bytes fields 1, 2, 3, 9, 21, 25 and 30 always written, even when empty;
string, integer and enum fields written only when non-empty or non-zero; fields 4 and 8 written only when present;
each dimension as its own field 24; integers as plain varints, a negative value as its 10-byte two's complement form.

### 3.5 DICTS

`u16 count`, then `count` entries of 36 bytes: `u32 zstdDictId`, `32 B sha256`. The body length MUST be
`2 + 36 * count`. An entry's position is its dictionary slot. `sha256` names the dictionary object; `zstdDictId` is the
dictionary id recorded inside that dictionary.

### 3.6 SEGMENTS

`u16 count`, then `count` entries of 48 bytes:

| Offset | Type | Field |
|---|---|---|
| 0 | 32 B | sha256: the segment id |
| 32 | u32 | salt: opaque to the format, 0 for none (13.3) |
| 36 | u64 | length of the segment object; 36 B to 64 MiB |
| 44 | u32 | frameCount; at most 2^31 - 1 |

The body length MUST be `2 + 48 * count`. An entry's position is its segment index.

### 3.7 INDEX

`u32 count`, then `count` entries of 36 bytes. The body length MUST be `4 + 36 * count`, and `count` MUST be below
2^31.

| Offset | Type | Field | Rule |
|---|---|---|---|
| 0 | i32 | x | Chunk x. |
| 4 | i32 | z | Chunk z. |
| 8 | u8 | dim | Index into HEADER `dimensions`; MUST be below its size. |
| 9 | u8 | kind | 0 CHUNK, 1 ENTITIES, 2 POI. Other values MUST be rejected. |
| 10 | u8 | codec | Frame codec, section 5. |
| 11 | u8 | flags | Entry flags, below. |
| 12 | u16 | location | Segment index, or 0xFFFF for inline. |
| 14 | u16 | dictSlot | DICTS slot, or 0xFFFF for none. |
| 16 | u32 | offset | Inline: offset of the payload in INLINE. Segment: offset of the frame header. At most 2^31 - 1. |
| 20 | u32 | storedLength | Stored payload length. At most 2^31 - 1. |
| 24 | u32 | rawLength | Decoded length. At most 256 MiB. |
| 28 | u32 | crc32c | CRC32C of the stored payload. |
| 32 | i32 | dataVersion | The `DataVersion` of the frame's NBT (0 when the NBT has none). |

Entry flags: bit 0 TOMBSTONE, bit 1 LIGHT_STRIPPED, bit 2 HEIGHTMAPS_STRIPPED, bit 3 BASE_DELTA. Bits 4 to 7 MUST be 0.

**Order.** Entries MUST be strictly ascending by the key `(dim, kind, x >> 5, z >> 5, x, z)`, compared as signed
integers, where `>>` is an arithmetic shift (so negative chunks group with their floor region). A repeated key or a
descending pair MUST be rejected. Readers find entries by binary search.

**Data entries** (TOMBSTONE clear) MUST satisfy:

- `dictSlot` is 0xFFFF or below the DICTS count.
- codec ZSTD_DICT if and only if `dictSlot` is not 0xFFFF.
- codec ZSTD_BASE_DELTA if and only if BASE_DELTA is set, and only in a root with HAS_BASE.
- codec RAW implies `storedLength == rawLength`.
- Inline: `offset + storedLength` is within the decoded INLINE body.
- In a segment: `location` is below the SEGMENTS count, `offset >= 32`, and
  `offset + 28 + storedLength <= length - 4` of that segment.

**Tombstones** (TOMBSTONE set) carry no payload and MUST be encoded exactly so: flags = 0x01 (no other bit), codec 0
(RAW), location 0xFFFF, dictSlot 0xFFFF, offset 0, storedLength 0, rawLength 0, crc32c 0. Writers SHOULD write
dataVersion 0; readers ignore it. A tombstone means "this chunk has no data of this kind" and hides the base entry for
the same key (section 9). Writers SHOULD write tombstones only where a base has a live entry for the key.

**Unknown codecs** fail closed per chunk, not per root: a root whose entry has an unknown codec value still parses
(its bounds are checked as for any entry), but that one entry MUST NOT be read. A server reports it as an IO error for
that chunk and MUST NOT overwrite or regenerate the chunk.

### 3.8 INLINE

The concatenated payloads of the inline frames, without frame headers. An inline entry's `offset` is relative to the
start of the decoded INLINE body.

Packed inline: a writer MAY compress the whole INLINE body as one zstd stream (ZSTD flag on the section). The frames
of a packed root are then normally RAW. The reference writer packs only when enabled, when the raw body is at most a
configured size, and when packing makes it smaller.

### 3.9 STATS

An informational protobuf (counts and raw bytes per kind). Readers MUST NOT depend on it. The reference writer marks
it OPTIONAL.

## 4. Segment object

| Offset | Size | Field | Rule |
|---|---|---|---|
| 0 | 4 | magic | `NWFS` |
| 4 | u16 | major | MUST be 1. |
| 6 | u16 | minor | 0 in this version. |
| 8 | u32 | frameCount | Number of frames. |
| 12 | u32 | flags | bit 0 SORTED. Other bits MUST be 0. |
| 16 | 16 B | reserved | MUST be all zero. |
| 32 | ... | frames | `frameCount` frames, each a 28-byte frame header and its payload. |
| end - 4 | u32 | trailer | CRC32C of bytes [0, 32). |

A segment is 36 bytes to 64 MiB. Its id is the SHA-256 of the whole object; a reader that has the whole object MUST
check it against the id it was asked for. A reader of a byte range relies on the frame CRC instead.

The frames MUST tile the area between offset 32 and the trailer exactly: `frameCount` frames, no gap, no trailing
bytes. With SORTED, frame keys MUST be strictly ascending in INDEX order. The reference writer always sorts.

Frame header (28 bytes):

| Offset | Type | Field |
|---|---|---|
| 0 | u16 | marker 0x464E (bytes 4E 46, "NF") |
| 2 | u8 | codec |
| 3 | u8 | flags (entry flags; TOMBSTONE MUST NOT be set) |
| 4 | u32 | rawLength |
| 8 | u32 | storedLength |
| 12 | u32 | crc32c of the payload |
| 16 | i32 | x |
| 20 | i32 | z |
| 24 | u8 | dim |
| 25 | u8 | kind |
| 26 | u16 | dictSlot |

The payload follows the header. The frame header repeats the index data (all but `dataVersion`) so an index can be
rebuilt from segments alone. A reader MUST treat any difference between a frame header and the index entry that
points at it (key, codec, flags, lengths, crc32c, dictSlot) as corruption. Frame headers obey the data-entry rules of
3.7 (dictionary slot and codec pairing, BASE_DELTA pairing, RAW lengths) and the 256 MiB raw limit.

When an index is rebuilt from several segments and a key occurs in more than one, the segment with the higher index
wins.

## 5. Frames and codecs

A frame payload encodes the NBT of one chunk, one entity chunk or one POI chunk, as the server serializes them, minus
the tags removed under section 7.

| Codec | Name | Payload |
|---|---|---|
| 0 | RAW | The NBT bytes. `storedLength == rawLength`. |
| 1 | ZSTD | One zstd frame, no dictionary. |
| 2 | ZSTD_DICT | One zstd frame compressed with the dictionary in slot `dictSlot`. |
| 3 | ZSTD_BASE_DELTA | One zstd frame compressed with the base snapshot's decoded NBT for the same `(dim, kind, x, z)` loaded as a raw-content dictionary. BASE_DELTA set, no dictionary slot. |

zstd frames written by NWF MUST record the content size and MUST carry the zstd content checksum. Frames compressed
with a trained dictionary MUST record its dictionary id. For ZSTD_BASE_DELTA the base content MUST NOT start with the
zstd dictionary magic (37 A4 30 EC), since a decoder would read it as a trained dictionary; such a frame cannot be
written.

Decoding a frame:

1. The CRC32C of the stored payload MUST equal `crc32c`. This is checked before decompression.
2. The zstd frame's declared content size MUST equal `rawLength`; it is checked before allocating.
3. Decompression MUST produce exactly `rawLength` bytes. A bad content checksum, a dictionary id mismatch or any zstd
   error fails closed.

The NBT of CHUNK and ENTITIES frames carries its own coordinates: CHUNK root ints `xPos` and `zPos`, ENTITIES root int
array `Position` of length 2. When present they MUST equal the frame key; writers MUST refuse a mismatch and checkers
report it as corruption. POI records carry block positions (`Sections.<y>.Records[].pos`). The server never validates
them, so writers MUST accept a POI frame whatever its record positions are, and a checker that finds a record outside
the frame key's chunk reports it as a warning, not as corruption. `dataVersion` in the index MUST equal the NBT's root
int `DataVersion` (0 when it has none).

## 6. Checksums and verification

- Root: `headerCrc` over the fixed header and TOC, and a CRC32C per section body.
- Segment: SHA-256 of the whole object (its id) and a trailer CRC32C over its 32-byte header.
- Frame: CRC32C of the stored payload in the index entry and, in a segment, in the frame header; the zstd content
  checksum inside every compressed payload.
- Dictionary: SHA-256 of its bytes, checked before first use.
- Base snapshot: the root's SHA-256 equals the referencing world's `base_root_sha`.

A reader MUST verify a whole root (every check of section 3) before using any part of it.

A checker tells an object that does not exist from one it could not read. A root, segment or base snapshot (or a base
frame a ZSTD_BASE_DELTA entry needs) that the store answers definitely does not exist is a dangling reference, which is
corruption, as is one that exists but fails its checks. One whose read failed (timeout, connection error, server error)
is never reported as damage: it makes the report incomplete, which means "re-run the check to cover it". The checker
itself does not retry.

A dictionary is a shared object, not part of the world that lists it (section 8). A checker reports a dictionary that
does not exist or could not be read as unavailable, and one that was read but fails its SHA-256 (or does not load as a
dictionary) as a damaged dictionary, naming it; neither is corruption of the world. The frames that need it are
unchecked, and a reader still fails closed: it MUST NOT decode them without it, nor treat the world as empty.

## 7. Light and heightmap policy

HEADER `light_policy` and `heightmap_policy` are the world's settings for frames it writes. Each frame's own flags are
authoritative; a root may hold frames written under different settings.

Under light STRIP the encoder removes from a CHUNK frame's NBT:

- in every compound of the root list `sections`: `BlockLight`, `SkyLight`, `starlight.blocklight_state`,
  `starlight.skylight_state`;
- at the root: `isLightOn`, `starlight.light_version`.

Under heightmap STRIP it removes the root tag `Heightmaps`.

Every other byte stays identical and in the same order. The filter streams the NBT and does not rebuild it. A frame
encoded under STRIP has LIGHT_STRIPPED (or HEIGHTMAPS_STRIPPED) set, whether or not the tags were present. Without
`isLightOn` the server treats the chunk as unlit and relights it on load; missing heightmaps are recomputed.

Old-layout chunks: a chunk whose root holds a compound named `Level` (the layout before Minecraft 1.18) is passed
through unchanged and is not flagged. Its light lives inside `Level`, which the filter does not enter, and the
server's data fixer upgrades the chunk on load.

ENTITIES and POI frames are never filtered.

The filter rejects malformed NBT: a root that is not a compound, an unknown tag type, a negative length, truncation,
trailing bytes after the root compound, or nesting deeper than 512.

## 8. Dictionaries

A dictionary object is the raw bytes of a zstd dictionary, named by their SHA-256. It is immutable. A root lists the
dictionaries its frames use in DICTS; a frame selects one by slot. A decoder MUST verify the SHA-256 of a dictionary's
bytes before its first use and MUST NOT use a dictionary that fails.

Dictionaries are meant to be trained per content class and kind from filtered (section 7) frames of that class; the
reference trainer uses zstd's default trainer with a suggested size of 112 KiB.

## 9. Base snapshots and tombstones

A base snapshot is a root with the TEMPLATE flag. It is immutable and is referenced by the SHA-256 of its bytes. A
world created from it has HAS_BASE and `base_root_sha` set and stores only the chunks it changed.

Lookup of a key in a world with HAS_BASE: the world's own entry, a tombstone included, wins; only when the world has
no entry for the key is the base INDEX consulted. A tombstone therefore deletes the base's chunk for that key. A base
MUST NOT itself have a base (no HAS_BASE), and a reader MUST reject such a base.

ZSTD_BASE_DELTA frames decode against the base's decoded frame for the same key; that base frame MUST exist.

## 10. Versioning

- Root and segment carry `major` and `minor`. A breaking change increments `major`; readers refuse other majors.
- A compatible change increments `minor`: new sections with the OPTIONAL flag, or new HEADER fields with new numbers.
  Readers accept any minor and skip what they do not know.
- Unknown flag bits, reserved bytes that are not zero, and unknown section types without OPTIONAL are rejected, so
  they stay available for future versions. The exception is a section of unknown type with OPTIONAL set: it is
  skipped whole, and its TOC flag bits and `dictSlot` are not checked (3.2).
- Codec values 4 to 255 are reserved; an unknown codec fails closed for that chunk only (3.7).
- HEADER field numbers 10 to 19 and 33 and above are unassigned.

## 11. Limits

| Item | Limit |
|---|---|
| Root object | 64 MiB |
| Segment object | 64 MiB (writers should aim at about 4 MiB) |
| Decoded frame or section body | 256 MiB |
| Decoded section bodies of one root, together | 256 MiB |
| INDEX entries | below 2^31 |
| Sections per root | 1 to 32 |
| Dictionaries, segments per root | 65,535 (slot 0xFFFF means none) |
| Dimensions | 256 |

The two decoded-size limits cover the sections a reader decodes. A section of unknown type with OPTIONAL set is skipped
without decoding, so its `rawLength` counts toward neither (3.2).

## 12. Transit envelope (non-normative)

Inside one process, a frame can travel between the encoder and the code that stores it in this envelope, which also
carries the `dataVersion` that a segment frame header lacks. It is never stored.

| Offset | Type | Field |
|---|---|---|
| 0 | u16 | marker 0x544E ("NT") |
| 2 | u16 | version = 1 |
| 4 | u32 | dataVersion |
| 8 | 28 B | frame header (section 4) |
| 36 | ... | payload, exactly `storedLength` bytes |

The receiver checks the marker, version, frame header rules, length and payload CRC32C.

## 13. Storage layout guidance (non-normative)

NWF does not prescribe a storage backend. These are the practices the reference store follows on an object store
with conditional writes.

### 13.1 One object or segments

- **Inline.** While the frames plus the raw INDEX fit an inline budget (the reference store uses 256 KiB), keep every
  frame in INLINE. The world is then one object: one read to load, one write to save.
- **Segmented.** Above the budget, write frames into sorted segments of about 4 MiB and keep INLINE for frames that
  changed since. A save then writes the root and, occasionally, one new segment, so the bytes written per save follow
  the number of changed chunks, not the size of the world.
- **Compaction.** When most of the bytes in a world's segments belong to superseded frames, or the segment count
  grows, rewrite the live frames into fresh segments and commit a new root.

### 13.2 Write order

Write every new segment before the root that references it, and write the root last. Segments are immutable and
content-addressed, so writing one twice is harmless; a root never points at a segment that does not exist yet. Make
the root write conditional on the root you read (for example by ETag), increment `generation`, and set
`parent_root_sha` to the SHA-256 of the previous root, so concurrent writers are detected and the history is a chain.

### 13.3 Naming

Store a world's segments under its `segment_prefix` and their SHA-256 (for example `seg/<prefix>/<sha256>`), so a
world's segments are private to it. `salt` allows a second name for the same content (`<sha256>-<salt>`) when a store
cannot overwrite a name it has seen before; 0 means the plain name. Name dictionaries and base snapshots by their
SHA-256. Base snapshots, their segments and dictionaries are shared and never rewritten.

### 13.4 Garbage collection

Segments become garbage when no root references them any more. A collector marks from every live root (and any
retained older root versions and base snapshots), then deletes only unmarked objects older than a safety window, and
aborts without deleting anything if any mark step failed.
