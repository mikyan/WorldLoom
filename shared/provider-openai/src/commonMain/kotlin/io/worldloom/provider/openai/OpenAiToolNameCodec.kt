package io.worldloom.provider.openai

import io.worldloom.provider.api.ProviderRequest

/**
 * Keeps Worldloom's namespaced tool identifiers inside the runtime while exposing only
 * OpenAI-compatible function names on the wire. The mapping is request-scoped because a model can
 * only call tools declared in that request, and the decoded logical name must remain authoritative.
 */
internal class OpenAiToolNameCodec private constructor(
    private val logicalToWire: Map<String, String>,
    private val wireToLogical: Map<String, String>,
) {
    fun encode(logicalName: String): String = logicalToWire.getValue(logicalName)

    fun decode(wireName: String): String = wireToLogical[wireName] ?: wireName

    companion object {
        fun from(request: ProviderRequest): OpenAiToolNameCodec {
            val logicalNames = buildList {
                request.tools.forEach { add(it.name) }
                request.messages.forEach { message ->
                    message.toolCalls.forEach { add(it.name) }
                }
            }.distinct()
            val reservedCompatibleNames = logicalNames.filter(OPENAI_TOOL_NAME_PATTERN::matches).toSet()
            val usedWireNames = mutableSetOf<String>()
            val logicalToWire = linkedMapOf<String, String>()

            logicalNames.forEachIndexed { index, logicalName ->
                val wireName = if (OPENAI_TOOL_NAME_PATTERN.matches(logicalName)) {
                    logicalName
                } else {
                    compatibleWireName(
                        logicalName = logicalName,
                        index = index,
                        unavailableNames = reservedCompatibleNames + usedWireNames,
                    )
                }
                check(usedWireNames.add(wireName)) { "Compatible tool names must be unique" }
                logicalToWire[logicalName] = wireName
            }

            return OpenAiToolNameCodec(
                logicalToWire = logicalToWire,
                wireToLogical = logicalToWire.entries.associate { (logical, wire) -> wire to logical },
            )
        }
    }
}

private fun compatibleWireName(
    logicalName: String,
    index: Int,
    unavailableNames: Set<String>,
): String {
    val stem = logicalName
        .map { character -> if (character.isOpenAiToolNameCharacter()) character else '_' }
        .joinToString("")
        .trim('_')
        .ifBlank { "tool" }
    val prefix = "wl_${index}_"
    var discriminator = 0

    while (true) {
        val suffix = if (discriminator == 0) "" else "_$discriminator"
        val availableStemLength = OPENAI_TOOL_NAME_MAX_LENGTH - prefix.length - suffix.length
        check(availableStemLength > 0) { "Compatible tool name prefix exceeds the provider limit" }
        val candidate = prefix + stem.take(availableStemLength) + suffix
        if (candidate !in unavailableNames) return candidate
        discriminator += 1
    }
}

private fun Char.isOpenAiToolNameCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-'

private const val OPENAI_TOOL_NAME_MAX_LENGTH = 64
private val OPENAI_TOOL_NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,$OPENAI_TOOL_NAME_MAX_LENGTH}$")
