package io.worldloom.ui.game

import io.worldloom.content.generation.RecognitionCandidateKind
import io.worldloom.content.generation.RecognitionConfidence
import io.worldloom.content.generation.RecognitionStage
import io.worldloom.content.generation.RecognitionStatus
import io.worldloom.world.RunLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FocusedPagePresentationTest {
    @Test
    fun `save lifecycle labels never expose enum names`() {
        RunLifecycle.entries.forEach { lifecycle ->
            val label = runLifecycleLabel(lifecycle)
            assertFalse(label.contains(lifecycle.name))
            assertFalse(label.isBlank())
        }
    }

    @Test
    fun `recognition progress is bounded and handles unknown totals`() {
        assertEquals(0f, recognitionProgress(4, 0))
        assertEquals(0f, recognitionProgress(-2, 10))
        assertEquals(0.4f, recognitionProgress(4, 10))
        assertEquals(1f, recognitionProgress(20, 10))
        assertEquals("正在准备识别任务…", recognitionProgressLabel(0, 0))
        assertEquals("已完成 10 / 10", recognitionProgressLabel(20, 10))
    }

    @Test
    fun `recognition labels are player readable`() {
        RecognitionStage.entries.forEach { stage ->
            assertFalse(recognitionStageLabel(stage).contains(stage.name))
        }
        RecognitionStatus.entries.forEach { status ->
            assertFalse(recognitionStatusLabel(status).contains(status.name))
        }
        RecognitionCandidateKind.entries.forEach { kind ->
            assertFalse(recognitionCandidateKindLabel(kind).contains(kind.name))
        }
        RecognitionConfidence.entries.forEach { confidence ->
            assertFalse(recognitionConfidenceLabel(confidence).contains(confidence.name))
        }
    }
}
