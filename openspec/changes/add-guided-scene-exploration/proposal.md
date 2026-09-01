## Why

Worldloom currently exposes some engine-valid progression actions before the scene, NPC dialogue, or discovered knowledge has established why the player would know those actions exist. A solo digital tabletop experience needs a persistent, spoiler-safe view of the current situation and gentle, grounded guidance so a new player can make meaningful choices without surrendering free-form play.

## What Changes

- Add a world-configured scene exploration model for observable details, discoverable location nodes, routes, threat cues, and player-visible objectives.
- Project a fog-of-war node map and compact situation board exclusively from facts the player has perceived or been told.
- Separate authoritative progression actions from player-facing suggestions; every suggestion must cite visible evidence, communicate intent and only expose risk or cost the character can reasonably estimate.
- Add progressive guidance levels: an onboarding layer, always-available editable examples, an explicit hint request, and a diegetic PM/NPC nudge when play stalls.
- Keep natural-language input primary. Map nodes, suggestions, and hints prepare or clarify an intent but never submit facts or bypass Command/Event validation.
- Re-author the first three scenes of `war-survival` as the acceptance slice, with multiple materially different approaches and a complete three-scene text playtest.
- Add responsive desktop and mobile presentation requirements without enlarging the existing compact character roster.
- Add validation and regression coverage for hidden-information leakage, unsupported recommendations, dead ends, replay, save/resume, and a ten-turn novice journey.

## Capabilities

### New Capabilities

- `guided-scene-exploration`: Spoiler-safe discovered maps, situation awareness, grounded suggestions, progressive hints, responsive presentation, and authoritative discovery/replay behavior.
- `bundled-war-guided-opening`: A redesigned three-scene opening for the bundled war scenario that demonstrates visible affordances, real route choices, NPC-led clues, pressure, and fail-forward outcomes.

### Modified Capabilities

None. The repository does not yet contain synchronized main specs; this change records compatibility with the existing pending `player-guidance`, `playable-world-contract`, and `bundled-war-survival-world` change artifacts in its design.

## Impact

- World package schema, validation, authoring guidance, content migrations, and contract fixtures.
- Application projections for visible scene knowledge, guidance, and discovered-map state.
- World engine Command/Event/Reducer support where discovering a location or route changes persistent player-visible knowledge.
- GM/NPC context projection and prompt rules for grounded scene framing and hint delivery.
- Shared Compose gameplay UI on Android, iOS, and Desktop.
- `war-survival` content version, golden routes, save compatibility handling, and release gates.
- Cross-topic validation against `station-ai` to prevent war-specific Runtime branches.
