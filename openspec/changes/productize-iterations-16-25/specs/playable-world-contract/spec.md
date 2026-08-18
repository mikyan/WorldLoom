## ADDED Requirements

### Requirement: A playable world declares a complete entry contract
A world that declares playable-world/v1 SHALL provide a valid character creation profile or explicit prebuilt-player profile, an initial scene, required module capabilities, at least one player-visible objective, failure progression policy, at least one reachable ending, and presentation bindings for critical state.

#### Scenario: Required entry reference is missing
- **WHEN** a playable world manifest references no initial scene, creation profile, objective, or ending
- **THEN** static validation SHALL reject the world with the precise missing contract path

#### Scenario: World does not need a planned content category
- **WHEN** a playable world intentionally defines no inventory, relationship, travel, or NPC content
- **THEN** validation SHALL accept the omission if no declared route or enabled capability references that category

### Requirement: Playable content has a statically valid reference closure
The validator SHALL resolve every scene, entity, activity, route, item, condition, relationship dimension, quest, clock, Behavior, presentation, module, and ending reference used by the declared playable routes.

#### Scenario: Behavior effect targets an unknown definition
- **WHEN** a Behavior in the world references an undeclared Command target or DefinitionId
- **THEN** the world SHALL be rejected before a Run can be created

#### Scenario: Ending is unreachable in the content graph
- **WHEN** no valid scene, objective, clock, or Behavior transition can reach a declared ending
- **THEN** validation SHALL report the unreachable ending and SHALL NOT mark the world playable

### Requirement: A Fake Agent can complete the golden route
Every bundled playable world SHALL include at least one deterministic route fixture that creates a Run, enters the initial scene, performs configured actions and checks, and reaches a terminal Run state through public application interfaces.

#### Scenario: Golden route completes
- **WHEN** the route fixture is executed with its fixed random records and Fake Agent decisions
- **THEN** the Run SHALL reach the expected ending and replay SHALL reconstruct the same final state and causal events

#### Scenario: Route requires a Runtime topic branch
- **WHEN** completion succeeds only because production Runtime checks WorldId, topic name, or a bundled DefinitionId
- **THEN** the cross-topic contract test SHALL fail the playable-world acceptance

### Requirement: Failure advances or terminates explicitly
Every player-facing check or action used on a required route SHALL define how failure produces a new scene, cost, state, retry condition, or terminal ending rather than silently stalling the Run.

#### Scenario: Required check fails
- **WHEN** a route fixture records the failure outcome of a required check
- **THEN** the EventLog SHALL contain the configured consequence or transition and the UI projection SHALL expose the resulting next state
