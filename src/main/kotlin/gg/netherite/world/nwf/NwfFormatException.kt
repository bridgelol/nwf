package gg.netherite.world.nwf

import java.io.IOException

/** Why NWF bytes were rejected. Every reason fails closed: the root, segment or frame must not be used. */
enum class NwfFormatError {
    BAD_MAGIC,

    /** Major other than [NwfFormat.MAJOR]. */
    UNSUPPORTED_MAJOR,

    /** A root, TOC, segment or frame flag bit this reader does not know (must be zero). */
    UNKNOWN_FLAGS,

    /** Section count outside 1..32, a required section missing, or a section repeated. */
    BAD_SECTIONS,

    /** An unknown section type without the OPTIONAL flag. */
    UNKNOWN_REQUIRED_SECTION,

    /** An offset or length points outside the object, sections overlap or leave gaps. */
    OUT_OF_BOUNDS,

    /** Bytes end before a structure is complete. */
    TRUNCATED,

    /** A size limit of the format is exceeded. */
    LIMIT_EXCEEDED,

    /** `headerCrc`, a section CRC, a frame CRC or a segment trailer CRC differs. */
    CRC_MISMATCH,

    /** The SHA-256 of a whole segment differs from its id. */
    SHA_MISMATCH,

    /** INDEX entries are not strictly sorted by [ChunkKey] order, or a key repeats. */
    UNSORTED_INDEX,

    /** A frame codec id this reader does not know: FAIL_CLOSED for that chunk. */
    UNKNOWN_CODEC,

    /** A dictionary slot, segment index or base reference that the root does not resolve. */
    DANGLING_REFERENCE,

    /** A base snapshot that itself has a base. */
    NESTED_BASE,

    /** A decoded frame whose NBT does not parse or whose coordinates differ from its key. */
    BAD_PAYLOAD,

    /** The HEADER protobuf does not parse. */
    INVALID_HEADER,
}

/**
 * The only exception NWF readers throw for malformed input, fuzzed roots and segments included. It is an [IOException]
 * so a server's chunk storage hook reports it as an IO error: the server logs it and leaves the chunk unloaded, and
 * never regenerates or overwrites it.
 */
class NwfFormatException(
    val reason: NwfFormatError,
    message: String,
    cause: Throwable? = null,
) : IOException("$reason: $message", cause)
