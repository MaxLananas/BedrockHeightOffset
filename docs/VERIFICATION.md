# Verification

What was checked, how to reproduce it, and what has not been checked. A passing build is evidence about the
Java-side logic. It is not evidence of how a Bedrock client behaves.

## Automated

| Check | Command | Where it runs | What it proves |
|---|---|---|---|
| Coordinate and codec oracle | `python3 dev/verify/oracle.py 5` | CI (`build.yml`), any machine | Python mirror of `WindowRules` and `SectionCodec`: round trips, reachable-window formula, switch cadence, reslice semantics, malformed-input behaviour. Five randomized rounds. |
| Compile and JUnit | `mvn -B verify` (JDK 21) | CI, on every push | Compiles against Geyser `2.11.2-SNAPSHOT` (resolved from the OpenGeyser repository) and runs the JUnit suite. |
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
- `AltusConfigTest`: clamping, strict booleans (`freeze-hold-actions=flase` is reported, not silently false),
  sample file round trip.

### What the JUnit suite does not cover

- `AltusHandler` is not exercised end to end. Its tests would need a live `GeyserSession`, which cannot be
  constructed without a running Geyser. The switch, freeze, flush and ghost-revert logic is therefore verified by
  review and by the manual procedure below, not by automated tests. This is a known gap.
- `AltusCore` installation, the world-manager wrapper, and reload paths are not tested.
- No test runs a real packet stream through Geyser's translators.

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
9. **Reload.** `/geyser reload` while a player is at altitude. Expect the player to stay playable; with
   `enabled: false`, the pipeline detaches without a reconnect.
10. **Failure injection** (optional). Temporarily break a transform in a test build. Expect the affected packet type
    to be quarantined and reported by `/altus doctor`, and no packet in the wrong frame.
11. **Performance.** Measure a switch with a full view distance (for example 24 chunks). Record replay time on the
    event loop. No figure is claimed in the documentation until this is measured.

## Known open items

- No measured performance figures.
- No validation with a real Bedrock client has been recorded for this version.
- The Maven Wrapper is not checked in; CI uses the Maven installed on the runner.
