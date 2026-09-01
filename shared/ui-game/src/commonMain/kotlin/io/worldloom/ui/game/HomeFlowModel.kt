package io.worldloom.ui.game

import io.worldloom.application.WorldCatalogEntry
import io.worldloom.application.SessionError
import io.worldloom.application.SessionErrorCode
import io.worldloom.platform.credentials.CredentialConfigurationState
import io.worldloom.provider.api.ProviderConfigurationId
import io.worldloom.world.RunId

internal enum class HomePane {
    MENU,
    DREAMS,
    SAVES,
}

internal sealed interface PendingDreamEntry {
    data class NewDream(val world: WorldCatalogEntry) : PendingDreamEntry

    data object QuickContinue : PendingDreamEntry

    data class Continue(val runId: RunId) : PendingDreamEntry
}

internal fun nextDreamIndex(
    currentIndex: Int,
    itemCount: Int,
    direction: Int,
): Int {
    if (itemCount <= 0) return 0
    val normalized = currentIndex.coerceIn(0, itemCount - 1)
    return (normalized + direction.sign() + itemCount) % itemCount
}

internal fun providerEntryReady(
    selectedId: ProviderConfigurationId?,
    credentialState: CredentialConfigurationState?,
): Boolean = selectedId != null && credentialState is CredentialConfigurationState.Configured

internal fun SessionError.homeMessage(): String = when (code) {
    SessionErrorCode.WORLD_NOT_FOUND,
    SessionErrorCode.INVALID_WORLD_DEFINITION,
    -> "世界内容不可用，请更新应用后重试。"

    SessionErrorCode.PERSISTENCE_REJECTED,
    SessionErrorCode.EVENT_STORE_REJECTED,
    SessionErrorCode.REPLAY_REJECTED,
    -> "存档无法初始化或恢复，请返回首页后重试。"

    SessionErrorCode.CHARACTER_CREATION_REJECTED -> "角色创建无法开始，请重新选择梦境。"
    else -> "入口暂时无法建立，请返回首页后重试。"
}

private fun Int.sign(): Int = when {
    this > 0 -> 1
    this < 0 -> -1
    else -> 0
}
