# Movement work, handoff for the next agent

Read this first, then `docs/movement-api/DESIGN.md` (the architecture), then the project memories
(loaded automatically: `movement-api-design`, `spider-climbing-port`, `spider-model-blockbench`,
`mod-design-direction`, `notion-knowledge-base`). Notion ADR-3 holds the accepted design decision.

## 1. Project + working style

- Mod **Cauchemar** (NeoForge 1.21.1, GeckoLib), immersive-horror. First monster: the **Mother
  Spider**, a wall/ceiling climber that lurks in shadow. Author handle: **Payangar**, base package
  `com.payangar`.
- The user drives the vision; you bring the technical rigor. Rules that matter here:
  - **Validate each step in `runClient`** before moving on (the user does this). Compile after every
    change: `./gradlew compileJava --console=plain`.
  - Never bluff. Verify against the real code / decompiled sources / Nyf's reference.
  - No em dash in any human-facing text. Code and commits in English. Talk to the user in French.
  - Don't break what works. The climbing code is ported from Nyf's Spiders and is fragile.
  - To read Nyf's reference: `/lookup-mc-mod-source` (mod `nyfs-spiders`, branch `1.21.5` for any
    further climbing/pathfinding port; `1.20.4` was the original source for steps 1-4).
  - Commits via `/git-commit` when asked (not asked yet this work).

## 2. The vision (chantier 2)

A **generic movement/navigation API**, NOT spider-specific: it must work for plain ground mobs (the
base case) and be compatible with climbers; flying is a planned future profile. It could later become
a standalone lib. Architecture = **a common engine (Perception + global Plan + local Steering) + an
interchangeable LocomotionProfile (Ground / Climber / Flying)**. The three engine layers never know
about climbing; they talk to the profile through interfaces. Plan representation = **block grid +
flat A\*** (navmesh rejected: 2.5D, cannot do wall/ceiling climbing; HPA\* cut for v1, ground-only at
best, only if profiling demands). Key insight: **fluidity comes ~90% from the Steering layer, not the
plan representation.**

## 3. Current state (all compiles green)

Done and **validated in-game**:
- Chantier 1 (repair of the existing climbing port): `getGroundSide()` wired, yaw stabilized
  (`MIN_INPLANE_TURN_DIST`), path-following kept alive during climb micro-detachments
  (`isAttachedToSurface()` in `canUpdatePath`). Spin and stair-stall fixed.
- Steering: the existing-but-disabled funnel re-enabled (`findDirectPathPoints=true`). User: cleaner.
- Step 0: the `MoveIntent` contract proven (spider behaves identically routed through it).

Done, compiles, **awaiting in-game validation** (some are pure refactors = must be behaviour-identical):
- Shadow behaviour rewrite: `SeekDarknessGoal` is now a directed flood over cling-able cells (floor,
  wall, ceiling) that minimises light (objective light 0, darker = progress). Dark-biased pathfinding
  via `MotherSpiderEntity.getPathingMalus` (+0.4/light level) + dark-biased wander. Range raised
  (search 8->32, FOLLOW_RANGE 24->32). SPRINT tuned to (1.8 navSpeed, 5.0 anim).
- **Step 1 (the big extraction) + package reorg**: ALL climbing physics + attachment state moved out
  of `MotherSpiderEntity` into `ClimberLocomotion` (generic `<T extends Mob & ClimberHost>`, no
  dependency on the spider). `ClimberHost` (4 bridges) replaces the package-private bridges.
  `applyMovement` (was a separate `ClimberLocomotionExecutor`) fused into the profile, exposed via
  `IClimberEntity.applyMovement`. The entity is now a thin delegator. This is a PURE refactor.

**THE IMMEDIATE NEXT THING: the user must confirm in `runClient` that the step-1 extraction + reorg
changed nothing** (escalade walls/ceilings, corner rounding, flee-to-shadow, head tracking, observe
freeze on hit, foot IK all identical). If anything differs, it is a wiring/transcription bug in
`ClimberLocomotion` (the physics was copied line-for-line; suspect a mis-converted `this.` call).

## 4. Package layout (the structure is in place)

```
movement/                 The API. Extract to a lib later = move this package as one block.
    MoveIntent            Generic per-tick steering intent (inPlaneMove, speed, jumpDir).
    climber/              The Climber profile + the surface-aware nav machinery (18 files):
        ClimberLocomotion (the profile: physics travel() + applyMovement(intent) + frame + accessors)
        ClimberHost       (contract mob -> profile: climberSuperTravel/UpdateAnimation/JumpPower/AffectedByFluids)
        IClimberEntity, IAdvancedPathFindingEntity   (contracts a climbing mob implements)
        Orientation, ClimberMatrix4f, CollisionSmoothingUtil   (surface-frame math + attachment point)
        ClimberLook/Move/JumpController              (controllers; MoveController = steering)
        AdvancedWalkNodeProcessor                    (~1000-line surface-aware node evaluator)
        CustomPathFinder, AdvancedPathFinder         (A* + sided-path retrace)
        AdvancedGroundPathNavigator, AdvancedClimberPathNavigator, BetterSpiderPathNavigator (3 layers)
        DirectionalPathPoint, PathingTarget          (path nodes carrying faces / target)
entity/      MotherSpiderEntity (thin delegator; implements IClimberEntity, ClimberHost), MotherSpiderPartEntity
entity/ai/   SeekDarknessGoal
client/      MotherSpiderModel, MotherSpiderRenderer, SpiderLegIk
```

## 5. Next steps (in order)

1. **Validate** the step-1 + reorg in-game (above). Do not build on it until confirmed.
2. **Step 2, Steering as a clean common layer**: the steering already lives in `ClimberMoveController`
   and produces a `MoveIntent`. Lift the path-following / look-ahead / arrive into a profile-agnostic
   Steering that any locomotion profile can reuse. (Funnel is per-same-face only; cross-face is a hard
   break, the profile handles the corner.)
3. **Step 3, minimal Perception**: cached LOS (reuse `mob.getSensing().hasLineOfSight`) + one
   last-known-position slot. This unlocks **detection / ambush AI**, which is the user's real target
   and enables the planned "cross light behind the prey's back" tactic (the lighting is already
   traversable, just needs the malus dropped when the prey is not looking).
4. Reserve levers, only if a test shows the need: re-enable `checkObstructions` (note: it also changes
   the A\* via `getSafePoints`, not just stuck detection); `getMaxFallDistance() > 0`.

## 6. Tech debt / open questions (raised by the user)

- **Rename `BetterSpiderPathNavigator`** (name inherited from Nyf, nothing spider-specific in it) to
  e.g. `ClimberPathNavigator` for the API. Same spirit: audit other spider-named bits in `movement/`.
- **Consolidation candidates** (NOT dead code; all 18 files are used, verified): possibly collapse a
  navigator layer (Ground -> Climber -> BetterSpider = 3), merge `IClimberEntity` +
  `IAdvancedPathFindingEntity`, replace `ClimberMatrix4f` with JOML. This is a SEPARATE, risky chantier
  on working ported code; do it after validation, with care, one change at a time + compile + runClient.
- **Generalisation**: `ClimberLocomotion` is generic but `ClimberHost`/the profile are only exercised
  by one mob. The host abstraction is validated for real when the first **Ground** mob is added
  (deferred on purpose). At that point: write the `Ground` profile and a clean `LocomotionProfile`
  interface (the step-0 `LocomotionExecutor` interface was deleted as premature).
- Minor: a `docs/movement-api/DESIGN.md` build-order note about "Ground deferred" failed to apply
  (anchor mismatch); the deferral is captured here and in the `movement-api-design` memory instead.

## 7. Resources

- `docs/movement-api/DESIGN.md` (v2): the full layered design, revised after 4 adversarial reviews.
- Notion ADR-3 "Generic mob movement/navigation API": https://app.notion.com/p/38e901141af78145bf88c4d9d7d4527c
- State-of-the-art research dossier (artifact): https://claude.ai/code/artifact/aa8f0f64-5a5a-4c7e-a51a-6d63525f81e2
- Memories: `movement-api-design` (the live status, kept up to date), `spider-climbing-port` (the
  original port), `spider-model-blockbench`, `mod-design-direction`, `notion-knowledge-base`.
- Decompiled MC 1.21.1 sources were extracted to the session scratchpad `mc-src/` (re-extract from
  `~/.gradle/caches/neoformruntime/.../sourcesAndCompiledWithNeoForge_*.jar` if needed).
- Harness task list: tasks #1-11 (chantier 1 done; #3/#5 reserve levers; #9/#10 shadow+dark-bias;
  #11 step1+reorg). Plus the "next steps" above.
