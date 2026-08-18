## ADDED Requirements

### Requirement: Behaviors are scheduled only from committed events
The Behavior orchestrator SHALL observe successfully appended GameEvents and SHALL identify enabled, version-compatible BehaviorDefinitions from the Run's pinned bundled world version.

#### Scenario: Event matches multiple behaviors
- **WHEN** one committed event matches multiple enabled triggers
- **THEN** candidates SHALL be ordered deterministically by configured priority and BehaviorId before evaluation

#### Scenario: Event append fails
- **WHEN** a proposed event batch is not committed
- **THEN** no Behavior SHALL be scheduled from that uncommitted candidate

### Requirement: Behavior effects use current state and the Command boundary
Before firing, the orchestrator SHALL load current reducer state, evaluate the validated guard with immutable trigger context, and submit each effect through the registered Behavior Command Sink and CommandValidator.

#### Scenario: State changes before queued behavior runs
- **WHEN** the behavior guard is no longer true in current state
- **THEN** execution SHALL record a non-firing outcome and SHALL submit no effect Command

#### Scenario: Effect command is rejected
- **WHEN** a Command fails permission, reference, Schema, or domain validation
- **THEN** behavior execution SHALL follow its failure policy, record bounded diagnostics, and SHALL NOT mutate GameState directly

### Requirement: Behavior causality is auditable and bounded
The orchestrator SHALL track root Event, parent Event, TurnId, BehaviorId/version, causal depth, per-behavior firing count, total derived Commands, and repeated signatures for each chain.

#### Scenario: Two behaviors recursively trigger each other
- **WHEN** a chain reaches a configured depth, firing, command, or repeated-signature limit
- **THEN** further derived execution SHALL stop deterministically without rolling back prior committed facts

#### Scenario: Completion behavior receives the same trigger twice
- **WHEN** the same Run, event sequence, BehaviorId, and idempotency key are scheduled again
- **THEN** the behavior SHALL not advance a Quest, Clock, scene, or ending twice

### Requirement: Behavior-driven progression replays deterministically
Given the same pinned world version, initial state, committed Events, Behavior versions, and RandomRecords, scheduling and accepted effects SHALL reproduce the same ordered facts.

#### Scenario: Golden route is replayed
- **WHEN** the bundled world route is verified
- **THEN** the verifier SHALL detect missing, extra, reordered, or differently parameterized Behavior-derived effects without rerolling
