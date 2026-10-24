# Architecture

This document describes what the code does and which guarantees it provides. Each claim names the code
that implements it or the test that checks it.

## Model

Per player, one integer: the offset `O`, a non-negative multiple of 16 with `O <= Omax`.

```
clientY = realY - O      (Bedrock client and Geyser's translators)
realY   = clientY + O    (Java server)
```

- Only absolute Y is translated. X and Z are never changed. Relative quantities (motion, velocities, cursor
  offsets, `MoveEntityPos` deltas, relative teleport components) are never changed.
- `Omax = floor16(javaMaxY - clientMinY - clientHeight)`. A larger offset would push the top of the world below
  the client floor without making anything reachable.
- The target offset centres the player in the window: `O* = snap16(clamp(realY - center, 0, Omax))`.
  Implemented in `window/WindowRules`, mirrored in `dev/verify/oracle.py`.
- If the real dimension fits the client window, `WindowConfig.needed` is false and the pipeline does nothing
  except watch login and respawn.

## Pipeline position

```
Java server  <->  [codec] -> [hauteur: HauteurHandler] -> [manager] -> Geyser translators -> Bedrock
```

The handler sits directly after mcprotocollib's `codec` and before Geyser's `manager`. Inbound packets reach it
decoded in real space. Outbound packets reach it before serialization, in client space. The handler is the
only place where absolute Y is translated for the session.

The handler does not depend on Geyser types. It reads the session through `pipeline/PlayerSession`, the
configuration through `pipeline/PipelineSettings` (implemented by `HauteurCore`), and reports through
`pipeline/Diagnostics`. Production adapters are `session/GeyserPlayerSession` and `pipeline/GeyserDiagnostics`.
That seam is what lets `HauteurHandlerTest` run the real pipeline without a Geyser runtime.

Writes that Geyser makes on the Java side pass through the handler too. Packets the handler injects go to
Geyser with `managerContext.fireChannelRead`, so they are not translated again.

## Threading

| Context | What runs | Shared state |
|---|---|---|
| Channel event loop | Inbound, window switches, freeze end, flush of held packets | The transition state of `HauteurSession` is written here, through its setters. |
| Geyser tick thread (or any thread) | Outbound `write` | Holds `freezeLock`, so it cannot interleave with a switch or a flush. Reads volatile fields. |
| Session and reload threads | Disconnect, reload, sweeper | `ChunkCache` is synchronized, so `clear` and `resize` are safe. |

Every freeze transition and every outbound write runs under `freezeLock`. The unfreeze flush therefore cannot be
overtaken by a later packet. Lock hold times are short: the lock is not held while waiting for I/O, and writes
from other threads are queued by Netty rather than executed inline.

`HeldQueue` is still lock-free because it is also used as a plain FIFO. It is not the ordering guarantee; the
lock is.

## Window switch

`HauteurHandler.performSwitch(realY, injectSnap, generation)`, under `freezeLock`:

1. Compute the target offset. Return if it equals the current offset, the world generation changed, or the
   pipeline is not active.
2. Increment `freezeEpoch`, set `frozen`, record the previous offset as `frozenFrameOffset`, flip `offset`,
   set the anchor `lastSeenRealY = realY`, record the start time.
3. Replay the cached chunks, nearest first. Each chunk is windowed in the new frame and followed by its
   journaled block and block-entity changes that fall inside the visible window.
4. For switches not caused by a server teleport, snap the client with a `ClientboundTeleportEntityPacket` for
   the player's id. Geyser maps that to a `TELEPORT` MovePlayer for the Bedrock client.
5. Schedule `unfreeze(epoch)` after `freeze-ms`. A stale epoch does nothing.

`unfreeze` flushes held packets (see below), re-checks destroyed-block ghosts, clears `frozen`, and then evaluates
whether another switch is needed. A teleport that arrived during the freeze is therefore not lost.

### Switch triggers

- **Server-authoritative positions** (`ClientboundPlayerPositionPacket`, `ClientboundTeleportEntityPacket` and
  `EntityPositionSync` for the player's id). The packet itself is the teleport the client receives, so no snap is
  injected. The cooldown is bypassed, because the client is already at that position. A relative Y is a delta
  from the anchor.
- **Client movement** (`MovePlayerPos`, `MovePlayerPosRot`). Subject to the margin and the cooldown.
- **Operator** (`/hauteur window`). Forced target, snap injected.

### Journal

Block updates, section updates and block-entity data received for a cached chunk are recorded in real coordinates,
keeping the latest state per position. A block change removes the block-entity entry at the same position. A chunk
whose journal exceeds 4096 positions is evicted instead of being replayed stale.

## Freeze: outbound classification

`OutboundYTransforms.frozenDisposition(packet)` decides each packet during a freeze:

| Class | Packets | Disposition |
|---|---|---|
| No absolute Y | Everything else (rotation, interaction, inventory, command requests without coordinates) | Transmit unchanged |
| Movement | `MovePlayerPos`, `MovePlayerPosRot`, `MoveVehicle` | Hold if `freeze-hold-movement`, else drop |
| Block-positioned action | `PlayerAction`, `UseItemOn`, `PickItemFromBlock`, `SignUpdate`, `SetCommandBlock`, `SetStructureBlock`, `SetJigsawBlock`, `JigsawGenerate`, `BlockEntityTagQuery`, `SetTestBlock`, `TestInstanceBlockAction` | Hold if `freeze-hold-actions`, else drop |
| Unsigned command | `ChatCommandSigned` with no signatures | Drop (coordinates cannot be placed in a frame) |
| Signed command | `ChatCommandSigned` with signatures | Transmit unchanged (never rewritten) |

Held overflow (more than 48 entries) is a drop. A dropped destroy (`START_DIGGING`, `CANCEL_DIGGING`,
`FINISH_DIGGING`) is recorded so that its block is re-sent when the freeze ends. Both candidate frames are
checked, and only positions inside the visible window are re-sent.

Before this version, sign, command block, structure, jigsaw and pick-block packets were transmitted without
translation during a freeze. They are now held as block actions.

## Frame resolution for held packets

When the freeze ends, each held packet has two candidate real positions: one under the previous offset and one
under the current offset. They differ by the switch delta, which is at least 16. `FrameResolver` chooses:

- **Movement:** the candidate within `travelBound` of the anchor. `travelBound = 24 + 60 * elapsedSeconds`, which
  is a generous bound on player speed. If both candidates or neither are within the bound, the packet is dropped.
- **Block action:** the candidate whose block centre is within 7 blocks (Bedrock reach) of the anchor. Both or
  neither: drop.

The anchor starts at the switch's `realY` and advances with each resolved movement. Packets are replayed in the
order they were sent, so the anchor follows the player's path.

This is a plausibility check, not a proof. It is correct when the anchor is within about 10 blocks of the true
position and the switch delta is at least 64. Smaller deltas can make both frames plausible; the resolver then
drops the packet. It never chooses the frame that is less plausible.

## Chunk cache budget

`ChunkCache` counts, per chunk: the chunk data, the heightmap arrays, the block-entity count, the light update
arrays, and 128 bytes per journal entry. The budget is strict: entries are evicted least-recently-used until the
total fits. A chunk larger than the budget by itself is not stored, and an older version of that chunk is removed.
Replays cover only what is cached, so such chunks are missing from the client after a switch until the server
resends them. This is documented in the README.

The estimate is conservative, not exact: it does not count the object headers of the packet and NBT contents of
block entities.

## Failure policy

| Failure | Response |
|---|---|
| Exception in an inbound transform | Type quarantined for the session. If the window is active, packets of that type are dropped (fail closed); at offset 0 they pass, since real space is already correct. |
| Exception in an outbound transform | Same policy. |
| Exception in a held packet's replay | Packet dropped, type quarantined. |
| Exception replaying one chunk during a switch | That chunk is skipped, counted as an anomaly, and logged. The switch completes. |
| Exception during unfreeze | Held packets and ghost re-sends are discarded, the freeze is cleared, and the error is logged. |
| Chunk does not fit the cache budget | Not cached, counted, replay skipped for it. |
| Journal overflow | Chunk evicted, counted. |
| Attach fails after retries | Session runs untranslated; `/hauteur info` and `/hauteur doctor` report it. |
| World-manager wrap fails | Reported by `/hauteur doctor`; collision and container reads may disagree with the window. |

The quarantine is kept per session and is cleared on reconnect. `/hauteur doctor` reports the current session's
quarantined types with the first error, so that a deterministic failure can be diagnosed.

## Lifecycle

- **Login and respawn:** the chunk cache is cleared, `worldGeneration` increments, the freeze is cancelled, the
  offset returns to 0, and the window is re-derived at the next position packet. Scheduled switches from the old
  generation are ignored.
- **Disconnect:** the handler is removed, and its state is reset. The session state is removed by the disconnect
  event, or by a sweeper every 60 s if the event never fired. The sweeper is cancelled on shutdown.
- **Reload:** the cache budget is re-applied to every tracked session. Enabling re-attaches connected players.
  Disabling removes every handler and restores the world manager, while keeping session states so that a later
  re-enable does not depend on a reconnect.
- **Detaching a shifted client:** removing the handler while the offset is non-zero on a live channel (disable,
  reload, shutdown) first gives the client its real-space view back: cached chunks are replayed at offset 0 and
  the player is snapped to the last real Y. Without this, the client would stay in a frame that nothing translates
  any more. Nothing is sent on a closed channel. Covered by `HauteurHandlerTest` on an embedded channel; the
  real-client behaviour is covered by the manual procedure (see VERIFICATION).
- **Shutdown:** the sweeper is cancelled, handlers are removed, states are cleared, and the world manager is restored.
- **Dimension read failure:** the window is re-derived from live data. If that read fails, the previous snapshot is
  kept, so windowing does not switch off while the client is shifted.

## World manager wrapper

`ShiftedWorldManager` wraps Geyser's `WorldManager`, so that direct, session-aware reads (collision correction,
swimming and climbing checks, container checks, decorated pots) resolve through the caller's offset. Installation is
the only reflection point in the extension, and it is reported by `/hauteur doctor`.

The wrapper adds the offset only where Geyser reads the real world, and it uses Geyser's own rule for that: the
platform owns its world (`hasOwnChunkCache()`, Geyser-Spigot) or the session's Erosion backend is active. Otherwise
Geyser reads its session chunk cache, which already holds window-space chunks, and the read is passed through. The
offset is therefore never applied twice. Async reads stay async, and batch reads stay one request, with the batch
box shifted and the result indexed as before.

## What is not guaranteed

- Client-side adoption of a frame is inferred from time and plausibility; Bedrock sends no acknowledgement that
  Hauteur can read.
- Whether a snap and the replayed terrain look correct on a real client.
- Performance under load. Replays run on the event loop and have not been measured.
