package io.worldloom.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.worldloom.application.GamePresentation
import io.worldloom.application.GameSession
import io.worldloom.application.GameSessionAction
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.SessionError
import io.worldloom.application.WorldCatalogEntry
import kotlinx.coroutines.launch

private val WorldloomColors = darkColors(
    primary = Color(0xFFD6B56E),
    primaryVariant = Color(0xFFA9843A),
    secondary = Color(0xFF8FA79A),
    background = Color(0xFF121513),
    surface = Color(0xFF1C211E),
    error = Color(0xFFD98282),
    onPrimary = Color(0xFF211B0F),
    onSecondary = Color(0xFF111713),
    onBackground = Color(0xFFE7E4DA),
    onSurface = Color(0xFFE7E4DA),
    onError = Color(0xFF241010),
)

@Composable
fun WorldloomApp(session: GameSession) {
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()

    MaterialTheme(colors = WorldloomColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppHeader()
                WorldSelector(
                    worlds = session.availableWorlds,
                    onSelected = { world -> scope.launch { session.load(world.id) } },
                )
                when (val current = state) {
                    GameSessionUiState.Idle -> EmptyState("选择一个契约世界，开始验证权威运行管线。")
                    is GameSessionUiState.Loading -> LoadingState()
                    is GameSessionUiState.Ready -> ReadyState(
                        presentation = current.presentation,
                        notice = current.notice,
                        onAdjust = { presentationId ->
                            scope.launch {
                                session.perform(GameSessionAction.AdjustPresentedField(presentationId))
                            }
                        },
                        onReplay = { scope.launch { session.replay() } },
                    )

                    is GameSessionUiState.Failed -> EmptyState(current.error.message, isError = true)
                }
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("WORLDLOOM / 织境", color = MaterialTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("确定性世界引擎 · 初始化竖切", color = MaterialTheme.colors.onBackground.copy(alpha = 0.68f))
    }
}

@Composable
private fun WorldSelector(
    worlds: List<WorldCatalogEntry>,
    onSelected: (WorldCatalogEntry) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(worlds, key = { it.id.value }) { world ->
            Button(onClick = { onSelected(world) }) {
                Text(world.title)
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colors.primary)
    }
}

@Composable
private fun EmptyState(
    message: String,
    isError: Boolean = false,
) {
    Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
        Text(
            text = message,
            modifier = Modifier.padding(20.dp),
            color = if (isError) MaterialTheme.colors.error else MaterialTheme.colors.onSurface,
        )
    }
}

@Composable
private fun ReadyState(
    presentation: GamePresentation,
    notice: SessionError?,
    onAdjust: (io.worldloom.definition.DefinitionId) -> Unit,
    onReplay: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(presentation.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Event sequence ${presentation.lastSequence}",
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.58f),
                )
            }
            Button(
                onClick = onReplay,
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary),
            ) {
                Text("回放校验")
            }
        }

        notice?.let { EmptyState(it.message, isError = true) }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(presentation.fields, key = { it.presentationId.value }) { field ->
                Card(backgroundColor = MaterialTheme.colors.surface) {
                    Column(
                        modifier = Modifier.width(220.dp).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(field.label, color = MaterialTheme.colors.onSurface.copy(alpha = 0.72f))
                        Text(field.value.toString(), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Button(onClick = { onAdjust(field.presentationId) }) {
                            Text("推进 ${signed(field.adjustmentStep)}")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("事件时间线", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
        if (presentation.timeline.isEmpty()) {
            Text("尚无事件。所有事实变化都会在这里留下可回放记录。", color = MaterialTheme.colors.onSurface.copy(alpha = 0.58f))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(presentation.timeline, key = { it.sequence }) { event ->
                    Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#${event.sequence}", color = MaterialTheme.colors.primary, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(16.dp))
                            Text(event.summary)
                        }
                    }
                }
            }
        }
    }
}

private fun signed(value: Long): String = if (value > 0) "+$value" else value.toString()
