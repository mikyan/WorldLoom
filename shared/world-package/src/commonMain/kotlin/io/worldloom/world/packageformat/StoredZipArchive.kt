package io.worldloom.world.packageformat

private const val LOCAL_FILE_HEADER = 0x04034b50L
private const val CENTRAL_FILE_HEADER = 0x02014b50L
private const val END_OF_CENTRAL_DIRECTORY = 0x06054b50L
private const val UTF8_FLAG = 0x0800
private const val STORED_METHOD = 0

data class ArchiveEntry(
    val path: String,
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is ArchiveEntry && path == other.path && content.contentEquals(other.content)
    override fun hashCode(): Int = 31 * path.hashCode() + content.contentHashCode()
}

sealed interface ArchiveResult {
    data class Success(val entries: List<ArchiveEntry>) : ArchiveResult
    data class Failure(val message: String) : ArchiveResult
}

/** A portable ZIP subset using the standard STORED method; unsupported compression is rejected explicitly. */
object StoredZipArchive {
    fun encode(entries: List<ArchiveEntry>): ByteArray {
        require(entries.isNotEmpty()) { "A world package archive must not be empty" }
        require(entries.size <= 4_096) { "A world package contains too many entries" }
        entries.forEach { requireSafePath(it.path) }
        require(entries.map(ArchiveEntry::path).distinct().size == entries.size) { "Archive entry paths must be unique" }
        val writer = ByteWriter()
        val central = mutableListOf<CentralRecord>()
        entries.sortedBy(ArchiveEntry::path).forEach { entry ->
            val name = entry.path.encodeToByteArray()
            val crc = crc32(entry.content)
            val offset = writer.size
            writer.u32(LOCAL_FILE_HEADER)
            writer.u16(20)
            writer.u16(UTF8_FLAG)
            writer.u16(STORED_METHOD)
            writer.u16(0)
            writer.u16(0)
            writer.u32(crc)
            writer.u32(entry.content.size.toLong())
            writer.u32(entry.content.size.toLong())
            writer.u16(name.size)
            writer.u16(0)
            writer.bytes(name)
            writer.bytes(entry.content)
            central += CentralRecord(name, crc, entry.content.size, offset)
        }
        val centralOffset = writer.size
        central.forEach { record ->
            writer.u32(CENTRAL_FILE_HEADER)
            writer.u16(20)
            writer.u16(20)
            writer.u16(UTF8_FLAG)
            writer.u16(STORED_METHOD)
            writer.u16(0)
            writer.u16(0)
            writer.u32(record.crc)
            writer.u32(record.size.toLong())
            writer.u32(record.size.toLong())
            writer.u16(record.name.size)
            writer.u16(0)
            writer.u16(0)
            writer.u16(0)
            writer.u16(0)
            writer.u32(0)
            writer.u32(record.offset.toLong())
            writer.bytes(record.name)
        }
        val centralSize = writer.size - centralOffset
        writer.u32(END_OF_CENTRAL_DIRECTORY)
        writer.u16(0)
        writer.u16(0)
        writer.u16(central.size)
        writer.u16(central.size)
        writer.u32(centralSize.toLong())
        writer.u32(centralOffset.toLong())
        writer.u16(0)
        return writer.toByteArray()
    }

    fun decode(
        source: ByteArray,
        maxEntries: Int = 4_096,
        maxUncompressedBytes: Long = 128L * 1024 * 1024,
    ): ArchiveResult = try {
        val eocd = findEndRecord(source)
        val entryCount = source.u16(eocd + 10)
        if (entryCount > maxEntries) return ArchiveResult.Failure("Archive contains too many entries")
        val centralSize = source.u32(eocd + 12).toIntChecked()
        val centralOffset = source.u32(eocd + 16).toIntChecked()
        if (centralOffset + centralSize > eocd) return ArchiveResult.Failure("Archive central directory is invalid")
        val entries = mutableListOf<ArchiveEntry>()
        val paths = mutableSetOf<String>()
        var total = 0L
        var cursor = centralOffset
        repeat(entryCount) {
            if (source.u32(cursor) != CENTRAL_FILE_HEADER) return ArchiveResult.Failure("Archive entry header is invalid")
            val flags = source.u16(cursor + 8)
            val method = source.u16(cursor + 10)
            if (flags and 1 != 0) return ArchiveResult.Failure("Encrypted archive entries are not supported")
            if (method != STORED_METHOD) return ArchiveResult.Failure("Only STORED ZIP entries are supported in v1")
            val expectedCrc = source.u32(cursor + 16)
            val compressedSize = source.u32(cursor + 20).toIntChecked()
            val uncompressedSize = source.u32(cursor + 24).toIntChecked()
            if (compressedSize != uncompressedSize) return ArchiveResult.Failure("Stored entry sizes do not match")
            val nameLength = source.u16(cursor + 28)
            val extraLength = source.u16(cursor + 30)
            val commentLength = source.u16(cursor + 32)
            val localOffset = source.u32(cursor + 42).toIntChecked()
            val nameStart = cursor + 46
            val name = source.sliceChecked(nameStart, nameLength).decodeToString()
            requireSafePath(name)
            if (!paths.add(name)) return ArchiveResult.Failure("Archive entry path is duplicated: $name")
            if (source.u32(localOffset) != LOCAL_FILE_HEADER) return ArchiveResult.Failure("Local entry header is invalid")
            val localNameLength = source.u16(localOffset + 26)
            val localExtraLength = source.u16(localOffset + 28)
            val localName = source.sliceChecked(localOffset + 30, localNameLength).decodeToString()
            if (localName != name) return ArchiveResult.Failure("Archive entry names do not match")
            val dataStart = localOffset + 30 + localNameLength + localExtraLength
            val content = source.sliceChecked(dataStart, compressedSize)
            if (crc32(content) != expectedCrc) return ArchiveResult.Failure("Archive entry CRC is invalid: $name")
            total += uncompressedSize
            if (total > maxUncompressedBytes) return ArchiveResult.Failure("Archive exceeds the uncompressed size limit")
            entries += ArchiveEntry(name, content)
            cursor = nameStart + nameLength + extraLength + commentLength
        }
        ArchiveResult.Success(entries)
    } catch (error: IllegalArgumentException) {
        ArchiveResult.Failure(error.message ?: "Archive is invalid")
    } catch (_: IndexOutOfBoundsException) {
        ArchiveResult.Failure("Archive is truncated")
    }

    private fun findEndRecord(source: ByteArray): Int {
        require(source.size >= 22) { "Archive is too short" }
        val minimum = maxOf(0, source.size - 65_557)
        for (index in source.size - 22 downTo minimum) {
            if (source.u32(index) == END_OF_CENTRAL_DIRECTORY) return index
        }
        throw IllegalArgumentException("Archive end record is missing")
    }
}

private data class CentralRecord(val name: ByteArray, val crc: Long, val size: Int, val offset: Int)

private class ByteWriter {
    private val bytes = mutableListOf<Byte>()
    val size: Int get() = bytes.size
    fun u16(value: Int) { repeat(2) { shift -> bytes += ((value ushr (shift * 8)) and 0xff).toByte() } }
    fun u32(value: Long) { repeat(4) { shift -> bytes += ((value ushr (shift * 8)) and 0xff).toByte() } }
    fun bytes(value: ByteArray) { bytes.addAll(value.toList()) }
    fun toByteArray(): ByteArray = bytes.toByteArray()
}

private fun ByteArray.u16(offset: Int): Int {
    require(offset >= 0 && offset + 2 <= size) { "Archive is truncated" }
    return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
}

private fun ByteArray.u32(offset: Int): Long {
    require(offset >= 0 && offset + 4 <= size) { "Archive is truncated" }
    var value = 0L
    repeat(4) { shift -> value = value or ((this[offset + shift].toLong() and 0xff) shl (shift * 8)) }
    return value
}

private fun ByteArray.sliceChecked(offset: Int, length: Int): ByteArray {
    require(offset >= 0 && length >= 0 && offset.toLong() + length <= size.toLong()) { "Archive is truncated" }
    return copyOfRange(offset, offset + length)
}

private fun Long.toIntChecked(): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "Archive entry is too large" }
    return toInt()
}

private fun requireSafePath(path: String) {
    require(path.isNotBlank()) { "Archive entry path must not be blank" }
    require(!path.startsWith('/') && '\\' !in path) { "Archive entry path must be relative" }
    require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Archive entry path is unsafe" }
}

private fun crc32(bytes: ByteArray): Long {
    var crc = 0xffffffffL
    bytes.forEach { byte ->
        crc = crc xor (byte.toLong() and 0xff)
        repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xedb88320L else crc ushr 1 }
    }
    return (crc xor 0xffffffffL) and 0xffffffffL
}
