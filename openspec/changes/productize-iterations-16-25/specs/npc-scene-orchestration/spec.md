## ADDED Requirements

### Requirement: NPC wake decisions use stable scene triggers and private perception
The NPC scene orchestrator SHALL create stable triggers from committed scene entry, communication, time, objective, plan, and relevant world Events and SHALL build each NPC input exclusively through its ContextProjector.

#### Scenario: Two NPCs perceive different facts
- **WHEN** an event is visible to NPC A but hidden from NPC B
- **THEN** NPC A MAY be awakened with that fact while NPC B's request, session, and memory SHALL contain no representation of it

#### Scenario: Same trigger is delivered twice
- **WHEN** the same Run, event sequence, NPC, and trigger kind are delivered again
- **THEN** the orchestrator SHALL reuse or ignore the duplicate and SHALL NOT create a second AgentTurn

### Requirement: Scene eligibility is world configured
NPC participation SHALL depend on current scene membership, declared perception/range, enabled wake policy, goals, and event relevance rather than a Runtime topic or WorldId branch.

#### Scenario: Player enters a scene with two present NPCs
- **WHEN** only one NPC's wake policy matches scene entry
- **THEN** only that NPC SHALL become eligible even though both entities are present

#### Scenario: NPC leaves the scene
- **WHEN** a committed movement Event removes the NPC from local perception
- **THEN** local player dialogue SHALL no longer include that NPC unless another communication capability applies

### Requirement: NPC scheduling is deterministic and budgeted
Eligible NPCs SHALL be ordered by trigger priority and stable AgentId and SHALL be constrained by per-scene concurrency, per-event wake count, Agent steps, timeout, token, and cost budgets.

#### Scenario: More NPCs are eligible than the wake limit
- **WHEN** an event matches more NPCs than policy permits
- **THEN** only the deterministic highest-priority subset SHALL run and remaining eligibility SHALL be explicitly deferred or skipped

#### Scenario: Foreground player turn needs Provider capacity
- **WHEN** background NPC work competes with active player/GM work
- **THEN** foreground work SHALL receive priority and background work SHALL yield, pause, or cancel according to policy

### Requirement: NPC public effects pass through authoritative interfaces
NPC dialogue, movement, item use, relationship changes, and other public actions SHALL be expressed through allowed Tool calls and typed Commands that produce Events.

#### Scenario: NPC performs a public action
- **WHEN** the NPC Tool call passes identity, manifest, target, and Schema validation
- **THEN** its Event SHALL join the current Turn causality and become available to GM narration and authorized presentation

#### Scenario: NPC produces only private reflection
- **WHEN** an NPC turn updates private beliefs or goals but performs no public action
- **THEN** private memory MAY update within its partition and no public Event or narration fact SHALL be fabricated

### Requirement: Player-facing outputs exclude private reasoning
Presentation, GM context, save previews, and public replay projections SHALL include only committed visible facts and explicitly public NPC messages, never raw model requests, private memories, or hidden perception.

#### Scenario: Player reviews an NPC conversation
- **WHEN** the time line displays that scene
- **THEN** it SHALL show revealed dialogue/actions and SHALL exclude private session and memory records
