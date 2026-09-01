## 1. Schema and compatibility baseline

- [x] 1.1 Define versioned topic-neutral exploration node, connection, affordance, scene-frame, suggestion-grounding, hint-tier, and public-risk schemas in the world package contract.
- [x] 1.2 Add manifest-gated exploration capability registration without making it a required dependency for compatible older worlds.
- [x] 1.3 Extend world-package validation for unique IDs, reference closure, initial visibility, suggestion grounding, legal dynamic targets, core-clue redundancy, and reachable fail-forward exits.
- [x] 1.4 Add serialization and migration fixtures proving old playable contracts still decode with exploration disabled and future schema versions fail with precise diagnostics.
- [ ] 1.5 Define the bundled content-version transition so existing war Runs remain pinned to their old content while new Runs select the guided opening.

## 2. Authoritative exploration state

- [x] 2.1 Add topic-neutral discovery Command/Event types for revealing or upgrading node, connection, and affordance knowledge with actor, cause, and visibility validation.
- [x] 2.2 Implement deterministic exploration module state and Reducer behavior without adding war-specific fields to core player or world state.
- [x] 2.3 Integrate scene entry, action outcome, observation, travel, and approved NPC knowledge reveal with atomic exploration events.
- [x] 2.4 Add replay, idempotency, invalid-ID, unauthorized-actor, duplicate-reveal, and event-serialization tests.
- [x] 2.5 Persist compatible exploration projections and verify corrupted snapshots fall back to EventLog without rediscovering or rerolling facts.

## 3. Player-visible projections and guidance

- [x] 3.1 Extend the application Presentation with a structured current-situation board and spoiler-safe discovered-node map.
- [x] 3.2 Replace automatic enumeration of every Action/Activity/Travel as a suggestion with independently authored, visibility-grounded suggestion projection.
- [x] 3.3 Support tool-backed and draft-only suggestions while keeping click behavior limited to editable input prefill.
- [x] 3.4 Project public rationale and tradeoff text only when their evidence is visible, and exclude hidden DCs, secret outcomes, private NPC knowledge, and unknown nodes.
- [x] 3.5 Add newcomer, standard, and immersive guidance preferences plus skip, review, and explicit “需要提示” behavior outside EventLog.
- [x] 3.6 Add evidence-based two-turn stall detection that ignores running, interrupted, failed-provider, and intentional roleplay-only turns and never auto-submits an action.

## 4. PM and NPC scene behavior

- [x] 4.1 Update PM context to provide the structured scene frame, current public exploration knowledge, legal grounded suggestions, and a concise open-ended scene question.
- [x] 4.2 Constrain PM narration so unsupported invented locations cannot become map, suggestion, save, or replay facts.
- [x] 4.3 Extend NPC perception projection with only that NPC’s authorized exploration subset and preserve private route knowledge until an approved reveal event commits.
- [x] 4.4 Implement authored Hint and Nudge delivery with no implicit fact changes and require a tool event whenever the hint actually reveals new knowledge.
- [ ] 4.5 Add Fake Provider tests for free-form observation, clarification, public `@` dialogue, private knowledge isolation, stalled-play nudges, and internal-ID suppression.

## 5. Bundled war opening acceptance slice

- [x] 5.1 Re-author the钟楼废墟 scene frame so the pharmacy, water-tower sign, nearby NPC conditions, patrol pressure, and immediate objective are visible before recommendations.
- [x] 5.2 Add safe-observational, risky-direct, and NPC-dependent opening approaches with distinct time, danger, relationship, knowledge, or resource consequences.
- [x] 5.3 Re-author the pharmacy branch around the emergency kit, rear exit, Mara’s medical knowledge, noise, time pressure, and fail-forward partial medicine outcomes.
- [x] 5.4 Re-author the under-fire branch with visible cover, the drainage breach, fast/risky, slow/safer, and resource-dependent escape approaches.
- [x] 5.5 Re-author the drainage convergence so Mara’s shelter route and Tomas’s water-tower route expose motives and known tradeoffs without identifying a correct or golden route.
- [x] 5.6 Declare progressive node/connection/affordance reveals for both success and failure paths, preserving existing stable Scene, NPC, Action, Objective, and Ending IDs where compatible.
- [x] 5.7 Add a minimal station exploration fixture and cross-topic contract test proving the same Runtime path works without war, pharmacy, or world-ID branches.

## 6. Responsive gameplay UI

- [x] 6.1 Add a compact safe-area-aware scene bar showing current location, known-exit count, the most urgent public pressure, and map/goal controls.
- [x] 6.2 Implement a dismissible desktop scene drawer with node map, situation board, known affordances, and no permanent expansion of the right character roster.
- [x] 6.3 Implement the mobile full-screen or bottom-sheet map flow, preserving chat scroll position and the composer draft across open, keyboard, rotation, and close transitions.
- [x] 6.4 Render map states for visited, discovered, rumored, blocked, and current nodes without exposing nodes absent from Presentation.
- [x] 6.5 Render compact suggestion chips with optional rationale/tradeoff details, editable input prefill, explicit hints, and guidance-strength controls.
- [ ] 6.6 Verify reduced-motion, touch targets, screen-reader labels, long localized names, large font, landscape desktop, and portrait mobile layouts.

## 7. Journey, recovery, and usability verification

- [x] 7.1 Add pure tests for schema validation, map projection, suggestion grounding, hint tiers, stall detection, and hidden-information exclusion.
- [x] 7.2 Add the documented ten-turn novice pharmacy journey covering observation, NPC dialogue, risk, discovery, resources, scene changes, route choice, save, resume, and replay.
- [x] 7.3 Add the under-fire alternate journey and assert it reaches drainage without retry loops or mentions of the emergency kit, culvert, checkpoint, endings, or internal IDs before reveal.
- [ ] 7.4 Run a live ten-turn MiMo v2.5 adapter smoke test using credentials loaded only from a Git-ignored local configuration, redact Provider bodies, and retain only safe pass/fail evidence.
- [ ] 7.5 Run Android debug packaging and physical-device portrait smoke tests, Desktop tests and distributable visual checks, and iOS Simulator compilation.
- [ ] 7.6 Conduct a novice usability pass where a tester must identify the current goal and at least two reasonable actions within 30 seconds in each opening stage; record confusion without coaching.
- [x] 7.7 Run `check`, relevant module desktop tests, migration verification, world-package golden routes, Android asset verification, and `git diff --check`.

## 8. Documentation and rollout

- [x] 8.1 Update `docs/DESIGN.md` with the event-sourced exploration boundary, scene-frame contract, guidance ladder, and responsive presentation rules.
- [x] 8.2 Update world-package authoring guidance with complete exploration and grounded-suggestion examples plus diagnostics for hidden or dangling references.
- [x] 8.3 Document the war content-version transition, old-Run resume behavior, rollback switch, and known mobile map choice.
- [x] 8.4 Review the final diff for secrets, topic-specific Runtime branches, silent state defaults, and accidental changes outside this OpenSpec scope before requesting implementation sign-off.
