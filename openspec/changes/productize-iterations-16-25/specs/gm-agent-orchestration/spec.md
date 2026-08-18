## ADDED Requirements

### Requirement: The GM is a stable run-scoped agent role
The system SHALL assign the GM a stable AgentId and ActorId, a Session isolated by RunId, an explicit profile, bounded memory, and only the permissions exposed by the pinned world's enabled capabilities.

#### Scenario: Two Runs use the same bundled world
- **WHEN** the GM handles turns for both Runs
- **THEN** each Run SHALL use an independent Session and memory partition and SHALL NOT expose the other Run's player history or facts

#### Scenario: A capability is not enabled
- **WHEN** the pinned world omits that rule module
- **THEN** its Tools and permissions SHALL not be advertised or available to the GM

### Requirement: Each player intent has a stable game turn identity
The GameTurnOrchestrator SHALL assign or accept a stable TurnId and SHALL track accepted, running, awaiting-player, completed, cancelled, and failed outcomes without treating orchestration state as world fact.

#### Scenario: Same intent is submitted twice
- **WHEN** the same RunId and TurnId are retried after an uncertain client response
- **THEN** the orchestrator SHALL return or resume the original turn and SHALL NOT duplicate accepted Commands or Events

#### Scenario: Player clarification is required
- **WHEN** the intent lacks a target or decision needed for an allowed action
- **THEN** the GM SHALL request that specific clarification, enter an explicit awaiting-player state, and SHALL NOT invent the missing target or fact

### Requirement: GM context is projected from authoritative visible data
The GmContextProjector SHALL build each request from the pinned world definitions, current reducer state, player-visible recent Events, current scene, available actions, player input, budgets, and currently allowed Tool schemas.

#### Scenario: Hidden world fact is not player-visible
- **WHEN** a secret is not authorized for the player-facing GM context
- **THEN** the request, GM memory, and resulting public narration SHALL not expose that secret unless a committed reveal Event occurs

#### Scenario: Current scene changes
- **WHEN** a committed Event moves the player to another scene
- **THEN** the next GM request SHALL use the new scene's visible participants, actions, and tool scope rather than stale session text

### Requirement: The GM directs play through bounded authoritative actions
The GM MAY narrate, ask questions, call zero or more allowed Tools, and request eligible foreground NPC or Behavior follow-ups, but every objective change SHALL pass Tool Gateway, CommandValidator, WorldEngine, and EventStore.

#### Scenario: Model narrates an unsupported state change
- **WHEN** final text claims an item, condition, location, relationship, objective, or resource change without a corresponding committed Event
- **THEN** authoritative state SHALL remain unchanged and delivery SHALL flag, omit, or repair the inconsistent claim

#### Scenario: Tool parameters are invalid
- **WHEN** a Tool call violates Schema, permission, Definition reference, scene scope, or numeric bounds
- **THEN** Tool Gateway SHALL reject it and SHALL append no objective fact

#### Scenario: An NPC produces a public reaction
- **WHEN** an eligible foreground NPC commits a visible action or message during the Turn
- **THEN** the GM SHALL receive only that public result and MAY incorporate it into the final player-facing narration

### Requirement: Turn delivery reflects committed facts and bounded failure
After GM actions and allowed foreground follow-ups finish or reach budget, the application SHALL derive Presentation from current state and deliver final narration consistent with the committed visible Events.

#### Scenario: Provider fails before any command commits
- **WHEN** the model request times out or fails before a Tool command is accepted
- **THEN** the Turn SHALL expose a retryable failure and the EventLog SHALL remain unchanged

#### Scenario: Provider fails after a command commits
- **WHEN** an action Event was committed but final narration fails
- **THEN** the Event SHALL remain authoritative and the UI SHALL show a recoverable narration failure alongside the updated state

#### Scenario: Player cancels a running turn
- **WHEN** cancellation arrives before a child operation commits a fact
- **THEN** cancellation SHALL propagate and no later child result SHALL be published as part of that cancelled Turn
