# Verification

What was checked, how to reproduce it, and what has not been checked. A passing build is evidence about the
Java-side logic. It is not evidence of how a Bedrock client behaves.

## Automated

| Check | Command | Where it runs | What it proves |
|---|---|---|---|
| Coordinate and codec oracle | `python3 dev/verify/oracle.py 5` | CI (`build.yml`), any machine | Python mirror of `WindowRules` and `SectionCodec`: round trips, reachable-window formula, switch cadence, reslice semantics, malformed-input behaviour. Five randomized rounds. |
| Compile and JUnit | `./mvnw -B verify` (JDK 21, Maven 3.9.11 through the wrapper) | CI, on every push | Compiles against Geyser `2.11.2-SNAPSHOT` (resolved from the OpenGeyser repository) and runs the JUnit suite. |
| Test summary | `dev/verify/surefire_summary.py` | CI job summary | Per-suite counts read from the Surefire XML. Skipped tests must be justified. |
| Release consistency | `dev/release/check_release.py vX.Y.Z` | Release workflow | Tag equals `pom.xml` version; changelog section exists; tagged commit is on `main`. |

### What the JUnit suite covers

- `session/ChunkCacheTest`: journal (latest state per position, block change supersedes block entity), strict
  byte budget, oversized chunks, light data counted, journal overflow eviction, replacement, forget, resize,
  nearest-first ordering.
- `pipeline/FrameResolverTest`: frame attribution for held movement and block actions; ambiguous and unplausible
  cases dropped; property test (20 000 random switches) that the resolver never selects the wrong frame.
- `translate/PositionCoverageTest`: freeze disposition table, block-position extraction, absolute and relative
  teleports (including the own-player `ClientboundPlayerPositionPacket`), identity at offset 0.
- `translate/TransformPropertyTest`, `translate/WindowedChunksTest`, `translate/CommandYRewriteTest`: the Y
  shift is exact in both directions; relative quantities survive; chunk re-homing and block-entity pruning.
- `session/AltusSessionTest`, `window/WindowRulesTest`, `chunk/SectionCodec*Test`,
  `pipeline/HeldQueueTest`: window math, codec tolerance and fuzzing, the held-queue contract.
- `LogSanitizerTest`: player-name sanitizing (control characters, cut length, surrogate-safe cut).
- `AltusConfigTest`: clamping, strict booleans (`freeze-hold-actions=flase` is reported, not silently false),
  sample file round trip.

### What the JUnit suite does not cover

- `AltusHandlerTest` runs the handler on an `EmbeddedChannel` with the production layout (`altus` before the
  manager). It covers: passthrough at offset 0; a switch on high movement with the snap at `realY - offset`; a
  movement held across the freeze and replayed in the new frame; detach returning to real space (chunk replay,
  then snap at the last real Y); no snap on a closed channel; no switch when windowing is not needed, when the
  extension is stopped, or without a player entity; no snap for a client that has not spawned.
  The handler depends on the `PlayerSession`, `PipelineSettings` and `Diagnostics` interfaces, not on Geyser
  types, so these tests need no Geyser runtime.

### What the JUnit suite does not cover

- `AltusCore` installation, the world-manager wrapper, and reload paths are not tested. They need a running Geyser.
  `GeyserPlayerSession` and `GeyserDiagnostics` are thin delegations and are checked only by compilation and the
  manual procedure.
- No test runs a real packet stream through Geyser's translators, and no test runs against a real Bedrock client.
  The manual procedure, step 9, covers the detach path on a real client.

## Manual procedure (not automated)

Run on a staging server with a Bedrock client, before any public deployment. Record the Geyser build, the
Minecraft Java and Bedrock versions, and the output of `/altus doctor` and `/altus stats` for each step.

1. **Preflight.** Extended-height world (for example `min-y: -64`, `height: 2016`). `/altus doctor` shows the
   pipeline attached and the world manager wrapped. `/altus info` near spawn shows offset 0.
2. **Passive proof.** Vanilla-height world (`height: 384`). `/altus stats` counters stay at zero after play.
3. **Teleport to altitude.** `/tp @s 0 1500 0` from Bedrock chat. Expect: no rubber-band, terrain visible at the
   player's feet, `/altus info` shows a new offset. Note whether the player's on-screen position is correct.
4. **Block edit then switch.** Place a block at client Y 500, climb about 300 blocks to force a switch. Expect the
   block to remain after the switch.
5. **Block-entity edit then switch.** Edit a sign at altitude, climb to force a switch. Expect the text to remain.
6. **Freeze interactions.** Dig and place blocks, open a sign, and edit a command block just before and during a
   forced switch (`/altus window <realY>`). Expect: no block appears at the wrong height, dropped digs are
   re-sent, `/altus stats` shows held and dropped counts.
7. **Movement across a switch.** Elytra or boat across a switch point. Expect no altitude jump after the snap.
8. **Respawn and dimension change at altitude.** Die, respawn; use a Nether portal and return. Expect the offset
   to reset and the window to re-derive without an out-of-window jump.
9. **Reload and disable at altitude.** `/geyser reload` while a player is at altitude. Expect the player to stay
   playable. Then set `enabled: false` and reload again while the player is still at altitude: expect the view to
   return to real space and the player to land at their real height, with no snap back to a shifted position and
   no reconnect.
10. **Failure injection** (optional). Temporarily break a transform in a test build. Expect the affected packet type
    to be quarantined and reported by `/altus doctor`, and no packet in the wrong frame.
11. **Performance.** Measure a switch with a full view distance (for example 24 chunks). Record replay time on the
    event loop. No figure is claimed in the documentation until this is measured.

## Known open items

- No measured performance figures.
- No validation with a real Bedrock client has been recorded for this version.
- The Maven distribution downloaded by `./mvnw` is not pinned by a checksum yet (`distributionSha256Sum` is unset).
  Pin it from the Apache release files before the first public release.
- Test counts are read from the Surefire XML on each run and published as a check-run annotation. The last green
  run on `80ba465` (build run 40019275713, push) reported 126 tests, 0 failures, 0 errors, 0 skipped. This is the
  only test count this document relies on; it changes with every test added.
