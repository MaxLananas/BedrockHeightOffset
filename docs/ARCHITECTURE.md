# Architecture

Deep-dive companion to the README's "How SkyWindow works" section. Written against the exact upstream
sources pinned during the rewrite (Geyser master @ 9b65a39 + mcprotocollib master @ f0e959a, which
is the shading inside Geyser-Spigot 2.11.2-SNAPSHOT); the numbers below refer to that code.

## The coordinate chain, end to end

```
 join/teleport/move/build/raycast/ride/sign/pick
 Java server                     Geyser core                      Bedrock client
 ───────────                     ──────────                       ──────────────
 send real coords   ──decode──►  [codec]   ┌─────────────┐  ──consume──►  translators build bedrock
 (window-agnostic)               [bho-win] │ -O absolute │  packets from the ALREADY-windowed view
                                           │ +0 relative │              ▲
                            bedrock client │           ▼ │              │ bedrock packets never
 ◄──encode──  accept/place/dig ──[manager]─┤ re-homed  ├─◄────────────┘ │ rewritten by SkyWindow
 server applies real coords   ──► [codec]  │ chunks +  │                 │
                                  [bho-win] │ BE list + │                 │
                                  (same)    │ +O absol. │                 │
                                            └───────────┘
```

The `[bho-win]` handler is the only component that owns offsets. Geyser's translators, its world
cache (`ChunkCache`, `BlockCache`), the block-break flow, container holders and collision manager
all receive and produce one single, self-consistent coordinate frame: the client's window frame.

## Why that placement is the right one (audit conclusions)

- **Geyser's correction engines are correct-by-assumption.** `CollisionManager` samples the
  platform `WorldManager` with the coordinates the client currently uses; `BlockInventoryHolder`
  re-reads the container block the same way; the break-handling code path (`BlockBreakHandler`) is
  wire/cache-driven. The old architecture attacked the *symptoms* on the Bedrock wire; the audit
  showed the *inputs* to these readers are the world Geyser is guaranteed to consult — so SkyWindow
  shifts those inputs instead, via a session-aware `WorldManager` decorator. After that, no component
  sees mixed frames, and no corrections are produced, because there is nothing to correct.
- **Chunk coherence is index arithmetic, not patching.** Geyser's
  `JavaLevelChunkWithLightTranslator` maps Java payload slot `w` → bedrock subchunk
  `w + (javaMinSection − clientMinSection)` and ignores heightmaps/light; block-entity lookups
  index `javaChunks[(y >> 4) − javaMinSection]`. Moving content to `w = r − Δs` and shifting BE
  `y` by −O lands every consumer (sections, BEs, chunk cache, prediction) in exactly one coherent
  frame — verified numerically in tests, including the array-bounds edge the BE prune protects.
- **Bedrock wire surgery is structurally wrong.** Rewriting bedrock `UpdateBlock`/`AddActor`
  coordinates means *two* translation layers with independent notions of the offset. The
  "block placed then vanished" reports map to exactly that (client-authority reverts + Geyser cache
  writes keyed by mismatched coords).
- **One packet, one transform, per direction.** Determinism & reversibility are local properties
  checkable in tests (`apply(x, O)` then inverse is the identity — integer Y).

## Threading

- `channelRead` runs on the downstream channel's event loop (mcprotocollib dispatches onto
  `GeyserSession`'s single-threaded `tickEventLoop` *after* this handler, so Geyser-side consumption
  stays on its normal loop; we are strictly upstream of that hop).
- `write` runs on the calling thread of `DownstreamSession#sendPacket` (normally the tick loop).
  The outbound path therefore treats `state.offset`/`frozen`/`window` as read-only volatiles and
  never mutates; the switch (and all cache state) is executed with
  `channel.eventLoop().execute(...)` — inlined directly when already on that loop (inbound-triggered
  switches are inline, so the very packet that triggered a switch is translated with the new offset
  in the same read).
- The switch itself is one atomic EL task; between flip and re-reads there is no interleaving point,
  because translation also runs on that loop. The volatile `offset` write is ordered-before the
  event-loop enqueue for other threads, and any packet translated with a *stale* frame still maps to
  the same real coordinate the sender currently uses (`realY = clientY + O`), which is the exact
  property that keeps a switch race benign instead of catastrophic.
- Commands (`/skywindow …`) read volatiles + a synchronized snapshot of the (opt-in) watch ring;
  a command that mutates (`/skywindow window <realY>`) is bounced to the session's event loop via
  the pipeline handler, never executed on the command thread.
- A single sweeper on `GeyserImpl.getInstance().getScheduler()` (60 s period) is the only other
  thread touching state: it reclaims per-session state for closed sessions when Geyser's disconnect
  event did not fire (proxy hiccups, half-open connections), resetting the chunk cache - the one
  place outside the event loop that touches a `SkyWindowSession`, and only for sessions no longer
  attached (it removes them from the registry rather than mutating a live one).
- The held-packet queue (`HeldQueue`) is the one structure crossing threads *while live*: `write()`
  can run on the tick loop, the drain runs on the event loop. It is a lock-free bounded FIFO
  (`ConcurrentLinkedQueue` + advisory size cap); on abandoned replays every pending netty promise
  is completed successfully rather than failed, so Geyser write futures can never hang on a dropped
  queue.

## The switch and freeze protocol

A window switch is a single event-loop task (`performSwitch`), in this order:

1. `frozen = true`, remember `frozenFrameOffset = previous offset`, install `offset = target`.
   From this microsecond, inbound translation uses the new offset and outbound position packets
   are held.
2. Backoff bookkeeping: switches arriving within 4x the cooldown double `switchBackoff` (cap 8),
   the effective cooldown being `switch-cooldown-ms * max(1, backoff)`; a quiet period resets it to
   1. This makes oscillation near a margin pathological-input-bounded rather than CPU-bounded.
3. `held.clearAndComplete()` is *not* called here - held packets survive the whole freeze;
   the queued chunk replays follow: every cached chunk in range is re-derived from pristine
   originals at the new offset and sent first, so the snap lands on already-correct terrain.
4. The client is snapped: a synthetic `MovePlayerPos`-equivalent teleport with the player's real
   position projected into the new window (the `lastPlayerTeleport` snapshot path).
5. `freezeMs` later, on the same event loop, `unfreeze` runs:
   `frozen = false` → destroy-ghost reverts (block updates that were dropped while frozen are
   re-sent once so the client resyncs authoritatively) → flush the held queue: each held packet is
   replayed only when its frame hypothesis holds (a held dig/place whose position translates
   identically under `frozenFrameOffset`, the current offset, or is out of interaction reach - then
   it is dropped deliberately and counted); movement packets are replayed with the offset they were
   captured under, preserving fall/elytra continuity.
   Both steps are skipped wholesale if `worldGeneration` changed during the freeze (respawn/dimension
   switch mid-switch): the ghosts and held writes refer to a world that no longer exists.
6. Scheduling the unfreeze *fails* (loop shutting down) → unfreeze is executed inline before
   returning; the freeze cannot outlive its own switch.

Invariants: the freeze is only ever entered by the task that flips `offset`; nothing else writes
`offset`; `frozen` has exactly one writer at a time per session, so a packet read by `write()` can
observe (frozen, offset) pairs that are all states this protocol itself passed through - the
benign-race property from the Threading section. Timings are kept in nanoseconds (last freeze and
running totals) and surfaced by `stats`/`doctor` in microseconds - a freeze that drifts far past
`freeze-ms` is a bug visible in one command.

## The chunk cache

Original `ClientboundLevelChunkWithLight` packets are retained untouched per session, evicted by
LRU under the configured chunk and byte caps. Replay derives windowed copies on demand (never
"windowed chunk of the day"), so:

- switches cannot accumulate translation drift (always from pristine originals);
- the server's per-player chunk publisher is never perturbed (no `refreshChunk`, no fake moves —
  zero impact on Java players);
- after reconnect, cache is empty and rebuilds from the server's fresh send set at offset 0
  (identity) before the first switch decision — there is no stale cross-session state by
  construction.

## Fail-safe policy (how bugs behave)

- Any throwable inside translation → original packet forwarded untranslated, error logged **once**,
  counter incremented. Never disconnect, never drop, never corrupt.
- Malformed/short/oversized chunk payload → **tolerant normalization**: content up to the
  anomaly is preserved and re-homed, the rest is canonical air, output always has exactly the
  expected section count (so Geyser's decoder can never run past the payload into the light
  data), and the `anomaly-normalized chunks` counter surfaces it in `/skywindow doctor`.
- Block interactions sent during a switch freeze → queued and replayed on unfreeze in the sender's
  original frame (never dropped, never double-applied; queue overflow drops explicitly and reverts
  any possible ghost block authoritatively).
- Pipeline not attachable after ~1 minute of retries → the player is reported and stays passive
  (vanilla behavior). The extension logs why.
- World-manager field not found (platform reorg) → collision fix disabled globally with an explicit
  log line; everything else keeps working. Rubber-band protection is then platform-degraded —
  documented, visible, and not silently half-on.

## Seams (the only version-sensitive parts) and upgrade policy

| Seam | Used for | Breaks as | Detected by |
|---|---|---|---|
| `NetworkConstants.CODEC_NAME` / `MANAGER_NAME` pipeline insertion on the downstream channel | the choke point | attach retry → passive | `/skywindow doctor` (not attached), startup log |
| `GeyserBootstrap` field assignment of type `WorldManager` | collision/container read correction | log + degraded mode | `/skywindow doctor` |
| mcprotocollib packet class shapes (`@With` builders, field names) | all transforms | per-packet: fail-safe passthrough (one log line) | `doctor` counters (`malformed` stays 0; watch rings) |
| cloudburst `Vector3*`/math types | positions | none expected (shared API of the pinned build) | CI compile |

Upgrade procedure for a new Geyser pin: bump `geyser.version` in the pom → CI compile → CI unit
tests → re-grep Geyser translators for new position-carrying packets and extend
docs/PACKET-MATRIX.md + the transform switch accordingly → staging checklist
(docs/VERIFICATION.md). The matrix in that doc doubles as the audit checklist: a packet type
*missing* from it is the only way this design can be wrong on new protocol versions, and the
fail-safe makes even that degrade into "untranslated for that packet type" (visible), not
"corrupted state".
