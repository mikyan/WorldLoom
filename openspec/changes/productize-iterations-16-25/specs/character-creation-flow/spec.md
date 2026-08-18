## ADDED Requirements

### Requirement: New Runs have an explicit pre-game lifecycle
A new Run SHALL progress through versioned `CREATED` and `CHARACTER_CREATION` states before it can become `ACTIVE`, and lifecycle changes SHALL be reducer-derived from committed Events.

#### Scenario: New game starts
- **WHEN** the player selects a playable bundled world
- **THEN** the system SHALL create an independent Run pinned to that world version and SHALL enter its declared character creation flow

#### Scenario: Incomplete character creation is resumed
- **WHEN** the application restarts before confirmation
- **THEN** the Run SHALL remain pre-game and SHALL restore the non-factual creation draft without fabricating an active player Entity

### Requirement: Character creation UI is generated from the world profile
The application SHALL render only the mode, fields, options, bounds, defaults, costs, labels, and order declared by the validated CharacterCreationProfile and referenced Definitions.

#### Scenario: Bundled world uses a template profile
- **WHEN** the selected world exposes TEMPLATE choices
- **THEN** the UI SHALL show those templates and their resulting typed assignments without reading topic-specific keys

#### Scenario: Point-buy profile is enabled by another contract world
- **WHEN** another world declares POINT_BUY fields and a budget
- **THEN** the same shared UI SHALL display current cost and remaining budget for that world's DefinitionIds

### Requirement: All creation results use the same validator
FIXED, TEMPLATE, POINT_BUY, and NARRATIVE candidates SHALL pass profile, TypedValue, reference, required-field, bounds, and budget validation before confirmation, even if the bundled Alpha world enables only a subset of modes.

#### Scenario: Candidate exceeds a field bound
- **WHEN** a player or narrative resolver supplies an out-of-range assignment
- **THEN** confirmation SHALL be blocked with a field-specific problem and no creation Command SHALL be submitted

#### Scenario: Candidate references an undeclared field
- **WHEN** a creation candidate includes a topic field absent from the current world profile
- **THEN** validation SHALL reject it rather than silently ignoring or defaulting the value

### Requirement: Confirmed creation enters authoritative history atomically
The application SHALL translate a validated request into an idempotent typed GameCommand, and WorldEngine SHALL emit the Events that create the player Entity, initial components, initial scene membership, and `ACTIVE` lifecycle state.

#### Scenario: Player confirms a valid character
- **WHEN** the request passes validation for the pinned world version
- **THEN** one atomic event batch SHALL create the reducer-derived player state and activate the Run

#### Scenario: Confirmation is retried after an uncertain response
- **WHEN** the same command ID is submitted again
- **THEN** the system SHALL return the existing result and SHALL NOT create duplicate entities or initial facts

#### Scenario: Persistence fails during confirmation
- **WHEN** the complete creation event batch cannot be appended
- **THEN** the Run SHALL remain pre-game and SHALL be safe to retry without partial character components
