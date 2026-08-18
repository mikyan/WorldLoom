package io.worldloom.content.generation

import kotlinx.serialization.Serializable

const val MAX_SOURCE_CHARACTERS: Int = 500_000
const val MAX_BRIEF_CHARACTERS: Int = 5_000

@Serializable
enum class SourceFormat { BRIEF, TXT, EPUB }

@Serializable
data class SourceLocator(
    val sourcePath: String? = null,
    val epubHref: String? = null,
    val paragraphPath: String? = null,
    val startCharacter: Int,
    val endCharacterExclusive: Int,
)

@Serializable
data class SourceSection(
    val id: String,
    val title: String,
    val text: String,
    val locator: SourceLocator,
)

@Serializable
data class SourceChunk(
    val id: String,
    val sectionId: String,
    val text: String,
    val locator: SourceLocator,
)

@Serializable
data class SourceDocument(
    val id: String,
    val format: SourceFormat,
    val title: String,
    val author: String? = null,
    val charset: String? = null,
    val characterCount: Int,
    val sections: List<SourceSection>,
    val chunks: List<SourceChunk>,
)

enum class SourceIngestProblemCode {
    EMPTY_SOURCE,
    SOURCE_TOO_LARGE,
    UNSUPPORTED_ENCODING,
    INVALID_TXT,
    INVALID_EPUB,
    CANCELLED,
}

data class SourceIngestProblem(val code: SourceIngestProblemCode, val message: String)

sealed interface SourceIngestResult {
    data class Success(val document: SourceDocument) : SourceIngestResult
    data class Failure(val problem: SourceIngestProblem) : SourceIngestResult
}

fun interface CancellationProbe {
    fun isCancelled(): Boolean
}

fun interface LegacyTextDecoder {
    /** Returns null when bytes are not valid GB18030. */
    fun decodeGb18030(source: ByteArray): String?
}

internal fun normalizeSourceText(source: String): String = buildString(source.length) {
    var previousWasCarriageReturn = false
    source.forEach { character ->
        when {
            character == '\r' -> {
                append('\n')
                previousWasCarriageReturn = true
            }
            character == '\n' && previousWasCarriageReturn -> previousWasCarriageReturn = false
            character == '\n' || character == '\t' || character.code >= 0x20 -> {
                append(character)
                previousWasCarriageReturn = false
            }
            else -> previousWasCarriageReturn = false
        }
    }
}.lineSequence().joinToString("\n") { it.trimEnd() }.trim()

internal fun unicodeCharacterCount(source: String): Int {
    var count = 0
    var index = 0
    while (index < source.length) {
        val first = source[index]
        index += if (first.isHighSurrogate() && index + 1 < source.length && source[index + 1].isLowSurrogate()) 2 else 1
        count += 1
    }
    return count
}

internal fun chunkSections(
    documentId: String,
    sections: List<SourceSection>,
    maximumChunkCharacters: Int = 4_000,
): List<SourceChunk> {
    require(maximumChunkCharacters > 0) { "Chunk size must be positive" }
    return sections.flatMapIndexed { sectionIndex, section ->
        if (section.text.isEmpty()) return@flatMapIndexed emptyList()
        buildList {
            var offset = 0
            var chunkIndex = 0
            while (offset < section.text.length) {
                var end = minOf(section.text.length, offset + maximumChunkCharacters)
                if (end < section.text.length) {
                    val paragraphBreak = section.text.lastIndexOf('\n', end - 1)
                    if (paragraphBreak > offset + maximumChunkCharacters / 2) end = paragraphBreak + 1
                }
                add(
                    SourceChunk(
                        id = "$documentId.$sectionIndex.$chunkIndex",
                        sectionId = section.id,
                        text = section.text.substring(offset, end).trim(),
                        locator = section.locator.copy(
                            startCharacter = section.locator.startCharacter + offset,
                            endCharacterExclusive = section.locator.startCharacter + end,
                        ),
                    ),
                )
                offset = end
                chunkIndex += 1
            }
        }
    }
}
