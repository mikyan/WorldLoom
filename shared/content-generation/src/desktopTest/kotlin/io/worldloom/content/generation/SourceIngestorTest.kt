package io.worldloom.content.generation

import io.worldloom.definition.DefinitionId
import io.worldloom.rules.module.registry.StandardRuleModules
import io.worldloom.world.packageformat.ArchiveResult
import io.worldloom.world.packageformat.StoredZipArchive
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceIngestorTest {
    @Test
    fun txtDetectsRequiredEncodingsAndKeepsSectionLocations() = runTest {
        val text = "第一章\r\n\r\n阿青进入车站。\r\n\r\n第二章\r\n\r\n警报响起。"
        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val gb18030 = text.toByteArray(Charset.forName("GB18030"))
        val ingestor = TxtIngestor(JvmGb18030Decoder)
        val cases = listOf(
            text.encodeToByteArray() to "UTF-8",
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + text.encodeToByteArray() to "UTF-8 BOM",
            utf16 to "UTF-16LE",
            gb18030 to "GB18030",
        )

        cases.forEachIndexed { index, (bytes, charset) ->
            val document = assertIs<SourceIngestResult.Success>(
                ingestor.ingest("txt.$index", "story.txt", bytes),
            ).document
            assertEquals(charset, document.charset)
            assertEquals(listOf("第一章", "第二章"), document.sections.map { it.title })
            assertTrue(document.chunks.all { it.locator.sourcePath == "story.txt" })
            assertTrue(document.characterCount > 0)
        }
    }

    @Test
    fun epubUsesManifestAndSpineOrderWithHrefSourceLocations() = runTest {
        val epub = epub(
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>""",
                "OPS/package.opf" to """
                    <package xmlns:dc="dc"><metadata><dc:title>织境资料</dc:title><dc:creator>作者</dc:creator></metadata>
                    <manifest><item id="second" href="two.xhtml"/><item id="first" href="one.xhtml"/></manifest>
                    <spine><itemref idref="first"/><itemref idref="second"/></spine></package>
                """.trimIndent(),
                "OPS/two.xhtml" to "<html><body><h1>第二幕</h1><p>后发生。</p></body></html>",
                "OPS/one.xhtml" to "<html><body><h1>第一幕</h1><p>先发生。</p></body></html>",
            ),
        )

        val document = assertIs<SourceIngestResult.Success>(
            EpubIngestor(JvmEpubArchiveReader).ingest("epub.test", "story.epub", epub),
        ).document

        assertEquals("织境资料", document.title)
        assertEquals("作者", document.author)
        assertEquals(listOf("第一幕", "第二幕"), document.sections.map { it.title })
        assertEquals(listOf("OPS/one.xhtml", "OPS/two.xhtml"), document.sections.map { it.locator.epubHref })
        assertTrue(document.sections.all { it.locator.paragraphPath == "/html/body" })
    }

    @Test
    fun briefPipelinePublishesPlayableWorldAndEnforcesFiveThousandCharacterBoundary() = runTest {
        val pipeline = WorldGenerationPipeline(
            model = DeterministicCreationModel(),
            registry = StandardRuleModules.registry(),
        )
        val request = WorldGenerationRequest("brief.job", DefinitionId("generated.brief"), title = "雨夜车站")
        val result = assertIs<GenerationResult.Success>(pipeline.runBrief(request, "雨夜车站\n一名旅客必须找出停电原因。"))
        val entries = assertIs<ArchiveResult.Success>(StoredZipArchive.decode(result.world.packageBytes)).entries
            .associateBy { it.path }
        assertNotNull(entries["manifest.json"])
        assertNotNull(entries["definitions/world.json"])
        assertNotNull(entries["definitions/character-creation.json"])
        assertNotNull(entries["definitions/rules.json"])
        assertNotNull(entries["generation.json"])
        assertEquals("雨夜车站", result.world.draft.definition.title)

        val boundary = pipeline.runBrief(
            WorldGenerationRequest("brief.boundary", DefinitionId("generated.boundary")),
            "界".repeat(MAX_BRIEF_CHARACTERS),
        )
        val overflow = pipeline.runBrief(
            WorldGenerationRequest("brief.overflow", DefinitionId("generated.overflow")),
            "界".repeat(MAX_BRIEF_CHARACTERS + 1),
        )
        assertIs<GenerationResult.Success>(boundary)
        assertIs<GenerationResult.Failure>(overflow)
    }

    @Test
    fun corpusGenerationCancelsAfterCheckpointAndResumesWithoutRepeatingOutline() = runTest {
        val store = InMemoryGenerationTaskStore()
        val firstModel = CountingModel()
        var cancelled = false
        val request = WorldGenerationRequest(
            jobId = "corpus.resume",
            worldId = DefinitionId("generated.corpus"),
            includeSourceInPackage = true,
        )
        val firstPipeline = WorldGenerationPipeline(
            model = firstModel,
            registry = StandardRuleModules.registry(),
            taskStore = store,
            txtIngestor = TxtIngestor(JvmGb18030Decoder),
        )

        val first = firstPipeline.runCorpus(
            request,
            "corpus.txt",
            CorpusFileType.TXT,
            "章节\n大量剧情资料。".encodeToByteArray(),
            cancellation = CancellationProbe { cancelled },
            progress = GenerationProgressListener { progress ->
                if (progress.stage == GenerationStage.OUTLINED) cancelled = true
            },
        )
        assertEquals(GenerationResult.Cancelled(GenerationStage.OUTLINED), first)
        assertEquals(1, firstModel.outlineCalls)
        assertEquals(0, firstModel.draftCalls)

        val secondModel = CountingModel()
        val resumed = WorldGenerationPipeline(
            model = secondModel,
            registry = StandardRuleModules.registry(),
            taskStore = store,
            txtIngestor = TxtIngestor(JvmGb18030Decoder),
        ).runCorpus(
            request,
            "corpus.txt",
            CorpusFileType.TXT,
            "章节\n大量剧情资料。".encodeToByteArray(),
        )

        val success = assertIs<GenerationResult.Success>(resumed)
        assertEquals(0, secondModel.outlineCalls)
        assertEquals(1, secondModel.draftCalls)
        val entries = assertIs<ArchiveResult.Success>(StoredZipArchive.decode(success.world.packageBytes)).entries
        assertTrue(entries.any { it.path == "sources/original.txt" })
        assertEquals(GenerationStage.COMPLETED, assertNotNull(store.load(request.jobId)).stage)
    }

    private fun epub(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}

private class CountingModel : WorldCreationModel {
    private val delegate = DeterministicCreationModel()
    var outlineCalls = 0
    var draftCalls = 0

    override suspend fun outline(request: WorldGenerationRequest, document: SourceDocument): WorldOutline {
        outlineCalls += 1
        return delegate.outline(request, document)
    }

    override suspend fun draft(
        request: WorldGenerationRequest,
        document: SourceDocument,
        outline: WorldOutline,
    ): GeneratedWorldDraft {
        draftCalls += 1
        return delegate.draft(request, document, outline)
    }
}
