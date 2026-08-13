# Creature AI (Brain pivot) — handoff for the next agent

Read this first, then the memory `creature-ai-brain` (loaded automatically; it holds the **verified
Brain API facts** so you do not re-study them) and Notion ADR-4/5/6. The older
`docs/movement-api/HANDOFF.md` + `DESIGN.md` cover the movement layer (ADR-3), which is DONE and
reused unchanged.

## 0. Project + working style (unchanged)

- Mod **Cauchemar**, NeoForge 1.21.1, modid `cauchemar`, GeckoLib. First monster: **Mother Spider**, a
  wall/ceiling climber that lurks in shadow. Author handle **Payangar**, base package `com.payangar`.
- The user drives the vision; you bring technical rigor. Rules that matter:
  - **Validate each step in `runClient`** (the user does this). **Compile after every change**:
    `./gradlew compileJava --console=plain`.
  - **Never bluff.** Verify against the real / decompiled code. Decompiled MC 1.21.1 sources are in the
    session scratchpad `mc-src/` (re-extract from `~/.gradle/caches/neoformruntime/.../sourcesAndCompiledWithNeoForge_*.jar`).
    `WardenAi.java` / `Warden.java` are the Brain templates.
  - **No em dash** in any human-facing text. Code + commits in English. Talk to the user in French.
  - Commits via `/git-commit` when asked (NOT asked yet for this work; nothing committed).

## 1. Big picture: we pivoted the AI from Goals to the vanilla Brain system

Decided with the user (ADR-4). The spider's AI is now **Brain-based** (Sensors -> Memories ->
Behaviors -> Activities), modelled on the Warden. The **movement API (ADR-3) is untouched**: the
standard `MoveToTargetSink` behavior consumes `WALK_TARGET` and drives `mob.getNavigation()` (our
`ClimberPathNavigator` + `Steering`). Two shared sub-systems were added:
- **Emotions** (`com.payangar.cauchemar.emotion`): 5 decaying drives, custom component (NOT Brain
  memories: their gradual decay does not fit the Brain's hard TTL). ADR-6.
- **Perception**: hearing only so far. ADR-5. **NOTE: the SIGHT half (Vision + acuities + a sight
  sensor) was deferred to P4**, where the stalk consumes it.

The bridge from emotions to the Brain: the entity's **appraisal** (in `customServerAiStep`) turns the
situation into emotion stimuli, and Brain behaviors read the emotions to gate themselves.

## 2. State: migration plan P0-P6

- **P0 DONE** — `emotion/EmotionType.java` (HUNGER/EXCITEMENT/ANGER/FEAR/CURIOSITY, each `restValue` +
  `driftPerTick`) + `emotion/Emotions.java` (value[type] 0..100, `add`/`set`/`get`/`atLeast`/`tick`
  drift + NBT `save`/`load`). Generic, reusable.
- **P1 DONE & validated** — Brain skeleton replacing the Goals. `entity/ai/SpiderAi.java` (MEMORY_TYPES,
  SENSOR_TYPES, `makeBrain`, `updateActivity`). Entity: `makeBrain`/`getBrain`/`customServerAiStep`,
  `registerGoals` removed. CORE = `Swim` + `LookAtTargetSink` + `MoveToTargetSink`. IDLE = wander + look.
  Fix shipped: the player-look is occasional (a `RunOne` of `SetEntityLookTarget` vs `DoNothing`), not a
  constant stare.
- **P2 DONE (hearing only)** — Hearing via a **plain `GameEventListener`** (inner class `SpiderEars` on
  the entity), NOT the full `VibrationSystem`. We started with `VibrationSystem` (Warden-style) but it
  emits the **traveling sculk particle** (hardcoded in `VibrationSystem.Ticker`, unsuppressable), which
  the user did not want, so we switched to the lighter `GameEventListener` (Allay `JukeboxListener`
  pattern). Trade-off accepted: NO travel delay, NO wall occlusion (immediate, hears through walls).
  `SpiderEars.handleGameEvent` filters to `GameEventTags.WARDEN_CAN_LISTEN`, ignores self + sneaking
  footsteps (`IGNORE_VIBRATIONS_SNEAKING` + `isCrouching`), computes a `noiseIntensity` (event type x
  distance falloff), then: loud (>= `LOUD_NOISE_INTENSITY`) -> FEAR + erase WALK_TARGET (flee now);
  moderate non-STEP -> CURIOSITY + set `DISTURBANCE_LOCATION` (TTL) + erase WALK_TARGET (investigate
  now); STEP -> small curiosity only. **The SIGHT sensor is DEFERRED to P4.**
- **P3 DONE** — Emotions wired on the entity (field + NBT `Emotions` tag + `getEmotions()` + ticked in
  `customServerAiStep`). Appraisal: bright light (>= `FEAR_LIGHT_THRESHOLD`) -> FEAR; on fire -> FEAR.
  Animation `MovementMode` set from FEAR (SPRINT if afraid else WANDER). **Fear -> flee**:
  `entity/ai/behavior/SeekShadow.java` (the old `SeekDarknessGoal` directed flood, now a Brain behavior
  gated by `FEAR >= threshold`, sets `WALK_TARGET` at SPRINT speed). **Curiosity -> investigate**:
  `entity/ai/behavior/Investigate.java` (walks to `DISTURBANCE_LOCATION`, stops `closeEnoughDist` short,
  and **gives up** clearing the memory when reached OR `CANT_REACH_WALK_TARGET_SINCE` is set, so it
  never freezes on an unreachable noise). Superseded by the P4 investigation rework below.
- **P4 (investigation half) DONE (2026-07-01; compiles green, awaiting runClient).** Cautious, vicious
  investigation + the seed of the perception layer. `updateActivity` precedence is now
  `afraid ? [PANIC, IDLE] : [INVESTIGATE, IDLE]` (INVESTIGATE valid only while `DISTURBANCE_LOCATION`
  is set; both PANIC and INVESTIGATE registered with erase-on-stop `{WALK_TARGET}` like IDLE). New
  `perception/` package (lean, ADR-5 seed): `SurfaceUtil` (canCling + rawLight, extracted from SeekShadow),
  `Sight` (point-to-point LOS via `level.clip(ClipContext.Block.COLLIDER)` + `scanVisibleLiving`),
  `VantagePointFinder` (bounded search: 16 sampled directions around the disturbance, snap to a clingable
  cell, LOS filter last, score by darkness+distance+height). `entity/ai/behavior/InvestigateDisturbance`
  is a phase FSM ORIENT (turn to face; early-exit if already sees a creature) -> REPOSITION (walk to a
  vantage, never to the source) -> OBSERVE (hold + scan for a visible creature) -> DECIDE. `InvestigationStyle`
  (record) maps emotions to params: anger -> faster/closer/briefer, hunger -> keener. Hearing now gates on
  **salience** (`intensity * attentionGain`, hunger+curiosity raise attention) instead of firing on every
  noise, and only picks up a new noise when idle (`isFreeToInvestigate`). The old `Investigate.java` was
  deleted. DECIDE has the hunt seam (perceived creature + hungry -> TODO log only).
- **Startle + fearful retreat-to-observe DONE (2026-07-01; compiles green, awaiting runClient).** A close
  noise she was not watching (`distance <= STARTLE_RADIUS=4` and no line of sight to it = "unanticipated")
  startles her: `SpiderEars` adds proximity-scaled fear, marks the source as `DISTURBANCE_LOCATION`, and
  drops the walk target. A proximity dread FLOOR in `updateEmotions` (fear held >= `DANGER_FEAR_FLOOR=40`
  while within `STARTLE_RADIUS` of the disturbance, but only if fear is already > 0 so it sustains rather
  than creates) keeps her afraid until she has backed off, making the retreat self-terminating. `PANIC` is
  now two behaviors in order: `RetreatToSafeVantage` (a OneShot: if a disturbance source is known, back off
  to a `VantagePointFinder` cell at a large `[SAFE_MIN=14, SAFE_MAX=22]` standoff that still has LOS to it,
  facing it, sprinting; returns false if none) then `SeekShadow` (ambient dread / no safe vantage -> flee
  to darkness). `VantagePointFinder.find` gained an optional `preferredDirection` (retreat biases vantages
  away from the threat). Emergent flow (no extra code): startle -> retreat while watching -> reach safety
  -> fear decays -> `INVESTIGATE` picks up the same disturbance and observes from cover. `SeekShadow`
  needs no gating: `RetreatToSafeVantage` (priority 10) claims WALK_TARGET first when a source exists, so
  `SeekShadow` (priority 11, absent WALK_TARGET) only runs when there is no source or no vantage.
  **IMPORTANT verified Brain fact that shaped this:** `Brain.getRunningBehaviors()` returns ALL RUNNING
  behaviors regardless of active activity, and an activity switch does NOT stop the outgoing activity's
  behaviors: each behavior must self-terminate via `canStillUse`. So `InvestigateDisturbance.canStillUse`
  checks `getActiveNonCoreActivity() == INVESTIGATE`, which makes fear (PANIC) and any future higher
  activity preempt it cleanly.
- **P4 (sight/hunt half) STILL TODO** — the full Vision/Acuity model + stalk/hunt. Build the rest of
  `perception/` per ADR-5: `Vision` (cone+range+falloff) + pluggable `Acuity` strategies (spider = a
  `LightAcuity` curve {dark->0, bright->1} OR'd with a `MovementAcuity`; a night-vision mob inverts it),
  a `SpiderSightSensor` writing a perceived-player memory (custom `MemoryModuleType` via DeferredRegister
  to `BuiltInRegistries.MEMORY_MODULE_TYPE` + `SENSOR_TYPE`). Spider's vision "forward" must use the
  SURFACE-FRAME view vector (it climbs: `orientation.getGlobal(yRot,xRot)`), not `getViewVector`. Then
  Hunger -> SEARCH activity; perceived prey + hungry -> STALK (circle, freeze when the player looks at
  her, excitement rises while unseen), wired into the `DECIDE` seam of `InvestigateDisturbance`. Target
  `updateActivity` order: PANIC > ATTACK > STALK > INVESTIGATE > SEARCH > IDLE.
- **P5 TODO** — Attack: Anger via the vanilla `HurtBySensor` (-> `HURT_BY_ENTITY`); **remove the
  hit->observe trigger** (a hit feeds anger now); repurpose the observe freeze to "player looking at me
  below the attack threshold". ATTACK gate = `excitement >= t OR anger >= t`: approach + `Pounce` (jump
  anim, reuse the climber directional jump) + `MeleeAttack`. High excitement/anger overrides fear-light.
- **P6 TODO** — Tuning + cleanup. See bugs below.

## 3. KNOWN BUGS / OPEN ITEMS (address these)

1. **Sprint-speed lag (user-reported). FIXED (2026-06-30; compiles green, awaiting runClient validation).**
   Symptom: when fear spiked mid-wander she kept moving at WANDER speed before sprinting, and "panicked
   but did not sprint". Two coupled causes, both confirmed in the decompiled sources:
   - **Locomotion latency.** Actual move speed = `WalkTarget.speedModifier`, read by `MoveToTargetSink`
     only when it (re)computes a path. `SeekShadow` required `absent(WALK_TARGET)`, so a slow wander
     target blocked the flee until it was reached/cleared (the "WALK_TARGET-window").
   - **Dual source of truth.** `MovementMode` (leg animation) was set from fear, while the body speed
     came from whatever behavior owned `WALK_TARGET`, so the two desynced during the transition.

   **Fix (clean, not a hack), using built-in Brain mechanics:**
   - `SeekShadow` moved out of IDLE into a real **`PANIC`** activity (reusing the vanilla Activity key;
     custom FLEE/STALK/SEARCH still come in P4). `SpiderAi.updateActivity` now does
     `setActiveActivityToFirstValid(afraid ? [PANIC, IDLE] : [IDLE])`. While fleeing, the idle wander is
     not even running (different activity), so she can never crawl while panicking.
   - IDLE is registered via the low-level `addActivityAndRemoveMemoriesWhenStopped` overload so it
     **erases `WALK_TARGET` when it stops**. `Brain.setActiveActivity` runs only on the front of an
     activity change and wipes the outgoing activity's erase-on-stop memories, so IDLE->PANIC drops the
     half-finished wander target for free, and `SeekShadow` claims a fresh SPRINT target in ~1 tick. No
     manual fear-edge detection, no path thrash (SeekShadow still re-decides only on arrival).
   - `MovementMode` is now derived from the **active `WALK_TARGET` speed** (`MovementMode.forNavSpeed`,
     in `customServerAiStep` after `brain.tick`, keeping the last mode when no target). Single source of
     truth = the walk target, so legs match the body in both directions (incl. finishing a sprint dash
     as fear decays). Residual: if afraid with no reachable darkness, `SeekShadow` sets no target and she
     stands (afraid, nowhere to flee) - acceptable, tune/extend in P4+.
2. **TEMPORARY debug logs** to REMOVE in P6: `SpiderEars.handleGameEvent` (`"[spider] heard noise: ..."`)
   and `InvestigateDisturbance.decideAndFinish` (`"[spider] investigation done: ..."`). Both added to
   confirm behaviour in runClient.
3. **NBT deprecation warning** (benign): `Emotions.load` uses `CompoundTag.contains/getFloat` and the
   entity uses `compound.contains(String,int)/getCompound`. Modernize in P6.
4. **Hearing has no occlusion/travel-delay** (plain `GameEventListener`, by design). If the user wants
   walls to block hearing, add a manual raycast occlusion check in `handleGameEvent` (the user was
   offered this and chose to proceed without for now).
5. The old `entity/ai/SeekDarknessGoal.java` is **orphaned/dead** (logic ported into `SeekShadow`).
   Delete it in P6.
6. Tolerances: fear thresholds, noise intensities, TTLs are initial. Tune in P6.

## 4. Tuning knobs (current values, all in `MotherSpiderEntity` unless noted)

- `FEAR_LIGHT_THRESHOLD = 8` (only light >= 8 frightens; below = calm/wander), `FEAR_PER_LIGHT_LEVEL = 3`,
  `FLEE_FEAR_THRESHOLD = 30` (public; SeekShadow + MovementMode read it), `FEAR_ON_FIRE_PER_TICK = 20`.
- `HEARING_RANGE = 16`, `DISTURBANCE_TTL = 200` (raw noise memory; `InvestigateDisturbance` re-latches it
  to 600 on start), `LOUD_NOISE_INTENSITY = 0.75`, `FEAR_PER_NOISE = 80`, `CURIOSITY_PER_NOISE = 60`.
- Investigation salience (in `MotherSpiderEntity`): `INVESTIGATE_SALIENCE_THRESHOLD = 0.35`,
  `HUNGER_ATTENTION_GAIN = 1.0`, `CURIOSITY_ATTENTION_GAIN = 0.5` (salience = intensity * (1 + gains)).
- `InvestigateDisturbance`: `OVERALL_TIMEOUT_TICKS = 600`, `ORIENT_TICKS = 15`, `VANTAGE_ARRIVAL_DIST = 2`,
  `SCAN_INTERVAL = 5`, `OBSERVE_SCAN_RADIUS = 4`, `HUNT_HUNGER_THRESHOLD = 60`.
- `InvestigationStyle` (anger 0 -> 100): speed WANDER(0.6) -> SPRINT(1.8); standoff [9,18] -> [5,11]
  (she observes from afar; the vantage search aims for the band midpoint); observe 100t -> 30t; hunger
  pulls standoff in by up to 3. She only observes in place if already within the band with LOS, else she
  repositions to a far vantage (no point-blank watching).
- `VantagePointFinder`: 16 directions (8 horizontal + 8 elevated ~35deg), `SNAP_VERTICAL_REACH = 3`,
  `DIRECTION_WEIGHT = 4` (weight of the optional away-from-threat bias).
- Startle / fearful retreat (in `MotherSpiderEntity` unless noted): `STARTLE_RADIUS = 4` (also the dread
  floor radius), `STARTLE_FEAR_MAX = 60`, `DANGER_FEAR_FLOOR = 40`; retreat standoff `SAFE_MIN = 14`,
  `SAFE_MAX = 22` (in `SpiderAi`). Note floor-radius(4) < investigate min standoff(9) so the calm-approach
  after retreat does not re-enter the dread zone (no oscillation).
- `EmotionType` drift per tick: HUNGER 0.01 (rest 100, rises), EXCITEMENT 0.2, ANGER 0.1, FEAR 2.0,
  CURIOSITY 0.3 (all rest 0 = decay). NOTE FEAR decays fast (2/tick); the retreat relies on the dread
  floor, not on the startle spike lasting.
- `SpiderAi`: PANIC = `RetreatToSafeVantage.create(SPRINT, 10, 16, 2)` then `SeekShadow.create(SPRINT,
  FLEE_FEAR_THRESHOLD, 0, 32)`; INVESTIGATE = `new InvestigateDisturbance()`; IDLE = `DarkBiasedStroll` + look-RunOne.

## 5. Files (creature AI)

```
emotion/EmotionType.java, emotion/Emotions.java              (P0, generic)
perception/SurfaceUtil.java                                  (canCling + rawLight, shared by SeekShadow + vantage finder)
perception/Sight.java                                        (point-to-point LOS + scanVisibleLiving; ADR-5 seed, generic)
perception/VantagePointFinder.java                           (bounded search for an observation cell with LOS at standoff; optional away-from-threat bias)
entity/ai/SpiderAi.java                                      (Brain wiring: memories, sensors, IDLE + INVESTIGATE + PANIC activities, priorityPairs)
entity/ai/InvestigationStyle.java                            (record: emotions -> approach speed / standoff / observe duration)
entity/ai/behavior/DarkBiasedStroll.java                     (IDLE wander, darkest of N samples)
entity/ai/behavior/SeekShadow.java                           (PANIC fallback: ambient fear -> flee to dark; uses SurfaceUtil)
entity/ai/behavior/RetreatToSafeVantage.java                 (PANIC primary: retreat from a known threat to a safe LOS vantage)
entity/ai/behavior/InvestigateDisturbance.java               (INVESTIGATE: FSM ORIENT->REPOSITION->OBSERVE->DECIDE; hunt seam)
entity/MotherSpiderEntity.java                               (Brain + emotions + appraisal + SpiderEars hearing/salience + NBT; MovementMode from WALK_TARGET)
entity/ai/SeekDarknessGoal.java                              (DEAD, delete in P6)
```
Movement API (ADR-3, reused untouched): `movement/` + `movement/climber/`.
(`entity/ai/behavior/Investigate.java` was deleted; replaced by `InvestigateDisturbance`.)

## 6. Resources

- Notion: ADR-4 (Brain) https://app.notion.com/p/38f901141af78158b241fbe34f371244 ; ADR-5 (Perception)
  https://app.notion.com/p/38f901141af78104a5a8dbffca487c24 ; ADR-6 (Emotions)
  https://app.notion.com/p/38f901141af781b4bdc9f0124cfca0bb . ADR-3 (movement) still valid.
- Memory `creature-ai-brain` = the verified Brain/VibrationSystem/GameEvent API facts (do not re-study)
  + the design. Other memories: `movement-api-design`, `mod-design-direction`, `spider-model-blockbench`,
  `notion-knowledge-base`.
- Harness tasks #5-11 = P0-P6 (P0-P3 completed, P4-P6 pending).
