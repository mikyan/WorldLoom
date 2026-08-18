## ADDED Requirements

### Requirement: The bundled adventure critical journey is release gated
The Alpha gate SHALL exercise world selection, new Run, character creation, Provider setup without secret exposure, natural-language turns, scene/time/activity/travel, adventure state, Behavior/NPC participation, multiple endings, save/resume, timeline, and replay verification.

#### Scenario: Closed Alpha candidate is built
- **WHEN** a commit is proposed as an Alpha candidate
- **THEN** automated shared tests, all bundled route contracts, Android build, Desktop package/run smoke, iOS Kotlin compilation, database migrations, and available host integration tests SHALL pass

#### Scenario: Platform-only journey cannot be automated
- **WHEN** Keychain/Keystore, lifecycle, packaging, or device performance requires manual testing
- **THEN** the release record SHALL contain a completed manual matrix or explicitly block the candidate

### Requirement: Gameplay remains responsive under Agent work
The Alpha gate SHALL measure startup, new-game flow, foreground turn responsiveness, long narrative timeline, Behavior/NPC follow-up, memory/compaction competition, memory use, and steady-state frame rate against the design baseline.

#### Scenario: Background work competes with player input
- **WHEN** NPC or compaction work runs while a player submits a foreground turn
- **THEN** foreground work SHALL retain priority and measured responsiveness/memory/frame behavior SHALL remain within the documented target or block release

### Requirement: Failure recovery preserves facts and playability
The system SHALL test interruption at character creation event batches, Agent requests, Tool loops, EventStore appends, Behavior/NPC scheduling, snapshot publication, and save resume.

#### Scenario: Network fails before a world command commits
- **WHEN** a Provider-backed turn cannot produce an accepted Command
- **THEN** no partial objective fact SHALL be appended, the Run SHALL remain valid, and the UI SHALL offer bounded retry

#### Scenario: Network fails after an event commits
- **WHEN** a Tool action committed but narration failed
- **THEN** resume SHALL preserve the Event and SHALL recover player-visible delivery without reverting or duplicating the fact

### Requirement: Security and topic-boundary audits block release
The Alpha gate SHALL scan source, world content, logs, databases, UI projections, replay, and failure messages for credentials, raw model bodies, NPC private context, unrevealed secrets, permission bypasses, unsafe archive behavior, and production Runtime topic branches.

#### Scenario: War-specific branch appears in Runtime
- **WHEN** production Runtime or shared UI branches on `war-survival`, `war.*`, or a bundled scene/ending ID
- **THEN** release SHALL fail until the content dependency is removed and covered by a cross-topic test

#### Scenario: Private data appears in player timeline
- **WHEN** an audit detects NPC private memory or a hidden fact in public presentation
- **THEN** release SHALL fail and a regression test SHALL be required

### Requirement: Release evidence is reproducible
Each Alpha candidate SHALL record source commit, app/content versions, supported platforms, database/world/package Schema versions, route results, validation commands, known limitations, and artifact hashes.

#### Scenario: Candidate passes all gates
- **WHEN** automated and required manual checks succeed
- **THEN** the project SHALL produce installable Android and Desktop artifacts plus an iOS build handoff, with no uncommitted repository changes
