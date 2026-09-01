package io.worldloom.application

import io.worldloom.content.schema.CharacterCreationMode
import io.worldloom.content.schema.CharacterCreationRequest
import io.worldloom.content.schema.CharacterCreationResult
import io.worldloom.content.schema.CharacterCreator
import io.worldloom.content.schema.CharacterProfileProblem
import io.worldloom.content.schema.CharacterValueAssignment
import io.worldloom.content.schema.ValidatedCharacterCreationProfile
import io.worldloom.definition.DefinitionId
import io.worldloom.definition.TypedValue
import io.worldloom.world.ActorId
import io.worldloom.world.CommandEnvelope
import io.worldloom.world.CommandId
import io.worldloom.world.CharacterCreationCommandPolicy
import io.worldloom.world.CreatePlayerCharacterCommand
import io.worldloom.world.CURRENT_COMMAND_SCHEMA_VERSION
import io.worldloom.world.GameState
import io.worldloom.world.EntityId
import io.worldloom.world.RunId
import kotlinx.serialization.Serializable

const val CURRENT_CHARACTER_CREATION_DRAFT_SCHEMA_VERSION: Int = 1

/** Non-authoritative, resumable input. Confirming it emits a Command; it never mutates GameState. */
@Serializable
data class CharacterCreationDraft(
    val schemaVersion: Int = CURRENT_CHARACTER_CREATION_DRAFT_SCHEMA_VERSION,
    val runId: RunId,
    val worldId: DefinitionId,
    val profileId: DefinitionId,
    val confirmationCommandId: CommandId,
    val request: CharacterCreationRequest,
)

interface CharacterCreationDraftStore {
    suspend fun load(runId: RunId): CharacterCreationDraft?

    suspend fun save(draft: CharacterCreationDraft)

    suspend fun delete(runId: RunId)
}

class InMemoryCharacterCreationDraftStore : CharacterCreationDraftStore {
    private val drafts = mutableMapOf<RunId, CharacterCreationDraft>()

    override suspend fun load(runId: RunId): CharacterCreationDraft? = drafts[runId]

    override suspend fun save(draft: CharacterCreationDraft) {
        drafts[draft.runId] = draft
    }

    override suspend fun delete(runId: RunId) {
        drafts.remove(runId)
    }
}

data class CharacterCreationFieldPresentation(
    val componentId: DefinitionId,
    val fieldId: DefinitionId,
    val label: String,
    val value: TypedValue,
    val minimumInteger: Long?,
    val maximumInteger: Long?,
)

data class CharacterCreationOptionPresentation(val id: DefinitionId, val label: String)

data class CharacterCreationPresentation(
    val runId: RunId,
    val worldId: DefinitionId,
    val worldTitle: String,
    val profileId: DefinitionId,
    val playerEntityId: String,
    val modes: List<CharacterCreationMode>,
    val selectedMode: CharacterCreationMode,
    val options: List<CharacterCreationOptionPresentation>,
    val selectedOptionId: DefinitionId?,
    val fields: List<CharacterCreationFieldPresentation>,
    val pointBuyBudget: Int?,
    val pointsSpent: Int,
    val narrativeBackground: String,
    val problems: List<CharacterProfileProblem>,
    val notice: SessionError? = null,
)

sealed interface CharacterCreationCandidateResult {
    data class Success(
        val command: CommandEnvelope,
        val pointsSpent: Int,
    ) : CharacterCreationCandidateResult

    data class Failure(val problems: List<CharacterProfileProblem>) : CharacterCreationCandidateResult
}

/** Coordinates one pinned profile and its topic-neutral Definition boundary. */
class CharacterCreationCoordinator(
    private val worldId: DefinitionId,
    private val worldTitle: String,
    private val profile: ValidatedCharacterCreationProfile,
    private val playerEntityId: String,
    private val initialSceneId: DefinitionId,
    private val initialSceneParticipantIds: List<EntityId> = emptyList(),
    private val fieldLabels: Map<Pair<DefinitionId, DefinitionId>, String> = emptyMap(),
) {
    fun commandPolicy(): CharacterCreationCommandPolicy = CharacterCreationCommandPolicy(
        profileId = profile.source.id,
        playerEntityId = EntityId(playerEntityId),
        initialSceneId = initialSceneId,
        initialSceneParticipantIds = initialSceneParticipantIds,
    )

    fun createDraft(runId: RunId, confirmationCommandId: CommandId): CharacterCreationDraft {
        val mode = profile.source.modes.minBy(CharacterCreationMode::ordinal)
        return CharacterCreationDraft(
            runId = runId,
            worldId = worldId,
            profileId = profile.source.id,
            confirmationCommandId = confirmationCommandId,
            request = CharacterCreationRequest(
                entityId = playerEntityId,
                mode = mode,
                optionId = options(mode).firstOrNull()?.id,
            ),
        )
    }

    fun update(draft: CharacterCreationDraft, request: CharacterCreationRequest): CharacterCreationDraft {
        require(draft.worldId == worldId && draft.profileId == profile.source.id) { "Character draft is not pinned here" }
        require(request.entityId == playerEntityId) { "Player Entity ID cannot be changed by character input" }
        return draft.copy(request = request)
    }

    fun candidate(
        state: GameState,
        draft: CharacterCreationDraft,
        actorId: ActorId,
    ): CharacterCreationCandidateResult = when (val creation = CharacterCreator.create(profile, draft.request)) {
        is CharacterCreationResult.Failure -> CharacterCreationCandidateResult.Failure(creation.problems)
        is CharacterCreationResult.Success -> CharacterCreationCandidateResult.Success(
            command = CommandEnvelope(
                schemaVersion = CURRENT_COMMAND_SCHEMA_VERSION,
                commandId = draft.confirmationCommandId,
                runId = state.runId,
                actorId = actorId,
                expectedSequence = state.lastSequence,
                payload = CreatePlayerCharacterCommand(
                    profileId = profile.source.id,
                    entity = creation.entity,
                    initialSceneId = initialSceneId,
                    initialSceneParticipantIds = initialSceneParticipantIds,
                ),
            ),
            pointsSpent = creation.pointsSpent,
        )
    }

    fun present(draft: CharacterCreationDraft, notice: SessionError? = null): CharacterCreationPresentation {
        val creation = CharacterCreator.create(profile, draft.request)
        val entity = (creation as? CharacterCreationResult.Success)?.entity
        val values = entity?.components.orEmpty().flatMap { component ->
            component.fields.map { (component.definitionId to it.id) to it.value }
        }.toMap()
        return CharacterCreationPresentation(
            runId = draft.runId,
            worldId = worldId,
            worldTitle = worldTitle,
            profileId = profile.source.id,
            playerEntityId = playerEntityId,
            modes = profile.source.modes.sortedBy(CharacterCreationMode::ordinal),
            selectedMode = draft.request.mode,
            options = options(draft.request.mode).map { CharacterCreationOptionPresentation(it.id, it.label) },
            selectedOptionId = draft.request.optionId,
            fields = profile.source.fields.mapIndexed { index, rule ->
                CharacterCreationFieldPresentation(
                    componentId = rule.componentId,
                    fieldId = rule.fieldId,
                    label = fieldLabels[rule.componentId to rule.fieldId] ?: "属性 ${index + 1}",
                    value = values[rule.componentId to rule.fieldId] ?: rule.defaultValue,
                    minimumInteger = rule.minimumInteger,
                    maximumInteger = rule.maximumInteger,
                )
            },
            pointBuyBudget = profile.source.pointBuyBudget,
            pointsSpent = (creation as? CharacterCreationResult.Success)?.pointsSpent ?: 0,
            narrativeBackground = draft.request.narrativeBackground.orEmpty(),
            problems = (creation as? CharacterCreationResult.Failure)?.problems.orEmpty(),
            notice = notice,
        )
    }

    private fun options(mode: CharacterCreationMode) = when (mode) {
        CharacterCreationMode.FIXED -> profile.source.fixedOptions
        CharacterCreationMode.TEMPLATE -> profile.source.templates
        CharacterCreationMode.POINT_BUY, CharacterCreationMode.NARRATIVE -> emptyList()
    }
}

fun CharacterCreationPresentation.request(
    mode: CharacterCreationMode = selectedMode,
    optionId: DefinitionId? = selectedOptionId,
    values: List<CharacterValueAssignment> = emptyList(),
    narrativeBackground: String? = this.narrativeBackground,
    entityId: String = playerEntityId,
): CharacterCreationRequest = CharacterCreationRequest(entityId, mode, optionId, values, narrativeBackground)
