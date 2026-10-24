# Verification

What was checked, how to reproduce it, and what has not been checked. A passing build is evidence about the
Java-side logic. It is not evidence of how a Bedrock client behaves.

## Automated

| Check | Command | Where it runs | What it proves |
|---|---|---|---|
| Coordinate and codec oracle | `python3 dev/verify/oracle.py 5` | CI (`build.yml`), any machine | Python mirror of `WindowRules` and `SectionCodec`: round trips, reachable-window formula, switch cadence, reslice semantics, malformed-input behaviour. Five randomized rounds. |
| Compile and JUnit | `./mvnw -B verify` (JDK 21, Maven 3.9.11 through the wrapper) | CI, on every push | Compiles against Geyser `2.11.2-SNAPSHOT` (resolved from the OpenGeyser repository) and runs the JUnit suite. |
| Python tooling tests | `python3 -m unittest discover -s dev/verify -p 'test_*.py'` and the same for `dev/release` | CI (`build.yml`), any machine | The Surefire summary (unreadable reports, table escaping, usage errors) and the release check (SNAPSHOT, tag form, changelog bounds, exit codes). |
| Benchmark smoke | `javac` on `SectionCodec` and `dev/bench/SectionCodecBench.java`, then a 50-iteration run | CI | The benchmark still builds and its payloads still parse without anomaly. It is not a timing; see the Measured section. |
| Test summary | `dev/verify/surefire_summary.py` | CI job summary | Per-suite counts read from the Surefire XML. Skipped tests must be justified. An unreadable report is named and excluded from the totals. |
| Release consistency | `dev/release/check_release.py vX.Y.Z` | Release workflow | Tag equals `pom.xml` version, which is not a `-SNAPSHOT`; changelog section exists; tagged commit is on `main`. |

### What the JUnit suite covers

- `session/ChunkCacheTest`: journal (latest state per position, block change supersedes block entity), strict
  byte budget, oversized chunks, light data counted, journal overflow eviction, budget evictions counted (on put
  and on resize, not on clear), replacement, forget, resize, nearest-first ordering.
- `pipeline/FrameResolverTest`: frame attribution for held movement and block actions; ambiguous and unplausible
  cases dropped; property test (20 000 random switches) that the resolver never selects the wrong frame.
- `translate/PositionCoverageTest`: freeze disposition table, block-position extraction, absolute and relative
  teleports (including the own-player `ClientboundPlayerPositionPacket`), identity at offset 0.
- `translate/TransformPropertyTest`, `translate/WindowedChunksTest`, `translate/CommandYRewriteTest`: the Y
  shift is exact in both directions; relative quantities survive; chunk re-homing and block-entity pruning.
- `session/HauteurSessionTest`, `window/WindowRulesTest`, `chunk/SectionCodec*Test`,
  `pipeline/HeldQueueTest`: window math, codec tolerance and fuzzing, the held-queue contract.
- `LogSanitizerTest`: player-name sanitizing (control characters, bidi and zero-width characters, cut length,
  surrogate-safe cut).
- `translate/PositionCoverageTest`, `session/HauteurSessionTest` (additions): the plain chat command is dropped
  while frozen and rewritten otherwise; the quarantine is per session and keeps the first error; every stats
  counter is reset by `resetStats` and counts again from zero.
- `HauteurConfigTest`: clamping, a malformed `\u` escape reported as an I/O error (the extension keeps its
  defaults), strict booleans (`freeze-hold-actions=flase` is reported, not silently false),
  sample file round trip.

### What the JUnit suite does not cover

- `HauteurHandlerTest` runs the handler on an `EmbeddedChannel` with the production layout (`hauteur` before the
  manager). It covers: passthrough at offset 0; a switch on high movement with the snap at `realY - offset`; a
  movement held across the freeze and replayed in the new frame; detach returning to real space (chunk replay,
  then snap at the last real Y); no snap on a closed channel; no switch when windowing is not needed, when the
  extension is stopped, or without a player entity; no snap for a client that has not spawned. It also covers the
  dropped-destroy re-check after a freeze: every destroy up to the limit is re-checked, and beyond it each one is
  counted in `GHOST_OVERFLOWS`.
  The handler depends on the `PlayerSession`, `PipelineSettings` and `Diagnostics` interfaces, not on Geyser
  types, so these tests need no Geyser runtime.

### What the JUnit suite does not cover

- The reflective installation of the world-manager wrapper (`HauteurCore.installShiftedWorldManager`) and the reload
  paths are not tested. They need a running Geyser. `ShiftedWorldManagerTest` tests the wrapper's read logic, with
  a stand-in delegate and both regimes (real world, session cache), not Geyser's own managers. `GeyserPlayerSession`
  and `GeyserDiagnostics` are thin delegations and are checked only by compilation and the manual procedure.
- No test runs a real packet stream through Geyser's translators, and no test runs against a real Bedrock client.
  The manual procedure, step 9, covers the detach path on a real client.

- Test counts are read from the Surefire XML on each run and published as a check-run annotation on the commit. This
  document does not copy them: the number changes with every test added, and a copied number goes stale.

## Manual procedure (not automated)

Run on a staging server with a Bedrock client, before any public deployment. Record the Geyser build, the
Minecraft Java and Bedrock versions, and the output of `/hauteur doctor` and `/hauteur stats` for each step.

1. **Preflight.** Extended-height world (for example `min-y: -64`, `height: 2016`). `/hauteur doctor` shows the
   pipeline attached and the world manager wrapped. `/hauteur info` near spawn shows offset 0.
2. **Passive proof.** Vanilla-height world (`height: 384`). `/hauteur stats` counters stay at zero after play.
3. **Teleport to altitude.** `/tp @s 0 1500 0` from Bedrock chat. Expect: no rubber-band, terrain visible at the
   player's feet, `/hauteur info` shows a new offset. Note whether the player's on-screen position is correct.
4. **Block edit then switch.** Place a block at client Y 500, climb about 300 blocks to force a switch. Expect the
   block to remain after the switch.
5. **Block-entity edit then switch.** Edit a sign at altitude, climb to force a switch. Expect the text to remain.
6. **Freeze interactions.** Dig and place blocks, open a sign, and edit a command block just before and during a
   forced switch (`/hauteur window <realY>`). Expect: no block appears at the wrong height, dropped digs are
   re-sent, `/hauteur stats` shows held and dropped counts.
7. **Movement across a switch.** Elytra or boat across a switch point. Expect no altitude jump after the snap.
8. **Respawn and dimension change at altitude.** Die, respawn; use a Nether portal and return. Expect the offset
   to reset and the window to re-derive without an out-of-window jump.
9. **Reload and disable at altitude.** `/geyser reload` while a player is at altitude. Expect the player to stay
   playable. Then set `enabled: false` and reload again while the player is still at altitude: expect the view to
   return to real space and the player to land at their real height, with no snap back to a shifted position and
   no reconnect.
10. **Failure injection** (optional). Temporarily break a transform in a test build. Expect the affected packet type
    to be quarantined and reported by `/hauteur doctor` for that session, and no packet in the wrong frame.
11. **Performance.** Measure a switch with a full view distance (for example 24 chunks). Record replay time on the
    event loop. No figure is claimed in the documentation until this is measured.
12. **Actions in a shifted chunk.** Stand at altitude, above the window's first switch, and check each of these in
    turn. Run the whole step three times: on a standalone Geyser without Erosion, on Geyser-Spigot, and on a
    standalone Geyser with an Erosion backend. These use the world-manager reads, which differ by platform.
    - Swim: enter water and leave it. Expect no sinking or bobbing at the surface, and the swim animation stops on exit.
    - Climb: a ladder and a vine. Expect the climb to hold and to stop at the top.
    - Place and break a block on the edge of the window. Expect the block to appear where it was placed.
    - Open a chest, a door, a decorated pot. Expect each to open at the block that was clicked.
    - Glide with an elytra, then land. Expect no collision correction on landing.
    - Pick up a dropped item, and throw an ender pearl or a snowball. Expect the pickup and the landing point to match
      the visible position.
    - Get hit by a mob from above. Expect the hit direction to point at the attacker.
    Record the Geyser build, the platform and any mismatch. Each mismatch is a bug report, not a note.

## Measured: chunk codec throughput (synthetic, not a server measurement)

`dev/bench/SectionCodecBench.java` times `SectionCodec.resliceTolerant` on generated 24-section overworld payloads
(sparse: 4-bit blocks, singleton biomes; dense: 8-bit blocks, explicit biome palette). It checks that the
generated payload parses with no anomaly before timing. Run it with a JDK 21:

```
javac -d /tmp/bench/classes src/main/java/io/github/maxlananas/hauteur/chunk/SectionCodec.java dev/bench/SectionCodecBench.java
java -cp /tmp/bench/classes SectionCodecBench 4000
```

Result on the sandbox used for this branch: seven runs of 4000 iterations, ranges shown. The sandbox is
shared and noisy, so read the ranges, not single values:

| Payload | Size | Per chunk | Throughput | 2048 chunks (full cache) |
|---|---|---|---|---|
| sparse | 3.8 KB | 2.6 to 3.8 µs | 1.0 to 1.5 GB/s | 5 to 8 ms |
| dense | 8.9 KB | 4.8 to 7.6 µs | 1.2 to 1.8 GB/s | 10 to 15 ms |

Reading: the codec runs at memory-copy speed, so there is no algorithmic headroom to recover here. A switch
replays every cached chunk (`replayWorld`). The cache is bounded by a chunk count (2048) and by a byte budget
(`chunk-cache-max-megabytes`, default 96 MB). With the synthetic chunks above, the count limit binds first: a full
cache holds about 8 MB (sparse) to 18 MB (dense), and its codec time is the 5 to 15 ms in the table. The byte budget
would only bind for chunks averaging about 48 KiB or more, which was not measured; at the same throughput, 96 MB
would take roughly 50 to 100 ms of codec time. Real chunks are larger than these synthetic ones, so the table is a
floor, not an estimate for a live server. The replay also performs Netty pipeline writes, which this benchmark does
not include.

Not measured: the replay on a live server, `WindowedChunks` with real MCProtocolLib packets, packet translation
per tick, and GC behaviour under load. Those remain open under item 11 above.

## Known open items

- No live-server performance figures. The only measurements are the synthetic codec numbers above.
- No validation with a real Bedrock client has been recorded for this version.
- The Maven distribution downloaded by `./mvnw` is not pinned by a checksum yet (`distributionSha256Sum` is unset).
  Pin it from the Apache release files before the first public release.
