package io.worldloom.persistence

import io.worldloom.rules.CheckResolvedEvent
import io.worldloom.world.EventEnvelope
import io.worldloom.world.GameEventPayload
import io.worldloom.world.GameState
import io.worldloom.world.NumericComponentAdjustedEvent
import io.worldloom.world.PlayerComponentInitializedEvent
import io.worldloom.world.PlayerEnteredInitialSceneEvent
import io.worldloom.world.PlayerEntityCreatedEvent
import io.worldloom.world.RunLifecycleChangedEvent
import io.worldloom.world.ActionOutcomeAppliedEvent
import io.worldloom.world.PlayerEnteredSceneEvent
import io.worldloom.world.PlayerExitedSceneEvent
import io.worldloom.world.NpcPublicActionPublishedEvent
import io.worldloom.world.NpcAddressedEvent
import io.worldloom.world.NpcPresenceChangedEvent
import io.worldloom.world.NpcKnowledgeRevealedEvent
import io.worldloom.application.CharacterCreationDraft
import io.worldloom.agent.runtime.GameTurn
import io.worldloom.rules.ActivityCompletedEvent
import io.worldloom.rules.ScheduledTriggerFiredEvent
import io.worldloom.rules.TravelCompletedEvent
import io.worldloom.rules.TravelStartedEvent
import io.worldloom.rules.WorldTimeAdvancedEvent
import io.worldloom.rules.AdventureEndingReachedEvent
import io.worldloom.rules.ConditionUpdatedEvent
import io.worldloom.rules.InventoryChangedEvent
import io.worldloom.rules.ProgressClockAdvancedEvent
import io.worldloom.rules.QuestAdvancedEvent
import io.worldloom.rules.RelationshipAdjustedEvent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import io.worldloom.rules.ExplorationKnowledgeRevealedEvent
import io.worldloom.behavior.runtime.BehaviorWorkItem
import io.worldloom.agent.runtime.NpcWorkItem

sealed interface PersistenceDecodeResult<out T> {
    data class Success<T>(val value: T) : PersistenceDecodeResult<T>

    data class Failure(
        val code: String,
        val message: String,
    ) : PersistenceDecodeResult<Nothing>
}

@OptIn(ExperimentalSerializationApi::class)
object PersistenceCodec {
    private val json = Json {
        serializersModule = SerializersModule {
            polymorphic(GameEventPayload::class) {
                subclass(ExplorationKnowledgeRevealedEvent::class)
                subclass(NumericComponentAdjustedEvent::class)
                subclass(CheckResolvedEvent::class)
                subclass(RunLifecycleChangedEvent::class)
                subclass(PlayerEntityCreatedEvent::class)
                subclass(PlayerComponentInitializedEvent::class)
                subclass(PlayerEnteredInitialSceneEvent::class)
                subclass(ActionOutcomeAppliedEvent::class)
                subclass(PlayerExitedSceneEvent::class)
                subclass(PlayerEnteredSceneEvent::class)
                subclass(WorldTimeAdvancedEvent::class)
                subclass(ActivityCompletedEvent::class)
                subclass(TravelStartedEvent::class)
                subclass(TravelCompletedEvent::class)
                subclass(ScheduledTriggerFiredEvent::class)
                subclass(InventoryChangedEvent::class)
                subclass(ConditionUpdatedEvent::class)
                subclass(RelationshipAdjustedEvent::class)
                subclass(QuestAdvancedEvent::class)
                subclass(ProgressClockAdvancedEvent::class)
                subclass(AdventureEndingReachedEvent::class)
                subclass(NpcPublicActionPublishedEvent::class)
                subclass(NpcAddressedEvent::class)
                subclass(NpcPresenceChangedEvent::class)
                subclass(NpcKnowledgeRevealedEvent::class)
            }
        }
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        exceptionsWithDebugInfo = false
    }

    fun encodeEvent(event: EventEnvelope): String = json.encodeToString(event)

    fun decodeEvent(source: String): PersistenceDecodeResult<EventEnvelope> = decode("event", source)

    fun encodeState(state: GameState): String = json.encodeToString(state)

    fun decodeState(source: String): PersistenceDecodeResult<GameState> = decode("state", source)

    fun encodeCharacterDraft(draft: CharacterCreationDraft): String = json.encodeToString(draft)

    fun decodeCharacterDraft(source: String): PersistenceDecodeResult<CharacterCreationDraft> =
        decode("character_draft", source)

    fun encodeGameTurn(turn: GameTurn): String = json.encodeToString(turn)

    fun decodeGameTurn(source: String): PersistenceDecodeResult<GameTurn> = decode("gm_turn", source)

    fun encodeBehaviorWork(item: BehaviorWorkItem): String = json.encodeToString(item)

    fun decodeBehaviorWork(source: String): PersistenceDecodeResult<BehaviorWorkItem> = decode("behavior_work", source)

    fun encodeNpcWork(item: NpcWorkItem): String = json.encodeToString(item)

    fun decodeNpcWork(source: String): PersistenceDecodeResult<NpcWorkItem> = decode("npc_work", source)

    private inline fun <reified T> decode(
        label: String,
        source: String,
    ): PersistenceDecodeResult<T> =
        try {
            PersistenceDecodeResult.Success(json.decodeFromString<T>(source))
        } catch (error: SerializationException) {
            PersistenceDecodeResult.Failure(
                "persistence.$label.invalid_json",
                error.message ?: "Stored $label JSON is invalid",
            )
        } catch (error: IllegalArgumentException) {
            PersistenceDecodeResult.Failure(
                "persistence.$label.invalid_value",
                error.message ?: "Stored $label contains an invalid value",
            )
        }
}
