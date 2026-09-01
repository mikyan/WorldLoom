package io.worldloom.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.worldloom.content.generation.RecognitionCandidateKind
import io.worldloom.content.generation.RecognitionCandidatePresentation
import io.worldloom.content.generation.RecognitionConfidence
import io.worldloom.content.generation.RecognitionStage
import io.worldloom.content.generation.RecognitionStatus
import io.worldloom.content.generation.RecognitionWorkspacePresentation

@Composable
fun RecognitionWorkspacePanel(
    workspace: RecognitionWorkspacePresentation,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onSelectCandidate: (RecognitionCandidatePresentation) -> Unit,
) {
    WorldloomPanel(modifier = Modifier.fillMaxWidth(), strong = true) {
        WorldloomSectionHeading(
            title = "剧本识别工作区",
            subtitle = workspace.sourceName,
        )
        WorldloomStatusBanner(
            message = "${recognitionStageLabel(workspace.stage)} · ${recognitionStatusLabel(workspace.status)}",
            tone = recognitionStatusTone(workspace.status),
        )
        Column(verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs)) {
            LinearProgressIndicator(
                progress = recognitionProgress(workspace.completedUnits, workspace.totalUnits),
                modifier = Modifier.fillMaxWidth(),
                color = WorldloomPalette.BrandPrimary,
                backgroundColor = WorldloomPalette.SurfaceRaised,
            )
            Text(
                recognitionProgressLabel(workspace.completedUnits, workspace.totalUnits),
                color = WorldloomPalette.TextSecondary,
                style = MaterialTheme.typography.caption,
            )
        }

        workspace.diagnostics.forEach { diagnostic ->
            WorldloomStatusBanner(diagnostic, WorldloomStatusTone.WARNING)
        }

        if (workspace.candidates.isNotEmpty()) {
            WorldloomSectionHeading(
                title = "识别候选",
                subtitle = "查看候选与来源上下文，再决定是否继续进入草稿试玩。",
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm)) {
                items(workspace.candidates, key = RecognitionCandidatePresentation::id) { candidate ->
                    WorldloomPanel(modifier = Modifier.width(WorldloomDimensions.RecognitionCandidateWidth)) {
                        Text(
                            recognitionCandidateKindLabel(candidate.kind),
                            color = WorldloomPalette.BrandPrimary,
                            style = MaterialTheme.typography.subtitle1,
                        )
                        Text(candidate.label, style = MaterialTheme.typography.h3)
                        candidate.confidence?.let {
                            Text(
                                "识别置信度：${recognitionConfidenceLabel(it)}",
                                color = WorldloomPalette.TextSecondary,
                                style = MaterialTheme.typography.caption,
                            )
                        }
                        if (candidate.diagnostics.isNotEmpty()) {
                            Text(
                                "有 ${candidate.diagnostics.size} 条待核对提示",
                                color = WorldloomPalette.Warning,
                                style = MaterialTheme.typography.caption,
                            )
                        }
                        WorldloomSecondaryButton(
                            label = "查看来源",
                            onClick = { onSelectCandidate(candidate) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        workspace.selectedSourceContext?.let { context ->
            WorldloomPanel(modifier = Modifier.fillMaxWidth()) {
                WorldloomSectionHeading(
                    title = "来源上下文",
                    subtitle = "用于核对候选内容，不会直接写入世界事实。",
                )
                Text(context.excerpt, color = WorldloomPalette.TextPrimary)
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
        ) {
            item {
                WorldloomDangerButton(
                    label = "取消识别",
                    onClick = onCancel,
                    enabled = workspace.canCancel,
                )
            }
            item {
                WorldloomPrimaryButton(
                    label = "继续识别",
                    onClick = onResume,
                    enabled = workspace.canResume,
                )
            }
            item {
                WorldloomSecondaryButton(label = "试玩", onClick = {}, enabled = workspace.canPlaytest)
            }
            item {
                WorldloomSecondaryButton(label = "安装", onClick = {}, enabled = workspace.canInstall)
            }
        }
    }
}

internal fun recognitionProgress(completedUnits: Int, totalUnits: Int): Float = if (totalUnits <= 0) {
    0f
} else {
    (completedUnits.toFloat() / totalUnits.toFloat()).coerceIn(0f, 1f)
}

internal fun recognitionProgressLabel(completedUnits: Int, totalUnits: Int): String = if (totalUnits <= 0) {
    "正在准备识别任务…"
} else {
    "已完成 ${completedUnits.coerceIn(0, totalUnits)} / $totalUnits"
}

internal fun recognitionStageLabel(stage: RecognitionStage): String = when (stage) {
    RecognitionStage.RECEIVED -> "已接收素材"
    RecognitionStage.PARSING -> "正在解析素材"
    RecognitionStage.PARSED -> "素材解析完成"
    RecognitionStage.RECOGNIZING -> "正在识别剧本要素"
    RecognitionStage.DRAFTED -> "识别草稿已生成"
    RecognitionStage.CANCELLED -> "识别已取消"
    RecognitionStage.FAILED -> "识别失败"
    RecognitionStage.SOURCE_CHANGED -> "来源内容已变化"
}

internal fun recognitionStatusLabel(status: RecognitionStatus): String = when (status) {
    RecognitionStatus.RUNNING -> "处理中"
    RecognitionStatus.READY_FOR_REVIEW -> "可以检查"
    RecognitionStatus.CANCELLED -> "已取消"
    RecognitionStatus.FAILED -> "需要处理"
    RecognitionStatus.SOURCE_CHANGED -> "需要重新识别"
}

internal fun recognitionStatusTone(status: RecognitionStatus): WorldloomStatusTone = when (status) {
    RecognitionStatus.RUNNING -> WorldloomStatusTone.INFO
    RecognitionStatus.READY_FOR_REVIEW -> WorldloomStatusTone.SUCCESS
    RecognitionStatus.CANCELLED, RecognitionStatus.SOURCE_CHANGED -> WorldloomStatusTone.WARNING
    RecognitionStatus.FAILED -> WorldloomStatusTone.ERROR
}

internal fun recognitionCandidateKindLabel(kind: RecognitionCandidateKind): String = when (kind) {
    RecognitionCandidateKind.CHARACTER -> "角色"
    RecognitionCandidateKind.LOCATION -> "地点"
    RecognitionCandidateKind.SCENE -> "场景"
    RecognitionCandidateKind.OBJECTIVE -> "目标"
    RecognitionCandidateKind.CANDIDATE_FACT -> "候选事实"
}

internal fun recognitionConfidenceLabel(confidence: RecognitionConfidence): String = when (confidence) {
    RecognitionConfidence.HIGH -> "高"
    RecognitionConfidence.MEDIUM -> "中"
    RecognitionConfidence.LOW -> "低"
}
