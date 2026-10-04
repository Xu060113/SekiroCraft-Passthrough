# Shared-memory protocol v1

All integers and IEEE-754 floats use little endian. C++ layouts are statically asserted; Java uses explicit byte offsets and is checked against C++ binary fixtures. Win32 `GetTickCount64()` is the only time base, exposed through JNI to avoid mixing Java nanoTime with the host clock.

Names: `Local\SekiroBridge-<channel>` and its `-lock` mutex. Channel names contain only ASCII letters, digits, hyphens or underscores, maximum 64 characters. Both participants create/open the same mapping, with a default user/session ACL. Use distinct channel names for additional pairs.

| Structure | Size | Fields |
| --- | ---: | --- |
| Header | 248 bytes | magic `SBP1`, version, mapping size, Control, newest frame, peer status/time/epoch |
| Control | 200 bytes | sequence/time/epoch, flags/capabilities, player/eye/forward, camera lens, mapping offset/scale, output dimensions, VK bitmap, pointer/buttons/wheel, command and UTF32 stream |
| FrameMeta | 96 bytes | sequence/time/epoch, source control sequence, dimensions/flags, capture camera/lens and source control timestamp |
| Frame payload | width × height × 12 | RGBA8 world, float32 OpenGL depth [0,1], RGBA8 premultiplied overlay |

The mapping contains one Header and three fixed-capacity frame slots. Maximum dimensions are 1920×1080; capacity is 74,650,136 bytes. Default capture width is 1280, with height following the host camera aspect. The mapping owns the publication sequence, so restarting the MC producer does not reuse old frame numbers. A new host process supplies a new epoch; frames from an earlier epoch cannot be drawn.

The mutex always uses timeout zero on both the render thread and publisher. The C++ receiver copies a completed frame on a worker thread and transfers an immutable CPU snapshot through an atomic ownership mailbox. An empty mailbox retains the last snapshot; a published null explicitly resets it. A busy IPC read retains the last peer heartbeat for its original 350 ms lifetime, while explicit bridge-off and epoch changes still clear it. The render path does not wait for IPC or perform a synchronous GPU readback. Triple-slot shared storage is protected by the named mutex, not a lock-free seqlock.

Frames must carry a valid source control timestamp as well as a publication time, both at most 350 ms old. This prevents delayed GL readbacks from becoming fresh merely because they were published later. Scene/focus and native depth freshness are additionally checked before drawing; the original hero is hidden only after a successful composite submission. A large camera discontinuity rejects the frame; normal camera differences use bounded iterative reprojection.

Control flags: scene=1, host focus=2, accept MC input=4, native F7 diagnostics open=8. Capabilities retain the earlier layout and add MC-owned player/camera=256 and experimental native terrain sampling=512. These require the verified position hook, timer, camera tail and ray function. Peer bit 8 requests MC player ownership. Bits 8/16/32 remain unavailable: the new height field does not claim complete native terrain or NPC/Havok integration.

Player feedback uses a separate `Local\SekiroBridge-<channel>-physics-v1` mapping and its `-lock` mutex; it does not change the existing frame ABI. Its packet is 6208 bytes: tick, source-control tick and epoch at 0/8/16; flags/count at 24/28; host-space player origin at 32; radius/height at 44/48; reserved at 52; sequence at 56; up to 256 min/max AABBs at 64, 24 bytes each. Flags are complete collision snapshot=1, flying=2, creative=4. A bounded MC region supplies actual voxel boxes; missing chunks or overflow disables collision for that snapshot instead of truncating a wall. Packet size, floating values, local bounds, epoch and both clocks are validated. Lock misses retain snapshots for their original expiry. Player-only coordinate constraints do not claim native Havok collision.

Coordinates: Sekiro `(x,y,z)` to MC `(x×scale,y×scale+y_offset,-z×scale)`. MC seeds its player from the host once per scene/connection. Its subsequent physics and camera are authoritative. Projection near/far use the same scale; exported metadata retains host units. Frame flag 4 declares an MC yaw float in the previously reserved word at offset 44, preserving heading at vertical pitch. The capture pose is frozen from Camera.update, and its timestamp is capture time. Only the newest ready PBO is published; older captures are retired without publishing backwards in time. Update both peers together.

## MC ownership side channels

All use the same zero-timeout mutex snapshot model and channel name with their suffix; the main mapping size remains unchanged.

| Suffix | Size | Layout |
| --- | ---: | --- |
| `input-v2` | 4136 | tick/epoch/event sequence at 0/8/16; cumulative signed 64-bit relative mouse counts at 24/32; 128 events of 32 bytes at 40 |
| `player-v2` | 104 | sequence/tick/control tick/epoch at 0/8/16/24; flags at 32, MC camera yaw at 36; player/eye/forward xyz at 40/52/64; fov/aspect/near/far at 76; velocity xyz at 92 |
| `terrain-v2` | 448 | sequence/tick/epoch at 0/8/16; host center xyz at 24; spacing .5 at 36; 81 float heights at 40; 81 hit bytes at 364; three padding bytes |

Input events: kind/code/action/modifiers at 0/4/8/12; normalized client cursor x/y at 16/20; wheel amount at 24; reserved at 28. Kind 1 is Windows VK key, kind 2 is GLFW-order mouse button (left 0, right 1, middle 2), kind 3 is wheel. Actions 0/1/2 are release/press/repeat. GUI coordinates use the actual HWND client rectangle and scene margins, not the D3D render buffer dimensions. A short press and release carry separate cursor coordinates and modifiers. Overflow releases held states before replay and reconciles the current physical levels. Reconnect starts at the latest event sequence.

Player flags: MC owner 1, flight 2, GUI 4. The receiver validates finite coordinates/lens, velocity bounds, camera distance, consistent yaw/forward, both clocks and epoch before native position/camera writes. The camera right vector retains yaw at ±90° pitch. Native movement/gravity flags are restored on loss of ownership; camera writes occur after the normal ChrCam update, so the original next update takes over on release.

Terrain rows use x=(column−4)×spacing and z=(row−4)×spacing. Queries run on the matching player's physics thread, at most once per 75 ms. Results are an experimental surface height field for MC's player collision view, not persistent MC blocks or full native collision geometry. No-hit cells remain open space; stale packets expire. MC animals and native NPCs are outside this adapter.

Wheel is cumulative, F9 command is edge counted, and UTF32 input is an eight-entry circular stream with a cumulative sequence. A peer reconnect initializes its input cursors to the latest state, releases previously owned key/button presses and avoids replaying old text or test-block requests. The bounded text ring may lose characters if the peer misses more than eight characters before reading again; IME composition forwarding is not implemented.
