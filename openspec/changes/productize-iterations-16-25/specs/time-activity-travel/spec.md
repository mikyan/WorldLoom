## ADDED Requirements

### Requirement: World time advances through explicit commands and events
The time capability SHALL use a world-defined TimeProfile and SHALL change time only through validated commands that emit auditable Events with previous value, delta, unit, reason, and causal Turn/Event IDs.

#### Scenario: Activity consumes time
- **WHEN** a validated activity declares a positive duration
- **THEN** its resolution SHALL emit the configured activity facts and an explicit time advancement in deterministic order

#### Scenario: Model mentions time passing without a command
- **WHEN** narration says hours or days passed but no time Command was accepted
- **THEN** authoritative world time SHALL remain unchanged

### Requirement: Activities are configured reusable resolutions
An ActivityDefinition SHALL declare prerequisites, duration, optional CheckProfile, costs, outcome effects, visibility, and presentation without embedding topic algorithms in Runtime.

#### Scenario: Player searches a location
- **WHEN** the current world enables a search activity and its prerequisites hold
- **THEN** the activity SHALL resolve its configured check/cost/effects and SHALL produce typed Events linked to one activity ID

#### Scenario: Activity prerequisite fails
- **WHEN** the player lacks a required state, item, location, or available time
- **THEN** validation SHALL reject the activity before costs or time are applied

### Requirement: Travel resolves routes atomically
A TravelDefinition SHALL reference origin, destination, duration/cost rules, optional checks, and configured arrival or interruption effects; travel SHALL not directly mutate entity location from UI or Agent code.

#### Scenario: Travel reaches destination
- **WHEN** a route resolves without interruption
- **THEN** the system SHALL commit time/cost facts and one validated location transition to the destination

#### Scenario: Travel is interrupted
- **WHEN** a recorded check or random table selects an interruption
- **THEN** the traveler SHALL enter the configured interruption scene/state and SHALL not also be projected as having arrived

### Requirement: Scheduled triggers are deterministic and replayable
Time advancement SHALL evaluate due world schedules and Behavior triggers in stable order using recorded random facts where required.

#### Scenario: Multiple schedules become due together
- **WHEN** one time advancement crosses several due thresholds
- **THEN** due triggers SHALL execute in configured priority and stable ID order with auditable causality

#### Scenario: Run is replayed
- **WHEN** the same time, activity, travel, and RandomRecord Events are replayed
- **THEN** the same schedules, interruption facts, and resulting state SHALL be reconstructed without reading system time or rerolling
