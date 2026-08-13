# Cauchemar Movement API, design (v2, revised after 4 adversarial reviews)

Status: DRAFT, revised. The high level decisions are in Notion ADR-3. v1 was rewritten after four
adversarial design reviews (plan, steering, agent/integration, cross-cutting) that all read the real
code. This version folds in their findings. Cut scope is recorded in section 11.

## 1. Goals and constraints

- Generic. Works for ground mobs (the base case), compatible with climbers, flying as a planned
  future profile. NOT spider specific.
- Mobs that are aware of their environment and move with intent, vs the vanilla "blind A* + naive
  turn-toward-waypoint controller".
- Optimization is first class, but sized to the real target (see below), not speculative.
- v1 integration scope = Option A (the mod's own mobs, by composition). Option B is narrowed (see 8).

Performance target (PROVISIONAL, to confirm with the user). Cauchemar is hand-crafted encounters:
roughly 1 to 4 active monsters near a player, paths up to ~32 to 48 blocks, pathfinding kept well
under ~0.5 ms/tick amortized on the server thread. This number is what justifies "flat A* and
synchronous, no async, no HPA*" below. If the target ever becomes "many agents over long ranges",
revisit section 5.

## 2. Architecture

Each mob gets an `Agent` = three common layers plus one interchangeable profile:

- Perception (common, graduable, minimal in v1)
- Plan (common): flat budgeted grid A* in v1
- Steering (common): turns a route into a continuous, smoothed `MoveIntent`
- LocomotionProfile (the single point of variation): Ground / Climber / Flying

The three common layers never reference climbing. They talk to the profile through its interface.
But the agent is NOT a single tick callback: it is a pipeline spread across the vanilla tick (see 2b
and 8), exactly like the existing drive chain.

## 2b. The 1.21.1 server tick order (the load-bearing fact)

Verified against decompiled `Mob.serverAiStep` / `LivingEntity.aiStep`:

    isImmobile()?  -> if true, xxa/zza zeroed and serverAiStep is SKIPPED entirely
    goalSelector.tick()        // a Goal calls mob.getNavigation().moveTo(target)
    navigation.tick()          // picks next node, calls moveControl.setWantedPosition / setMoveTo
    customServerAiStep()
    moveControl.tick()         // sets yRot / speed / zza / xxa  (this is STEERING today)
    lookControl.tick(); jumpControl.tick()
    travel(xxa, yya, zza)      // MotherSpiderEntity.travel() -> travelOnGround() = the PHYSICS

So the existing climber is a three-stage pipeline: navigation.tick (plan + pick waypoint) ->
moveControl.tick (steering: yaw/zza) -> travel() (execution: surface physics + orientation rebase).
This dictates where each API layer must live (section 8).

## 3. Shared types (corrected)

- `AgentBody`: everything planning/execution needs from the mob, so planning never touches `Mob`
  directly. Must enumerate: bbox, step height, max fall distance, can-float / can-climb-in-water /
  can-climb-in-lava, the malus table, can-pass-doors, plus the agent start state (position, onGround,
  inWater) captured at request time. This list is a hard prerequisite for any future off-thread work.
- `WorldSnapshot`: read access for planning. NOTE: a `PathNavigationRegion` is NOT a snapshot (it
  holds live chunk references). v1 plans synchronously on the main thread, so this is just the region.
  A real immutable capture is only needed if we ever go async (we do not, in v1).
- `NavCell`: a planning cell = block coords + an OPAQUE `cellData` owned by the profile (for the
  climber this packs the per-face data; the generic layers never interpret it). Do NOT put a
  `Direction face` in the generic type.
- `Route`: ordered `NavCell` + helpers. Carries the profile's center/feet and face conventions via
  `cellData`, not as generic fields.
- `LocalFrame`: up + forward + right at the current pose, from the profile (the climber's existing
  `Orientation`). IMPORTANT: it mutates mid-tick (the climber rebases yaw/pitch in `travel()`), so it
  is this-tick state, never cached across phases (see 4.2, 6).
- `MoveIntent` (corrected, was lossy): a desired direction expressed IN the `LocalFrame` (the profile
  projects it to world at apply time), a tangent-plane speed, an optional `jumpDir` (a `Vec3`, not a
  bool, because corner-rounding jumps are directional), and an optional `FaceTransition` hint (target
  face + suggested jump axis). Ground ignores `jumpDir`/`transition`.
- `CellClass` / cost: NOT a flat enum. Cost is a FUNCTION of (agent, cell, direction, incoming side),
  modelled on the existing `IAdvancedPathFindingEntity.getPathingMalus(...)` contract
  (negative = forbidden, 0 = preferred, positive = added cost). A cell can yield multiple candidate
  edges (drop / step-up / bridge), so this is not one value per cell.

## 4. LocomotionProfile

### 4.1 Navigability (consumed by the Plan)

    CellClass classify(WorldSnapshot world, int x, int y, int z, AgentBody body);
    void expandNeighbors(WorldSnapshot world, NavCell from, AgentBody body, NeighborSink out);

`NeighborSink.accept(NavCell cell, float edgeCost, EnumSet<EdgeFlag> flags)` where flags include
DROP / STEP_UP / TRANSITION. This preserves what the real `getNeighbors` does: 0, 1 or 2 results per
direction, asymmetric (one-way drops), and diagonals that are gated on the orthogonal results.
`expandNeighbors` is allowed to be multi-pass and stateful within one expansion. A `Navigability`
instance is NOT reentrant (it owns caches with a prepare/done lifecycle), so one per worker if ever
pooled.

Mapping:

| Profile  | classify (where can I stand/be)            | expandNeighbors (connectivity)              |
|----------|--------------------------------------------|---------------------------------------------|
| Ground   | solid floor below + body clearance         | 8 horizontal neighbours + step up/down      |
| Climber  | any adjacent solid face + body clearance   | same-face neighbours + corner/transition    |
| Flying   | traversable free air                       | 26 neighbours in 3D                         |

Reality of the climber wrap (was understated): `AdvancedWalkNodeProcessor` is a ~1000-line stateful
`WalkNodeEvaluator` subclass, and the per-cell face is produced in TWO passes (the evaluator packs
candidate sides, then `AdvancedPathFinder.retraceSidedPath` assigns the walked `pathSide` and inserts
transition nodes at corners). Wrapping it behind `classify`/`expandNeighbors` is real refactoring,
not a thin adapter. The interface above is shaped to fit it, not the other way around.

### 4.2 Execution (the PHYSICS, runs in `travel()`, not at navigation time)

    LocalFrame currentFrame(Agent agent);
    void applyMovement(Agent agent, MoveIntent intent);   // CALLED FROM travel(), post-controls
    LocomotionConstraints constraints();   // step height, can fall, max fall, canFloat,
                                           // canClimbInWater, canClimbInLava, ...

Reality (corrected): for the climber, `applyMovement` IS the body of the overridden `travel()` /
`travelOnGround()` on the entity (sticking force, surface-projection probing, corner re-attach, AND
`updateOffsetsAndOrientation()` which rebases yaw/pitch). It is NOT in `ClimberMoveController`. The
Ground profile's execution is vanilla-like (`super.travel()`).

Frame contract: `currentFrame` is this-tick state (the rebase happens inside execution). Steering
therefore outputs a direction the profile re-projects against the CURRENT orientation at apply time,
never a precomputed yaw delta. Inner corners (collision normal not unit length) have no single frame;
the profile defines `currentFrame` there (today: suppress projection, let physics carry through).

## 5. Plan layer (flat budgeted grid A* for v1)

- v1: flat grid A* over the Navigability facet. This is today's `CustomPathFinder`/`AdvancedPathFinder`
  generalized to call `classify`/`expandNeighbors`. It is already budgeted (`maxExpansions`) and has a
  pluggable heuristic. Tune `maxExpansions` + heuristic to the encounter range (section 1).
- Cost: ONE authoritative source. Fold the planner's distance term and the profile malus into a single
  profile-owned edge cost (today they are split between `CustomPathFinder.distance` and node
  `costMalus`, which would double-weight moves if generalized naively).
- Synchronous, time-sliced: if a single path is too big for one tick, suspend the A* loop (it is a
  clean `while` over a `BinaryHeap`) and resume next tick. NO threads.
- Recompute: keep the existing throttled `recomputePath` (20-tick) + stuck-driven replan.

HPA*, async, D* Lite: explicitly OUT of v1 (section 11). The `Navigability` abstraction keeps HPA*
*possible* later, but it would be ground-only (the climber's (x,y,z,face) node space plus synthesized
transition nodes does not cluster cleanly), and only if profiling at the real agent count shows flat
A* is the bottleneck. The earlier "HPA* is profile-agnostic" claim was wrong and is dropped.

## 6. Steering layer

Input: the `Route`, the agent state, the `LocalFrame`. Output: a `MoveIntent`. Runs where the current
steering runs (`moveControl.tick`, i.e. the climber's `ClimberMoveController` becomes the Steering
host; see 8).

Behaviours:
- Path following with FUNNEL smoothing, but PER same-face run only, with a hard break at each face
  transition (a cross-face funnel has no shared plane to funnel through). Do not promise "removes the
  staircase look" across corners. REUSE the existing `findDirectPathPoints` / `canMoveDirectly`
  raycast shortcutter (currently disabled, `findDirectPathPoints=false`, tagged //todo); fix and
  generalize it rather than writing a new funnel.
- Look-ahead: aim a point further along the smoothed path, computed in frame-local terms and
  re-projected after any rebase (otherwise it jitters at transitions; this is what
  `MIN_INPLANE_TURN_DIST` already guards).
- Arrive: decelerate near the goal. MUST reconcile with the existing MC-94054 overrun behaviour
  (`BetterSpiderPathNavigator` keeps pushing past path end); decide which wins to avoid oscillation.
- Face transition is a STEERING decision, not a profile-hidden one. Steering reads the upcoming cell's
  face (`pathSide`) and emits the `MoveIntent.transition` / `jumpDir`; the profile only executes the
  jump. This is the fix for the biggest design flaw (a bare world-direction `MoveIntent` could not
  carry the corner decision, and the profile was not handed the route).
- Stuck recovery: wire the existing `onPathingObstructed` (today a no-op) into the steering contract
  as an `onStuck -> replan/unstick` behaviour.
- Local avoidance (ORCA/context maps): OUT of v1 (few mobs). Reserve `MoveIntent` shape so it could be
  added (per-direction weights) without breaking the contract.

## 7. Perception layer (minimal in v1)

v1 ships only the 20% with a real consumer:
- One cached line-of-sight check, reusing vanilla `mob.getSensing().hasLineOfSight(target)`.
- Optionally one "last seen position + timestamp" slot per agent (for one hunter mob), enabling
  "lose sight -> walk to last-known".

Hosting: perception runs in `tick()` / `customServerAiStep()`, NOT in `navigation.tick()`, because
`isImmobile()` (observe stance) skips `serverAiStep` and would freeze perception otherwise.

Deferred (no consumer yet): voxel DDA LOS, decaying occupancy memory, shared influence/scent grids.
Add them with a concrete hunter AI, later.

## 8. Integration (Option A) and lifecycle (rewritten around the real tick order)

The agent is a pipeline across the vanilla tick, mirroring the existing drive chain:

- Goals unchanged: they call `mob.getNavigation().moveTo(target)`.
- `AgentBackedNavigation.tick()` (navigation phase): runs Plan + Steering, writes a `MoveIntent` into
  a field. (It must also subsume what the current navigators do in `tick`, including the MC-94054
  finish-the-last-step poke.)
- A NO-OP MoveControl is REQUIRED. The live `MoveControl.tick()` runs unconditionally after
  navigation; if it is the current `ClimberMoveController` it would overwrite the agent's output. So
  either the agent's Steering IS the MoveControl, or we install a pass-through MoveControl. (This was
  an "open question"; it is a hard requirement.)
- `applyMovement` runs from the `travel()` OVERRIDE (post-controls), the only place climber physics
  can legally run. Folding execution into `navigation.tick` would double-apply physics (vanilla still
  calls `travel()` later in the same tick). Data flow is therefore TWO phases:
  navigation-phase (perceive context + plan + steer -> MoveIntent) and travel-phase
  (profile.applyMovement(MoveIntent)).
- Perception in `tick()`/`customServerAiStep` so it survives `isImmobile()`.

Client side: `travel()` also runs under `isControlledByLocalInstance()`, and orientation rebasing is
server-side with client lerp. Server->client rotation sync is a known TODO (climbing-port step 6) the
API inherits; the Climber profile owns it.

Option B (NARROWED, was over-claimed): the "thin facade keeps Option B reachable" promise holds only
for GROUND mobs (drive via public `zza`/`xxa`/jump setters + a data attachment). Climber and Flying
profiles need a custom `travel()`/`move()` and touch protected entity state
(`setPositionToBoundingBox`, delta movement, attachment fields, `getMaxFallDistance=0`,
`checkFallDamage`), so they will always need a subclass or a `travel`/`move` mixin. State this
honestly; do not design v1 indirection for a facade that cannot cover the climber execution anyway.

## 9. Optimization (sized to the target)

- A per-tick expansion budget + synchronous time-slicing of the A* loop. No async pool in v1 (it would
  be a data race against live chunks, and the evaluator reads live mob state).
- Distance/visibility LOD and shared structures: deferred (few agents). Add only if the target grows.
- Pool route/intent objects; avoid per-tick allocations.

## 10. Open questions and risks

- The biggest residual risk: the `MoveIntent`-centric split for surface-relative movement. Mitigated
  by step 0 below.
- Inner-corner frame behaviour (no single tangent plane).
- The exact steering/execution line through `travelOnGround` (surface-projection probing and
  re-attachment: which side of the line?). Draw it on paper before coding step 1.
- The `zza`-fed `updateWalkingSide` feedback loop (frame selection depends on an execution input).
- Server->client rotation sync (inherited TODO).
- Confirm the performance target in section 1.

## 11. Build order and cut list

Build order:
0. STEP 0, throwaway spike (de-risk first). Put the CURRENT spider, UNCHANGED, behind a
   `LocomotionProfile.execution` facade driven by a hand-built `MoveIntent`, and confirm it still
   climbs a wall and rounds a corner without regressing. If `applyMovement(MoveIntent)` cannot drive
   the working climber, the `MoveIntent` contract is wrong, and we learn it in a day, not after
   building plan + steering on top. (Honors "do not break what works".)
1. Extract the `LocomotionProfile`: Climber (wrap the evaluator + lift the entity's `travel` physics
   behind `applyMovement`) and Ground (vanilla-like). Run the spider through the Agent on flat-grid
   plan + basic steering. NOTE: Ground is the trivial case and proves little; the Climber extraction
   is the real test, so step 0 is what actually de-risks it.
2. Steering quality: fix/re-enable the existing funnel (`findDirectPathPoints`) + look-ahead + arrive
   + face-transition handling + stuck recovery. Biggest visible fluidity win.
3. Perception (minimal): cached LOS + last-known-position, with a hunter consumer.

CUT from v1 (kept possible by the abstraction, built only if a real need appears):
- HPA* (ground-only at best, and only if flat A* profiles as the bottleneck).
- Async pathfinding (correctness risk against live chunks; synchronous time-slicing instead).
- D* Lite incremental replanning (throttled recompute is enough).
- ORCA / context-steering local avoidance (crowd tech, not a few-monster product).
- Shared influence/scent grids, shared caches, distance/visibility LOD machinery.
- Rich perception (voxel DDA, decaying occupancy) until a consumer exists.
- Option B indirection beyond what Ground needs.
