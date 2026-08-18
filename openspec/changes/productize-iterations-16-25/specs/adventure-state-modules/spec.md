## ADDED Requirements

### Requirement: Adventure capabilities are manifest gated
Inventory, Condition, relationship, Quest, and Clock capabilities SHALL register Definitions, Tools, Commands, Events, Reducers, projections, and UI only when their compatible modules are enabled by the world manifest.

#### Scenario: World omits the Quest module
- **WHEN** that world is loaded
- **THEN** Quest Tools and projections SHALL be absent while unrelated gameplay remains available

#### Scenario: Module version is incompatible
- **WHEN** a world requires a module version outside Runtime compatibility
- **THEN** world loading SHALL fail before a Run is created

### Requirement: Inventory operations are atomic and definition driven
Inventory SHALL represent item Definitions, instances or quantities, holders, and configured instance fields, and SHALL update them only through validated add, remove, transfer, consume, equip, or use Commands supported by enabled capabilities.

#### Scenario: Player consumes a scarce item
- **WHEN** an item-use Command passes ownership, quantity, and item-rule validation
- **THEN** consumption and configured effects SHALL commit atomically or not at all

#### Scenario: Transfer exceeds available quantity
- **WHEN** a transfer requests more than the source holder owns
- **THEN** the command SHALL be rejected with no source or destination change

### Requirement: Conditions use typed definitions and lifecycle
ConditionDefinition SHALL describe allowed fields such as intensity, stack, duration, tags, and presentation; apply, update, expiry, and remove operations SHALL emit typed Events.

#### Scenario: Time advancement expires a condition
- **WHEN** a condition's configured duration reaches its expiry threshold
- **THEN** an explicit expiry/removal Event SHALL update the reducer projection and presentation

### Requirement: Relationships are private-aware typed state
Relationship state SHALL use world-defined dimensions and TypedValues between stable entity IDs, with visibility rules controlling Agent context, player presentation, and replay export.

#### Scenario: NPC trust changes privately
- **WHEN** a relationship effect is not player-visible
- **THEN** the objective relationship projection MAY change while public UI and replay SHALL omit the hidden value until a reveal policy allows it

### Requirement: Quests and clocks expose auditable progress
QuestDefinition and ClockDefinition SHALL define stages, objectives/segments, completion/failure conditions, visibility, and configured effects; progress SHALL change only through Commands and Events.

#### Scenario: Objective completes
- **WHEN** committed facts satisfy a configured objective transition
- **THEN** Quest progress SHALL advance once, emit its visible update, and schedule any enabled Behavior effects

#### Scenario: Clock fills
- **WHEN** a validated clock-advance Command reaches its final segment
- **THEN** the Clock SHALL emit one completion fact and SHALL not execute completion effects more than once for the same causal chain

### Requirement: Adventure state is presented without topic keys
Shared UI SHALL consume PresentationDefinitions and module projections for items, conditions, relationships, objectives, and clocks.

#### Scenario: Station world uses the same Clock module
- **WHEN** `station-ai` declares a station-specific clock Definition
- **THEN** the shared clock UI SHALL render it without a station or war branch in production Runtime
