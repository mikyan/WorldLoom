package io.worldloom.rules

import io.worldloom.definition.CheckProfileDefinition
import io.worldloom.definition.CheckResolutionMode
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.ValidatedWorldDefinition
import io.worldloom.rules.module.api.RegisteredWorldModules
import io.worldloom.world.CommandAuthorization
import io.worldloom.world.CommandEnvelope
import io.worldloom.world.CommandEnvelopeValidator
import io.worldloom.world.CommandPermission
import io.worldloom.world.CommandValidationError
import io.worldloom.world.CommandValidationErrorCode
import io.worldloom.world.GameCommandPayload
import io.worldloom.world.GameState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

val RANDOM_CHECK_CAPABILITY_ID = DefinitionId("worldloom.schema.random-check-profile")
val DETERMINISTIC_CHECK_CAPABILITY_ID = DefinitionId("worldloom.schema.deterministic-check-profile")

@Serializable
@SerialName("resolve-check")
data class ResolveCheckCommand(
    val profileId: DefinitionId,
    val modifier: Long = 0,
) : GameCommandPayload

data class ValidatedCheckCommand(
    val envelope: CommandEnvelope,
    val payload: ResolveCheckCommand,
    val profile: CheckProfileDefinition,
)

sealed interface CheckCommandValidationResult {
    data class Valid(val command: ValidatedCheckCommand) : CheckCommandValidationResult

    data class Invalid(val error: CommandValidationError) : CheckCommandValidationResult
}

object CheckCommandValidator {
    fun validate(
        state: GameState,
        definition: ValidatedWorldDefinition,
        modules: RegisteredWorldModules,
        authorization: CommandAuthorization,
        envelope: CommandEnvelope,
    ): CheckCommandValidationResult {
        CommandEnvelopeValidator.validate(state, authorization, envelope)?.let {
            return CheckCommandValidationResult.Invalid(it)
        }
        if (CommandPermission.RESOLVE_CHECK !in authorization.permissions) {
            return invalid(CommandValidationErrorCode.PERMISSION_DENIED, "payload", "Actor may not resolve checks")
        }
        val payload = envelope.payload as? ResolveCheckCommand
            ?: return invalid(
                CommandValidationErrorCode.UNSUPPORTED_COMMAND_PAYLOAD,
                "payload",
                "Check validator requires ResolveCheckCommand",
            )
        val profile = definition.checkProfile(payload.profileId)
            ?: return invalid(
                CommandValidationErrorCode.FIELD_NOT_FOUND,
                "payload.profileId",
                "Check profile is not defined: ${payload.profileId}",
            )
        val requiredCapability = when (profile.mode) {
            CheckResolutionMode.RANDOM -> RANDOM_CHECK_CAPABILITY_ID
            CheckResolutionMode.DETERMINISTIC -> DETERMINISTIC_CHECK_CAPABILITY_ID
        }
        if (modules.capability(requiredCapability) == null) {
            return invalid(
                CommandValidationErrorCode.PERMISSION_DENIED,
                "payload.profileId",
                "The world manifest did not enable the capability required by this check",
            )
        }
        return CheckCommandValidationResult.Valid(ValidatedCheckCommand(envelope, payload, profile))
    }

    private fun invalid(
        code: CommandValidationErrorCode,
        path: String,
        message: String,
    ): CheckCommandValidationResult.Invalid =
        CheckCommandValidationResult.Invalid(CommandValidationError(code, path, message))
}
