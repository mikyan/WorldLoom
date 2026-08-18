package io.worldloom.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.worldloom.content.generation.RecognitionCandidatePresentation
import io.worldloom.content.generation.RecognitionWorkspacePresentation

@Composable
fun RecognitionWorkspacePanel(
    workspace: RecognitionWorkspacePresentation,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onSelectCandidate: (RecognitionCandidatePresentation) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("剧本识别工作区", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
            Text("${workspace.sourceName} · ${workspace.stage.name} · ${workspace.status.name}")
            LinearProgressIndicator(
                progress = workspace.completedUnits.toFloat() / workspace.totalUnits.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (workspace.diagnostics.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    workspace.diagnostics.forEach { diagnostic ->
                        Text("• $diagnostic", color = MaterialTheme.colors.error)
                    }
                }
            }
            if (workspace.candidates.isNotEmpty()) {
                Text("识别候选", fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(workspace.candidates, key = RecognitionCandidatePresentation::id) { candidate ->
                        Card {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(candidate.kind.name, color = MaterialTheme.colors.secondary)
                                Text(candidate.label, fontWeight = FontWeight.SemiBold)
                                Text(candidate.sourceLabel)
                                Button(onClick = { onSelectCandidate(candidate) }) { Text("查看来源") }
                            }
                        }
                    }
                }
            }
            workspace.selectedSourceContext?.let { context ->
                Text("来源上下文 · ${context.fragmentId}", fontWeight = FontWeight.SemiBold)
                Text(context.excerpt)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCancel, enabled = workspace.canCancel) { Text("取消识别") }
                Button(onClick = onResume, enabled = workspace.canResume) { Text("继续识别") }
                Button(onClick = {}, enabled = workspace.canPlaytest) { Text("试玩") }
                Button(onClick = {}, enabled = workspace.canInstall) { Text("安装") }
            }
        }
    }
}
