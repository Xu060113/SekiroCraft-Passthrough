# Chained Ogre and mapped grapple repair

This revision changes normal bridge hits to the grounded Kusabimaru profile
5000010 sampled in native play, with coherent attack kind, reaction strengths,
empty effect fields and secondary reaction selector defaults. It does not copy
live packets or stack padding, overwrite Boss nodes or enable the candidate
remote phase finish backend.

MC position ownership no longer sets native NoMove. Its final physics candidate
store and input capture still own movement, while the invisible native actor's
action state remains able to run. Native action handoff accepts the observed
grounded idle states 100321 and 790010 even when its MC baseline was animation 0.
A transition into idle alone does not confirm a successful native action.

Set `grapple_key` in `[SekiroBridge]` to the native Sekiro binding (A-Z).
For this user's M binding the paired updater is run with `-NativeGrappleKey M`.
In MC gameplay either M or G requests native M through both DirectInput polling
and buffered events. Inventory/native menus must never send these requests.
The original Wolf mesh remains hidden throughout the handoff.

Offline checks cover packet ownership and ABI, stage-stale commands, actual
DirectInput M down/up and GUI suppression, and return to a different idle state.
These checks cannot establish native Boss red-point eligibility or rewards.

Directed live acceptance:

1. With MC enabled, face a real native grapple marker and press M. Verify the
   native pull moves Steve, WASD works after landing, and Wolf stays hidden.
2. Weaken Chained Ogre using MC. Approach on the ground and face it. Verify a
   real native red point, then tap R. Record a native type-5 hit and node 2 to 1;
   after the final node verify the native reward/progression.
3. If no red point appears, stop repeating the fight. Preserve that trace, pause
   MC control using F8 and compare a single native sword hit on the weakened
   Ogre. This separates receiver reactions from MC-owned attacker eligibility.

Status: code and offline repair pending live acceptance. A depleted HP bar by
itself is not recorded as a successful deathblow.
