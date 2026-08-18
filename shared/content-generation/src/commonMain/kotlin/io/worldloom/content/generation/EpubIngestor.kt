package io.worldloom.content.generation

import io.worldloom.world.packageformat.ArchiveResult
import io.worldloom.world.packageformat.StoredZipArchive

sealed interface EpubArchiveReadResult {
    data class Success(val entries: Map<String, ByteArray>) : EpubArchiveReadResult
    data class Failure(val message: String) : EpubArchiveReadResult
}

fun interface EpubArchiveReader {
    fun read(source: ByteArray): EpubArchiveReadResult
}

/** Portable reader for uncompressed EPUB fixtures and Worldloom-authored source archives. */
object StoredEpubArchiveReader : EpubArchiveReader {
    override fun read(source: ByteArray): EpubArchiveReadResult = when (val archive = StoredZipArchive.decode(source)) {
        is ArchiveResult.Success -> EpubArchiveReadResult.Success(archive.entries.associate { it.path to it.content })
        is ArchiveResult.Failure -> EpubArchiveReadResult.Failure(archive.message)
    }
}

class EpubIngestor(
    private val archiveReader: EpubArchiveReader,
) {
    suspend fun ingest(
        documentId: String,
        fileName: String,
        source: ByteArray,
        cancellation: CancellationProbe = CancellationProbe { false },
    ): SourceIngestResult {
        if (cancellation.isCancelled()) return ingestCancelled()
        val entries = when (val read = archiveReader.read(source)) {
            is EpubArchiveReadResult.Success -> read.entries
            is EpubArchiveReadResult.Failure -> return ingestFailure(SourceIngestProblemCode.INVALID_EPUB, read.message)
        }
        val container = entries["META-INF/container.xml"]?.decodeUtf8()
            ?: return ingestFailure(SourceIngestProblemCode.INVALID_EPUB, "EPUB container.xml is missing")
        val opfPath = ROOTFILE_PATTERN.find(container)?.groupValues?.get(1)
            ?: return ingestFailure(SourceIngestProblemCode.INVALID_EPUB, "EPUB rootfile is missing")
        val opf = entries[opfPath]?.decodeUtf8()
            ?: return ingestFailure(SourceIngestProblemCode.INVALID_EPUB, "EPUB package document is missing")
        val base = opfPath.substringBeforeLast('/', "")
        val manifest = ITEM_PATTERN.findAll(opf).mapNotNull { match ->
            val attributes = attributes(match.groupValues[1])
            val id = attributes["id"] ?: return@mapNotNull null
            val href = attributes["href"] ?: return@mapNotNull null
            id to resolvePath(base, decodeEntities(href))
        }.toMap()
        val spine = ITEMREF_PATTERN.findAll(opf).mapNotNull { match ->
            attributes(match.groupValues[1])["idref"]
        }.toList()
        if (spine.isEmpty()) return ingestFailure(SourceIngestProblemCode.INVALID_EPUB, "EPUB spine is empty")
        val title = tagText(opf, "title")?.ifBlank { null } ?: fileName.substringBeforeLast('.')
        val author = tagText(opf, "creator")?.ifBlank { null }
        val sections = mutableListOf<SourceSection>()
        var absolute = 0
        spine.forEachIndexed { index, id ->
            if (cancellation.isCancelled()) return ingestCancelled()
            val href = manifest[id]
                ?: return ingestFailure(SourceIngestProblemCode.INVALID_EPUB, "EPUB spine references a missing item: $id")
            val xhtml = entries[href]?.decodeUtf8()
                ?: return ingestFailure(SourceIngestProblemCode.INVALID_EPUB, "EPUB content entry is missing: $href")
            val text = normalizeSourceText(extractXhtmlText(xhtml))
            if (text.isNotBlank()) {
                val sectionTitle = tagText(xhtml, "h1")
                    ?: tagText(xhtml, "h2")
                    ?: tagText(xhtml, "title")
                    ?: "Section ${index + 1}"
                sections += SourceSection(
                    id = "$documentId.section.${sections.size}",
                    title = sectionTitle,
                    text = text,
                    locator = SourceLocator(
                        sourcePath = fileName,
                        epubHref = href,
                        paragraphPath = "/html/body",
                        startCharacter = absolute,
                        endCharacterExclusive = absolute + text.length,
                    ),
                )
                absolute += text.length
            }
        }
        if (sections.isEmpty()) return ingestFailure(SourceIngestProblemCode.EMPTY_SOURCE, "EPUB contains no readable text")
        val count = sections.sumOf { unicodeCharacterCount(it.text) }
        if (count > MAX_SOURCE_CHARACTERS) {
            return ingestFailure(SourceIngestProblemCode.SOURCE_TOO_LARGE, "EPUB exceeds $MAX_SOURCE_CHARACTERS Unicode characters")
        }
        return SourceIngestResult.Success(
            SourceDocument(
                id = documentId,
                format = SourceFormat.EPUB,
                title = decodeEntities(title),
                author = author?.let(::decodeEntities),
                characterCount = count,
                sections = sections,
                chunks = chunkSections(documentId, sections),
            ),
        )
    }
}

private val ROOTFILE_PATTERN = Regex("""<rootfile\b[^>]*\bfull-path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val ITEM_PATTERN = Regex("""<item\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
private val ITEMREF_PATTERN = Regex("""<itemref\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
private val ATTRIBUTE_PATTERN = Regex("""([\w:-]+)\s*=\s*["']([^"']*)["']""")

private fun attributes(source: String): Map<String, String> = ATTRIBUTE_PATTERN.findAll(source).associate {
    it.groupValues[1].lowercase() to it.groupValues[2]
}

private fun resolvePath(base: String, href: String): String {
    val segments = mutableListOf<String>()
    (if (base.isBlank()) href else "$base/$href").substringBefore('#').split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> require(segments.isNotEmpty()) { "EPUB path escapes the archive" }.also { segments.removeLast() }
            else -> segments += segment
        }
    }
    return segments.joinToString("/")
}

private fun ByteArray.decodeUtf8(): String? = try {
    decodeToString(throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    null
}

private fun tagText(source: String, localName: String): String? {
    val pattern = Regex("""<([\w-]+:)?$localName\b[^>]*>(.*?)</([\w-]+:)?$localName>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    return pattern.find(source)?.groupValues?.get(2)?.let(::extractXhtmlText)?.trim()
}

private fun extractXhtmlText(source: String): String = decodeEntities(
    source
        .replace(Regex("""<(script|style)\b[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("""<(br|p|div|h[1-6]|li)\b[^>]*>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), ""),
)

private fun decodeEntities(source: String): String = source
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")
    .replace(Regex("""&#(\d+);""")) { match -> match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value }

private fun ingestFailure(code: SourceIngestProblemCode, message: String) =
    SourceIngestResult.Failure(SourceIngestProblem(code, message))

private fun ingestCancelled() = ingestFailure(SourceIngestProblemCode.CANCELLED, "Source ingestion was cancelled")
