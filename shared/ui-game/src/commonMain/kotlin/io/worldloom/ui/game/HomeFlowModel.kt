package io.worldloom.ui.game

import io.worldloom.application.WorldCatalogEntry
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

private fun Int.sign(): Int = when {
    this > 0 -> 1
    this < 0 -> -1
    else -> 0
}
