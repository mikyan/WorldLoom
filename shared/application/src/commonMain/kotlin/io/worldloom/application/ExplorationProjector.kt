package io.worldloom.application

import io.worldloom.definition.DefinitionId
import io.worldloom.rules.ExplorationKnowledgeLevel
import io.worldloom.rules.ExplorationState
import io.worldloom.world.GameState
import io.worldloom.world.packageformat.PlayableAffordanceKind
import io.worldloom.world.packageformat.PlayableWorldContract

data class PresentedSituationBoard(
    val sensoryDetails: List<String>,
    val objective: String,
    val pressure: String,
    val question: String,
)

data class PresentedExplorationNode(
    val id: DefinitionId,
    val label: String,
    val description: String,
    val level: ExplorationKnowledgeLevel,
    val current: Boolean,
)

data class PresentedExplorationConnection(
    val id: DefinitionId,
    val fromNodeId: DefinitionId,
    val toNodeId: DefinitionId,
    val label: String,
    val directionSummary: String?,
    val travelMinutes: Long?,
    val riskSummary: String?,
    val level: ExplorationKnowledgeLevel,
)

data class PresentedExplorationAffordance(
    val id: DefinitionId,
    val kind: PlayableAffordanceKind,
    val label: String,
    val description: String,
    val level: ExplorationKnowledgeLevel,
)

data class ExplorationPresentation(
    val situation: PresentedSituationBoard? = null,
    val nodes: List<PresentedExplorationNode> = emptyList(),
    val connections: List<PresentedExplorationConnection> = emptyList(),
    val affordances: List<PresentedExplorationAffordance> = emptyList(),
) {
    val knownExitCount: Int get() {
        val currentNodeIds = nodes.filter(PresentedExplorationNode::current).mapTo(mutableSetOf(), PresentedExplorationNode::id)
        return connections.count { connection ->
            connection.level != ExplorationKnowledgeLevel.BLOCKED &&
                (connection.fromNodeId in currentNodeIds || connection.toNodeId in currentNodeIds)
        }
    }
}

/** Builds a spoiler-safe read model exclusively from committed exploration module state. */
object ExplorationProjector {
    fun project(contract: PlayableWorldContract, state: GameState): ExplorationPresentation {
        val definition = contract.exploration ?: return ExplorationPresentation()
        val known = ExplorationState.known(state)
        val nodes = definition.nodes.mapNotNull { node ->
            val level = known[node.id] ?: return@mapNotNull null
            PresentedExplorationNode(
                id = node.id,
                label = node.label,
                description = node.description,
                level = level,
                current = node.sceneId == state.currentSceneId,
            )
        }.sortedWith(compareByDescending<PresentedExplorationNode> { it.current }.thenBy { it.id.value })
        val visibleNodeIds = nodes.mapTo(mutableSetOf(), PresentedExplorationNode::id)
        val connections = definition.connections.mapNotNull { connection ->
            val level = known[connection.id] ?: return@mapNotNull null
            if (connection.fromNodeId !in visibleNodeIds || connection.toNodeId !in visibleNodeIds) return@mapNotNull null
            PresentedExplorationConnection(
                id = connection.id,
                fromNodeId = connection.fromNodeId,
                toNodeId = connection.toNodeId,
                label = connection.label,
                directionSummary = connection.directionSummary,
                travelMinutes = connection.travelMinutes,
                riskSummary = connection.publicRisk?.summary,
                level = level,
            )
        }.sortedBy { it.id.value }
        val affordances = definition.affordances.mapNotNull { affordance ->
            val level = known[affordance.id] ?: return@mapNotNull null
            if (affordance.sceneId != state.currentSceneId) return@mapNotNull null
            PresentedExplorationAffordance(
                id = affordance.id,
                kind = affordance.kind,
                label = affordance.label,
                description = affordance.description,
                level = level,
            )
        }.sortedBy { it.id.value }
        val situation = definition.sceneFrames.firstOrNull { it.sceneId == state.currentSceneId }?.let { frame ->
            PresentedSituationBoard(frame.sensoryDetails, frame.objective, frame.pressure, frame.question)
        }
        return ExplorationPresentation(situation, nodes, connections, affordances)
    }
}
