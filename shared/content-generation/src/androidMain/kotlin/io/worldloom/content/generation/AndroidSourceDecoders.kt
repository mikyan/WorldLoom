package io.worldloom.content.generation

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

object AndroidGb18030Decoder : LegacyTextDecoder {
    override fun decodeGb18030(source: ByteArray): String? = try {
        Charset.forName("GB18030").newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(source)).toString()
    } catch (_: Exception) {
        null
    }
}

object AndroidEpubArchiveReader : EpubArchiveReader {
    override fun read(source: ByteArray): EpubArchiveReadResult = try {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(source)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                if (path.startsWith('/') || '\\' in path || path.split('/').any { it == ".." }) {
                    return EpubArchiveReadResult.Failure("EPUB entry path is unsafe")
                }
                if (!entry.isDirectory) {
                    val bytes = zip.readBytes()
                    total += bytes.size
                    if (total > 128L * 1024 * 1024) return EpubArchiveReadResult.Failure("EPUB is too large")
                    if (entries.put(path, bytes) != null) return EpubArchiveReadResult.Failure("EPUB entry is duplicated")
                }
                zip.closeEntry()
            }
        }
        EpubArchiveReadResult.Success(entries)
    } catch (_: Exception) {
        EpubArchiveReadResult.Failure("EPUB ZIP data is invalid")
    }
}
