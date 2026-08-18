## ADDED Requirements

### Requirement: Players can manage multiple independent Runs
The application SHALL present a save catalog derived from Run metadata, pinned bundled world version, lifecycle, last committed Event, bounded preview projection, integrity status, and last-played application metadata.

#### Scenario: Player starts a second Run
- **WHEN** another character flow begins for the same bundled world version
- **THEN** the system SHALL create an independent RunId, EventLog, snapshots, Agent sessions, private memories, Behavior causality, and NPC scheduling partition

#### Scenario: Save catalog metadata is stale
- **WHEN** catalog projection is missing or inconsistent
- **THEN** it SHALL be rebuilt from Run/EventStore and bundled world references without modifying objective facts

### Requirement: Resume validates content and history integrity
Before entering play, the application SHALL verify the pinned world version/hash, event sequence, event integrity, snapshot compatibility, reducer reconstruction, and unfinished Turn recovery policy.

#### Scenario: Latest snapshot is invalid but EventLog is valid
- **WHEN** snapshot validation fails and the complete EventLog can be replayed
- **THEN** the application SHALL ignore the snapshot, rebuild state from Events, and report recovered status

#### Scenario: Event history is discontinuous or tampered
- **WHEN** sequence or integrity validation fails
- **THEN** normal resume SHALL be blocked and original data SHALL remain available for diagnosis

#### Scenario: Application stopped during an Agent turn
- **WHEN** committed Events exist but final narration was not delivered
- **THEN** resume SHALL preserve those facts and SHALL present or regenerate only the missing non-authoritative delivery according to policy

### Requirement: The play timeline exposes visible causal facts
The game UI SHALL provide a virtualized narrative/event timeline that links player intent, actor, Commands, checks, RandomRecords, Events, Behavior causality, NPC public actions, and presentation text according to visibility policy.

#### Scenario: Player opens a resolved check
- **WHEN** a visible check is selected
- **THEN** the timeline SHALL show formula inputs, modifiers, outcome tier, and associated recorded random facts without rerolling

#### Scenario: Behavior effect is selected
- **WHEN** a visible Event was derived from Behavior
- **THEN** the timeline SHALL identify the visible triggering fact and result without exposing hidden guard data

### Requirement: Replay projections protect private information
An in-app public replay projection SHALL contain only the pinned public world identity, player-visible Events, public NPC messages, RandomRecords, and verification metadata.

#### Scenario: Replay contains NPC activity
- **WHEN** the player reviews or later exports the public projection
- **THEN** API keys, raw Provider content, NPC private memories, hidden world facts, and unrevealed source material SHALL be absent

#### Scenario: Replay is verified offline
- **WHEN** the application verifies the projection and underlying local EventLog
- **THEN** ordering and recorded random facts SHALL be checked without contacting a model Provider
