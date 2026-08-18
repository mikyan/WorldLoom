package io.worldloom.content.generation

class TxtIngestor(
    private val legacyDecoder: LegacyTextDecoder = LegacyTextDecoder { null },
) {
    suspend fun ingest(
        documentId: String,
        fileName: String,
        source: ByteArray,
        cancellation: CancellationProbe = CancellationProbe { false },
    ): SourceIngestResult {
        if (cancellation.isCancelled()) return cancelled()
        if (source.isEmpty()) return failure(SourceIngestProblemCode.EMPTY_SOURCE, "TXT source is empty")
        val decoded = decode(source)
            ?: return failure(SourceIngestProblemCode.UNSUPPORTED_ENCODING, "TXT is not valid UTF-8, UTF-16, or GB18030")
        if (cancellation.isCancelled()) return cancelled()
        val text = normalizeSourceText(decoded.first)
        if (text.isBlank()) return failure(SourceIngestProblemCode.EMPTY_SOURCE, "TXT contains no readable text")
        val characterCount = unicodeCharacterCount(text)
        if (characterCount > MAX_SOURCE_CHARACTERS) {
            return failure(SourceIngestProblemCode.SOURCE_TOO_LARGE, "TXT exceeds $MAX_SOURCE_CHARACTERS Unicode characters")
        }
        val sections = inferSections(documentId, fileName, text)
        return SourceIngestResult.Success(
            SourceDocument(
                id = documentId,
                format = SourceFormat.TXT,
                title = fileName.substringBeforeLast('.').ifBlank { fileName },
                charset = decoded.second,
                characterCount = characterCount,
                sections = sections,
                chunks = chunkSections(documentId, sections),
            ),
        )
    }

    private fun decode(source: ByteArray): Pair<String, String>? = when {
        source.startsWith(0xef, 0xbb, 0xbf) -> source.copyOfRange(3, source.size).decodeUtf8Strict()?.let { it to "UTF-8 BOM" }
        source.startsWith(0xff, 0xfe) -> decodeUtf16(source, littleEndian = true)?.let { it to "UTF-16LE" }
        source.startsWith(0xfe, 0xff) -> decodeUtf16(source, littleEndian = false)?.let { it to "UTF-16BE" }
        else -> source.decodeUtf8Strict()?.let { it to "UTF-8" }
            ?: legacyDecoder.decodeGb18030(source)?.let { it to "GB18030" }
    }

    private fun inferSections(documentId: String, fileName: String, text: String): List<SourceSection> {
        val lines = text.lines()
        val sections = mutableListOf<SourceSection>()
        var title = fileName.substringBeforeLast('.').ifBlank { "Text" }
        var start = 0
        var body = mutableListOf<String>()
        var absolute = 0
        fun flush(end: Int) {
            val value = body.joinToString("\n").trim()
            if (value.isNotEmpty()) {
                sections += SourceSection(
                    id = "$documentId.section.${sections.size}",
                    title = title,
                    text = value,
                    locator = SourceLocator(fileName, startCharacter = start, endCharacterExclusive = end),
                )
            }
            body = mutableListOf()
        }
        lines.forEachIndexed { index, line ->
            val heading = line.trim().removePrefix("#").trim()
            val surroundedBySpace = line.isNotBlank() &&
                (index == 0 || lines[index - 1].isBlank()) &&
                (index == lines.lastIndex || lines[index + 1].isBlank())
            val looksLikeHeading = line.trimStart().startsWith("#") ||
                (surroundedBySpace && heading.length in 1..40 && !heading.endsWith('。'))
            if (looksLikeHeading) {
                flush(absolute)
                title = heading
                start = absolute + line.length + 1
            } else {
                body += line
            }
            absolute += line.length + if (index == lines.lastIndex) 0 else 1
        }
        flush(text.length)
        if (sections.isEmpty()) {
            sections += SourceSection(
                id = "$documentId.section.0",
                title = title,
                text = text,
                locator = SourceLocator(fileName, startCharacter = 0, endCharacterExclusive = text.length),
            )
        }
        return sections
    }
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { this[it].toInt() and 0xff == prefix[it] }

private fun ByteArray.decodeUtf8Strict(): String? = try {
    decodeToString(throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    null
}

private fun decodeUtf16(source: ByteArray, littleEndian: Boolean): String? {
    if ((source.size - 2) % 2 != 0) return null
    return buildString((source.size - 2) / 2) {
        var index = 2
        while (index < source.size) {
            val first = source[index].toInt() and 0xff
            val second = source[index + 1].toInt() and 0xff
            append(if (littleEndian) (first or (second shl 8)).toChar() else ((first shl 8) or second).toChar())
            index += 2
        }
    }
}

private fun failure(code: SourceIngestProblemCode, message: String) =
    SourceIngestResult.Failure(SourceIngestProblem(code, message))

private fun cancelled() = failure(SourceIngestProblemCode.CANCELLED, "Source ingestion was cancelled")
