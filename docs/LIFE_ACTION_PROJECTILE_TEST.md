# Life, native actions and projectile repair

Historical gameplay3 record: current local builds use M for grapple and no longer forward R to native attack/deathblow/resurrection. Use the MC death-screen button to resurrect. The older G/R instructions below describe the previous build and must not be used for the current patch; see SLASHBLADE_TEST.md.

The paired host/JNI/Fabric build adds these changes. Offline checks do not establish real-game acceptance.

* Death remains a connected GUI state. Click the first MC death-screen button (or press R) to send the native left attack/resurrection button. MC requests its respawn only after a fresh native living snapshot. The native hero identity changes on resurrection even if its address stays the same, rejecting pre-death damage and commands.
* Bind **Sekiro grapple to G**. Keep Sekiro attack/resurrection on its normal **left mouse button**. **R** sends that native button while MC gameplay is suspended. Use R when the actual Sekiro red deathblow indicator is available; hold R until a long finisher completes. Native grapple/finisher root motion moves the MC player, then returns position authority after the key is released and the native position settles. G/R remain usable as text in inventory/search screens.
* The native Wolf draw bit remains suppressed before rendering during death and action handoff. MC camera/view and character rendering continue. Native animation is not translated into a Steve animation.
* Arrows/tridents, thrown entities and fireballs request bounded native physics ray segments (64 per batch, maximum 16 native meters per segment). Client JNI transports immutable server requests; casts execute only at the verified player physics callback. Requests identify epoch and exact segment. Stale/unmatched/nonfinite or off-segment contacts are discarded. A missing reply waits at most 350 ms and uses only observed nearby floor contact as fallback.
* Native ray contacts stop vanilla projectiles at real floor/wall positions. They create no saved barrier blocks and cannot be mined. Arrows lodged in native floor use vanilla in-ground lifetime and pickup. Native actor proxies receive vanilla arrow damage and render lodged arrows without a second character body. Native actor exports/damage range extend to 64 native meters. Proxy shape is still human sized, so large Boss limbs do not yet match their detailed meshes.

## Focused acceptance

1. Enable `/sekirobridge on`; die in survival. Confirm MC death cursor and button work. Click once to resurrect Wolf. MC respawns at Wolf without a second immediate death. Repeat once with final death/respawn at an idol.
2. Break a normal enemy's posture, move within native deathblow range, and use/hold R at its red point. Repeat for one Boss node. Confirm native animation completes, node decreases exactly once, and no Wolf body is visible. R can perform a normal native attack if the engine does not currently accept a deathblow; it does not manufacture eligibility or write Boss node counters.
3. Fire arrows at sloped ground, a wall farther than the sampled local floor, and a native enemy. Confirm nearest MC blocks/native terrain win in the proper order; floor arrows stay visible and can be picked up; native enemy arrows are visible and damage registers. Also throw a snowball/egg/potion. Check a rapid batch and a brief IPC interruption.
4. Rebind grapple to G in Sekiro, aim at a real grapple marker and press G. Confirm native movement, MC follow, release back to WASD, and no Wolf mesh in first/third person. Open inventory and type G/R: no native actions should fire.

Native action timing is a bounded control handoff, not an identified animation-state/deathblow API. Real-game testing is still required, particularly long scripted Boss finishers, action-facing/lock-on and grapple-marker targeting.
