## ADDED Requirements

### Requirement: The bundled war world is a complete data-defined short adventure
The bundled `war-survival` content version SHALL contain an entry, creation profile, initial scene, scene graph, characters, NPCs, items/resources, activities/routes, objectives/clocks, Behavior, presentation, and endings sufficient for a complete short playthrough without Runtime topic code.

#### Scenario: Application starts with bundled content
- **WHEN** no user-created or imported world exists
- **THEN** the world selector SHALL offer the validated bundled war adventure and SHALL allow a new Run without file import or world generation

#### Scenario: Bundled content is removed from production Runtime source
- **WHEN** production Runtime modules are scanned
- **THEN** war characters, locations, resource IDs, scene IDs, ending IDs, and WorldId branches SHALL exist only in package/content or test-fixture scope

### Requirement: The adventure offers a bounded complete play session
The Alpha content SHALL target approximately 45–90 minutes, SHALL contain a sequence or graph of 7–14 key scenes/stages, at least two interactive NPCs, at least three meaningful activity/travel choices, and at least three distinguishable endings.

#### Scenario: Success-oriented route is played
- **WHEN** the golden route uses fixed Fake Agent decisions and RandomRecords
- **THEN** it SHALL progress from character creation through scenes and objectives to a successful or hopeful terminal ending

#### Scenario: Cost or failure route is played
- **WHEN** checks fail or costly choices are selected
- **THEN** the world SHALL continue through configured consequences to a different scene, objective state, or terminal ending rather than stall

### Requirement: Survival pressure is represented through generic definitions and modules
Health, hunger, fatigue, stress, supplies, trust, danger, or other war content SHALL use namespaced Definitions and enabled reusable modules rather than fixed Runtime fields.

#### Scenario: Rest activity resolves
- **WHEN** the player chooses a configured rest activity
- **THEN** time, costs, checks, conditions, and resource changes SHALL use generic Activity/Time/Condition/numeric capabilities and typed Events

#### Scenario: A station fixture uses equivalent modules
- **WHEN** `station-ai` enables a generic capability with station-specific Definitions
- **THEN** the same Runtime path SHALL load and resolve it without war content dependencies

### Requirement: Hidden information respects visibility boundaries
Unrevealed war facts, NPC beliefs, future events, route consequences, and ending thresholds SHALL be available only to authorized world/Agent projections until committed reveal conditions occur.

#### Scenario: NPC does not know a hidden event
- **WHEN** that NPC has not perceived or learned the fact
- **THEN** its Agent context and dialogue SHALL not reveal the fact

#### Scenario: Fact is revealed by play
- **WHEN** a configured discovery or communication Event occurs
- **THEN** authorized player/NPC knowledge and presentation SHALL update through explicit facts or module projections

### Requirement: Bundled routes are regression fixtures
The world SHALL provide deterministic golden, costly, and failure-oriented automated routes plus invariants for objective progress, ending identity, event ordering, random audit, replay, and secret visibility.

#### Scenario: Runtime change alters a route
- **WHEN** a code or Schema change produces a different route Event, ending, or visibility result
- **THEN** contract tests SHALL fail unless an intentional content/runtime version migration updates the fixture and design record
