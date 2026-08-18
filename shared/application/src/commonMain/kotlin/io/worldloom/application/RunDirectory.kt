package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.world.RunId
import io.worldloom.world.RunLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RunDirectoryEntry(
    val runId: RunId,
    val worldId: DefinitionId,
    val worldContentVersion: Int,
    val displayName: String,
    val archived: Boolean,
    val lastSequence: Long,
    val lifecycle: RunLifecycle,
    val diagnostic: String? = null,
)

interface RunDirectoryStore {
    suspend fun list(): List<RunDirectoryEntry>

    suspend fun rename(runId: RunId, displayName: String): Boolean

    suspend fun setArchived(runId: RunId, archived: Boolean): Boolean

    suspend fun setWorldContentVersion(runId: RunId, version: Int): Boolean
}

sealed interface SaveLibraryState {
    data object Loading : SaveLibraryState

    data class Ready(val runs: List<RunDirectoryEntry>) : SaveLibraryState

    data class Failed(val message: String) : SaveLibraryState
}

enum class SaveOperationErrorCode {
    WORLD_NOT_FOUND,
    RUN_NOT_FOUND,
    INVALID_NAME,
    CONTENT_VERSION_MISMATCH,
    LOAD_REJECTED,
    STORAGE_FAILURE,
}

data class SaveOperationError(val code: SaveOperationErrorCode, val message: String)

sealed interface SaveOperationResult {
    data class Success(val runId: RunId? = null) : SaveOperationResult

    data class Failure(val error: SaveOperationError) : SaveOperationResult
}

/** Coordinates user-facing save operations without treating snapshots as authoritative facts. */
class SaveCoordinator(
    private val session: GameSession,
    private val store: RunDirectoryStore,
) {
    private val mutableState = MutableStateFlow<SaveLibraryState>(SaveLibraryState.Loading)
    val state: StateFlow<SaveLibraryState> = mutableState.asStateFlow()

    suspend fun refresh() {
        mutableState.value = try {
            SaveLibraryState.Ready(store.list().sortedWith(runOrdering))
        } catch (_: Exception) {
            SaveLibraryState.Failed("无法读取存档目录")
        }
    }

    suspend fun create(worldId: DefinitionId, displayName: String? = null): SaveOperationResult {
        val world = session.availableWorlds.firstOrNull { it.id == worldId }
            ?: return failure(SaveOperationErrorCode.WORLD_NOT_FOUND, "内置世界不可用：$worldId")
        if (session.load(worldId) !is LoadResult.Success) {
            return failure(SaveOperationErrorCode.LOAD_REJECTED, "无法创建新的游戏 Run")
        }
        val runId = session.currentRunId
            ?: return failure(SaveOperationErrorCode.LOAD_REJECTED, "新 Run 未提供稳定标识")
        if (!store.setWorldContentVersion(runId, world.contentVersion)) {
            return failure(SaveOperationErrorCode.STORAGE_FAILURE, "无法写入世界内容版本")
        }
        val requestedName = displayName?.trim().orEmpty().ifBlank { world.title }
        if (!validName(requestedName) || !store.rename(runId, requestedName)) {
            return failure(SaveOperationErrorCode.STORAGE_FAILURE, "无法保存 Run 名称")
        }
        refresh()
        return SaveOperationResult.Success(runId)
    }

    suspend fun continueRun(runId: RunId): SaveOperationResult {
        val entry = store.list().firstOrNull { it.runId == runId }
            ?: return failure(SaveOperationErrorCode.RUN_NOT_FOUND, "存档不存在：$runId")
        val world = session.availableWorlds.firstOrNull { it.id == entry.worldId }
            ?: return failure(SaveOperationErrorCode.WORLD_NOT_FOUND, "存档使用的世界不可用：${entry.worldId}")
        if (entry.worldContentVersion != world.contentVersion) {
            return failure(
                SaveOperationErrorCode.CONTENT_VERSION_MISMATCH,
                "存档内容版本 ${entry.worldContentVersion} 与内置世界版本 ${world.contentVersion} 不一致",
            )
        }
        return when (session.resume(entry.worldId, runId)) {
            LoadResult.Success -> SaveOperationResult.Success(runId)
            is LoadResult.Failure -> failure(SaveOperationErrorCode.LOAD_REJECTED, "存档一致性校验失败")
        }.also { refresh() }
    }

    suspend fun rename(runId: RunId, displayName: String): SaveOperationResult {
        val normalized = displayName.trim()
        if (!validName(normalized)) return failure(
            SaveOperationErrorCode.INVALID_NAME,
            "存档名称必须包含 1 到 80 个字符",
        )
        if (!store.rename(runId, normalized)) return failure(SaveOperationErrorCode.RUN_NOT_FOUND, "存档不存在")
        refresh()
        return SaveOperationResult.Success(runId)
    }

    suspend fun archive(runId: RunId, archived: Boolean = true): SaveOperationResult {
        if (!store.setArchived(runId, archived)) return failure(SaveOperationErrorCode.RUN_NOT_FOUND, "存档不存在")
        refresh()
        return SaveOperationResult.Success(runId)
    }

    private fun validName(value: String): Boolean = value.length in 1..80 && value.none(Char::isISOControl)

    private fun failure(code: SaveOperationErrorCode, message: String) = SaveOperationResult.Failure(
        SaveOperationError(code, message),
    )

    private companion object {
        val runOrdering = compareBy<RunDirectoryEntry> { it.archived }
            .thenByDescending { it.lastSequence }
            .thenByDescending { it.runId.value }
    }
}
