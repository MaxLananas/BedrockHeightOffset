# Extreme audit — adversarial review of the 1.3.0 implementation

Posture: the implementation is guilty until proven otherwise. Every entry was found by asking
"how can this fail?", never "does this work?". Priority ladder enforced throughout:
CORRECTNESS › DATA INTEGRITY › DETERMINISM › STABILITY › PERFORMANCE › MEMORY › CODE SIZE.

Inventory produced BEFORE any modification (step 25 of the protocol); dispositions recorded
after treatment in the second table.

## Inventory — classified findings

### CRITICAL

| ID | Finding | Failure mode |
|----|---------|--------------|
| CR1 | `SkyWindowHandler.write()` reads `state.offset` and `state.frozen` as two separate volatile reads. `performSwitch` writes `frozen=true`, `frozenFrameOffset`, `offset=target` in sequence. A writer thread (Geyser tick) stalled between its two reads can pair OLD `offset` with a stale `frozen=false` read that is *logically after* a full freeze/unfreeze cycle (400 ms), or NEW `offset` with `frozen=false` before the client frame changed. | One movement packet translated with a foreign frame's offset: the server sees the player Y jump by the window delta; worse, `writeMovement` then feeds that wrong `realY` into `evaluateSwitch`, which can trigger a **window switch centered on a bogus Y** — a rare, real desync/teleport class. Classic torn-read across two volatiles. |
| CR2 | Window-switch chunk replay walks the whole `ChunkCache` synchronously on the channel event loop (`nearestFirst` + `WindowedChunks.window` per cached chunk). Bounded by the cache cap (2048 chunks / 96 MB) but the worst case copies hundreds of MB on the event loop. | Event-loop stall during switch; netty heartbeat/other traffic on that channel waits. Bounded, but the bound is a memory cap chosen for MEMORY, not for latency. |
| CR3 | `ChunkCache` LRU evicts under `maxChunks`/`maxBytes` **without the corresponding chunk having left the client**. A switch replays only what is still cached; an evicted-but-still-loaded chunk keeps its OLD frame projection forever (ghost chunk) until the server re-sends it. Guarantee is exact only while cache ≥ chunks loaded (defaults 2048 ≈ view distance 22). | Ghost chunks at large view distances + height switches: "chunk ancien/chunk fantôme" — exactly the destructive-chunk-audit class. Silent memory-vs-correctness tradeoff, undocumented as a guarantee limit. |

### HIGH

| ID | Finding | Failure mode |
|----|---------|--------------|
| H1 | **Dead state army left over from the deleted `/skywindow` commands** (1.3.0 removed the commands, not their scaffolding): `watch` flag (never written, read on 6 hot-path sites), watch ring + `pushWatchLine`/`watchSnapshot`, 12 never-read counters (`inTranslated`, `outTranslated`, `chunkWindowed`, `chunkReplays`, `windowSwitches`, `droppedWhileFrozen`, `chunkAnomalies`, `actionsHeld`, `heldOverflow`, `freezeTotalNanos`, `freezeCount`, `lastFreezeNanos`) + `statsBaseline[16]`, the whole `forceWindow`/`performForcedSwitch`/`forcedRealY` chain (its only caller was `/skywindow window`), `quarantinedTypes` map + `reportQuarantinedType`/accessor ("for /skywindow doctor"), `TESTED_AGAINST` constant (read by nobody), `degraded` flag (written `false`, never `true`, never read), `SkyWindowExtension.onDefineCommands` empty listener, `HeldQueue` overflow callback (only fed a dead counter). | ~15 never-read fields, ~10 writes per packet/chunk on hot paths for nothing, one whole scheduling chain ("au cas onde"), plus an empty event subscription. Every one violates "a concrete technical reason to exist". Also: LongAdder increments per translated packet and per chunk = pure GC/CPU waste. |
| H2 | `CommandYRewrite.rewrite` tokenizes the command (`tokenize(trimmed)`), then `tree.ySlots(trimmed)` tokenizes **again** (`tokenizeWithOffsets`) for the offset mapping. Every command packet and every tab-completion keystroke pays double tokenization + double allocation (char[], protected String, N tokens, N substrings). | 2× work and 2× garbage on the hottest text path (tab-complete fires per keystroke per player). Structural waste: the same data computed twice. |

### MEDIUM

| ID | Finding | Failure mode |
|----|---------|--------------|
| M1 | `SectionCodec.resliceTolerant` allocates `int[] starts` next to `int[] lengths`, but the copy loop visits source sections in strictly ascending order (`src = w + shift`) — a monotone cursor suffices. | One useless int[] per chunk packet (~200 per spawn burst per player). |
| M2 | `WindowedChunks.shiftBlockEntities` builds `ArrayList` then `toArray` (2 allocations + growth) where one filled array of known max size does. | 2+ allocations per windowed chunk with block entities. |
| M3 | `SkyWindowCore.loadConfig` derives `commandConfig` twice (inside try and again after). | One useless allocation per config load (rare, but pure noise). |
| M4 | `revertDestroyedGhosts` performs synchronous `WorldManager.getBlockAt` from the netty event loop (≤ 8 calls). Same pattern Geyser itself uses on this surface; platform reads are documented thread-tolerant. | Accepted risk — documented, bounded, rare (ghosts only after dropped dig/place during a freeze). |
| M5 | Window replay (CR2) cost is bounded by the memory cap rather than by a latency budget. | Worst-case event-loop stall at switch; mitigated by caps + rarity (switch ≈ 1/450 blocks climbed). |

### LOW / CLEANUP

| ID | Finding |
|----|---------|
| L1 | Orphan javadoc/comments referencing removed features (`/skywindow doctor` ×3, `/skywindow window <realY>`, duplicate `@return` stub in `OutboundYTransforms`), the `counter accessor index` comment over `GHOST_REVERT_LIMIT`, the `LOGGED` `TESTED_AGAINST` doc. |
| L2 | `InboundYTransforms` 2- and 3-arg `apply` overloads exist only for tests — kept: test surface is real value (they pin the contracts). |
| L3 | `ChunkCache.key()` boxes `Long` per chunk lookup (~200/spawn burst) — kept: a hand-rolled primitive LRU is more code than the boxing costs in a lifetime; CODE SIZE ranks last but "premature optimization absurd" is forbidden. |
| L4 | `SuggestionRanges.Journal` is `synchronized` (writer thread + event loop). Kept: two 3-line critical sections, bounded LRU; a lock-free bounded LRU would be strictly more complexity for zero measurable gain. |
| L5 | `windowed.anomaly()` is only fed to dead counters (H1). The flag itself is a real integrity signal (payload normalized) — it deserves a rare log line instead of a dead counter. |
| L6 | `evaluateSwitch` is called per movement/teleport/sync packet — kept event-driven (the packets ARE the events; no polling exists anywhere in the pipeline). `switchQueued` latch + cooldown are the only scheduling state. |
| L7 | Sweeper (60 s) is polling for `states` cleanup — kept: `SessionDisconnectEvent` does not fire for sessions that die between initialize and join (Geyser semantics); no event exists for that gap. Bounded, documented. |

### Self-adversarial answers to the standing questions (21)

- *Executed twice?* `performSwitch` re-entry is serialized by the event loop + `switchQueued` latch + `target == previous` re-check → second run is a no-op. `installShiftedWorldManager` re-entry guarded by `worldManagerShifted` + `instanceof` unwrap check.
- *Never executed?* Freeze-unwind paths are triple-covered (scheduled task, `schedulingFailed` catch, outer catch) — each path ends with `frozen=false`-equivalent + `clearAndComplete` + ghost clear; no strand possible.
- *Too early / too late / wrong order?* CR1 is exactly this class. Packet-order sensitivities (seat-before-add, teams-before-spawn, container data within 1 tick) are bounded reorderings of Geyser's own translators — documented, out of our ordering surface.
- *Chunk disappears between the lines?* Replay re-derives from cached originals; `ForgetLevelChunk` purges the cache on the event loop before any switch can replay it; `worldGeneration` invalidates freeze artifacts across dimension replacement.
- *Player disappears?* Every switch/freeze path re-checks `channel().isActive()`, `isSpawned`, `worldGeneration`; `handlerRemoved` resets frame, clears held futures (Geyser write promises are never left hanging), clears the chunk cache.
- *Geyser disappears / API shifts?* Reflection is startup-only (2 field finds + 1 proxy), double-strategy install, clean unwrap; failure degrades to packet-layer-only with one warning. No reflection on any hot path.
- *Server lags?* Freeze window is time-based (`freeze-ms`), backoff doubles on switch storms (×2 up to 8×, decays after 4×cooldown), queue cap 96 with drop+ghost fallback.
- *1 million calls?* Per-packet work is O(packets) instanceof chains + at most one record copy per transformed packet; chunk work is O(payload) one walk + one exact allocation + bulk copies (M1 reduces allocations); text work is O(tokens) after H2.

## Dispositions (post-treatment)

| ID | Disposition |
|----|-------------|
| CR1 | **Fixed** — `offset`/`frozen`/`frozenFrameOffset` replaced by a single immutable `FrameState` record behind one volatile reference. One read per packet; the switch writes one reference (`new FrameState(target, true, previous)`); torn states are now impossible by construction. |
| CR2 | **Accepted + documented** — replay stays synchronous (a batched replay would present the client a half-old/half-new world = worse), bounded by the cache caps; switch rarity (1 per ~450 blocks) keeps the stall amortized. |
| CR3 | **Mitigated + documented** — eviction of a still-loaded chunk now logs one warning per session naming the exact knob (`chunk-cache-max-chunks` / `chunk-cache-max-megabytes`); README documents the exact-guarantee envelope (defaults cover view distance ≤ 22). The cache stays bounded (OOM defense ranks under correctness only when both are live threats). |
| H1 | **Fixed** — entire dead state army deleted: watch machinery, 12 counters + baselines, forceWindow chain, quarantined-type map, TESTED_AGAINST, degraded flag, empty command-event listener, HeldQueue overflow callback. |
| H2 | **Fixed** — single tokenization per rewrite: `CommandYRewrite` computes `tokenizeWithOffsets` once and hands the tokens to `CommandTreeIndex.ySlots`. |
| M1 | **Fixed** — `starts[]` eliminated; monotone source cursor over `lengths[]`. |
| M2 | **Fixed** — single pre-sized array fill. |
| M3 | **Fixed** — single derivation. |
| M4 | **Accepted** — documented above; identical to Geyser's own calling pattern on this surface. |
| M5 | **Accepted** — documented above. |
| L1 | **Fixed** — orphan comments/javadoc purged. |
| L2/L3/L4/L6/L7 | **Kept with reasons** (table above). |
| L5 | **Fixed** — anomaly now produces one rare warning line (real integrity signal) instead of dead counters. |


## Second pass (same protocol, on the treated tree)

The tree above was re-audited with the identical adversarial protocol before any further change;
new findings below, classified and treated the same way.

| ID | Finding | Disposition |
|----|---------|-------------|
| S1 (CRITICAL) | `unfreezeFrame()` kept `f.offset()` (the switch target) on a mid-switch failure, while the client provably never left `frozenFrameOffset`'s frame (the snap had not landed). The next `performSwitch` then early-returns (`target == previous`) and the session stays mis-translated until another boundary crossing - and the "staying in current window" log was false. | **Fixed** - `snapped` flag: before the snap, `rollbackSwitch()` restores `FrameState(frozenFrameOffset, false, frozenFrameOffset)`; after the snap, `unfreezeFrame()` at the new offset is correct and unchanged. |
| S2 (HIGH) | Orphan-session sweeper started even while disabled and its `ScheduledFuture` was never retained or cancelled: one zombie periodic task per process after `/geyser extensions disable` (or on a disabled install). | **Fixed** - future retained, `cancelSweeper()` on stop/disable, started only when running, idempotent across enable cycles. |
| S3 (HIGH) | `installShiftedWorldManager` trusted its `worldManagerShifted` flag: if a Geyser reload swapped the live `WorldManager`, the wrap was silently absent while every ghost/collision read assumed it. | **Fixed** - verify, never trust: the live manager is instance-checked every time; a swapped manager clears the stale reflection handles and re-wraps. |
| S4 (MEDIUM) | `onPreReload` + `onPostReload` both called `core.reload()` (double config load + double install per `/geyser reload`). | **Fixed** - `onPreReload` deleted; `onPostReload` alone is the single reload point (with S3 the re-wrap is verified, not assumed). |
| S5 (MEDIUM) | `revertDestroyedGhosts` read blocks through the (shifted) `WorldManager` at window coordinates: silently wrong blocks when the wrap failed (S3's failure mode). | **Fixed** - read REAL space through the unwrapped delegate; correct in both wrap states. |
| S6 (CLEANUP) | `handlerRemoved` left `ghostRevertPositions` populated across a pipeline rebuild on a surviving state. | **Fixed** - cleared alongside the held queue and chunk cache. |

Deletion pass re-run on the treated tree (protocol step 27): nothing further was found deletable
without losing a pinned guarantee - the survivors of the table above (L2/L3/L4/L6/L7 dispositions,
`FrameState`, `evictions`+`cacheEvictionWarned`, `unfreezeFrame`) each have a live reader and a
documented reason.
