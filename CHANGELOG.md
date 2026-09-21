# Changelog

All notable changes to SkyWindow. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) against the
pinned Geyser build (see README "Version compatibility" for why that pin *is* the API surface).

## [1.1.0] - 2026-09-21

Robustness release: an upstream-source re-audit (Geyser + MCProtocolLib master @ 2026-09-21) closed
every remaining "works most of the time" hole. Same architecture, same config, drop-in upgrade.

### Added

- **Full teleport/position packet family**: `ClientboundPlayerPositionPacket` is now actually
  translated *and* monitored (it previously only fed the switch monitor — the spawn/`/tp` frame bug),
  with the protocol's own **relative-Y rule** honored on `PlayerPosition`/`TeleportEntity`
  (`PositionElement.Y` ⇒ the Y field is a delta and is never shifted), `EntityPositionSync` and
  `MoveMinecart` lerp-step coverage added.
- **Absolute block-position entity metadata**: `BLOCK_POS`/`OPTIONAL_BLOCK_POS` metadata values
  (bed position, ender-crystal beam target, creaking home) are shifted via metadata *rebuild* —
  values are cloned, never mutated in place (the shared-metadata aliasing class of bugs).
- **Serverbound tool-edit packets**: `PickItemFromBlock`, `SignUpdate`, `SetCommandBlock`,
  `SetStructureBlock`, `SetJigsawBlock`, `JigsawGenerate`, `BlockEntityTagQuery`, `SetTestBlock`,
  `TestInstanceBlockAction` — all shifted at the same choke point.
- **Command rewriting** now covers both `ChatCommand` and signature-gated `ChatCommandSigned`
  (signed commands are never touched).
- **Visibility packets at altitude**: `OpenSignEditor`, `PlayerLookAt` anchor, `DamageEvent`
  source position, `Login`/`Respawn` `lastDeathPos` (death screen / recovery compass),
  `TrackedWaypoint` VEC3I coordinates (locator bar).
- **Hold queue widened** to *every* position-bearing serverbound packet during a switch freeze
  (sign edits, NBT queries, command-block saves… — not just dig/place), capacity 96.
- **Seam strategy B (bootstrap proxy)**: when no `WorldManager`-typed field accepts the wrapper
  (Spigot), a `Proxy` over `GeyserBootstrap` intercepts `getWorldManager()`, covering both the
  direct core path and `getBootstrap().getWorldManager()`.
- `getBlockAtAsync` follows the same `+O` correction as its sync twin.
- `ExtendedPacketCoverageTest` pins every policy above; the oracle mirrors the palette bounds.

### Fixed

- **Silent packet discard**: synthetic packets (snap, chunk replays, ghost reverts) were injected at
  the manager's own netty context and died at the tail (`lastInboundHandler`), never reaching Geyser.
  They are now fired from the SkyWindow context and traverse `manager` like real forwarded packets.
- **Frozen ghost placement/delete reverts** are recorded in real space (frame-resolved at drop time)
  and re-projected at unfreeze; dropped placements revert both the clicked block and the
  would-be-placed anchor (`pos+face`).
- **Forced switch retry** no longer deadlocks behind its own latch (`switchQueued` is re-armed at
  retry entry).
- `SectionCodec` rejects palette sizes outside `[0, 2^bits]` (fail-closed, mirroring vanilla).

### Changed

- `SkyWindowCore.TESTED_AGAINST` → `Geyser 2.11.2-SNAPSHOT / MCProtocolLib master-1.28.x @ 2026-09-21;
  docs/PACKET-MATRIX.md carries the audit details`. PACKET-MATRIX now states the relative-flag rule
  as contract text.

## [1.0.0] - 2026-09-08

First release of the rewritten extension. Everything below replaces the 3.x plugin line wholesale;
**3.x and 1.0.0 must never run on the same server** (see "Removed").

### Added

- **Height windowing.** Bedrock players climb above the client-side Y limit (extended-height
  overworlds up to real 1952) via a per-session offset O, applied inbound as −O and outbound as +O
  at the single upstream-pipeline choke point, with automatic re-homing switches (margin + cooldown
  + anti-oscillation backoff up to 8x) and a ~400 ms freeze protocol around each switch.
- **Chunk re-slicing** (`SectionCodec`): section array of `LevelChunkWithLight` payloads is moved as
  whole byte ranges; tolerance-first (malformed input degrades to air, never to a decode exception
  in Geyser's chunk path); parsed sections preserved byte-identical.
- **Freeze hold & replay**: block interactions (dig/place) *and* movement sent during a switch
  freeze are queued in a bounded lock-free FIFO and replayed on unfreeze in the frame they were
  captured in (`freeze-hold-actions`, `freeze-hold-movement`), with reach-checked replay decisions
  and authoritative ghost-block re-sends where a held packet had to be dropped.
- **Anti rubber-band**: per-session `WorldManager` decorator reports window-corrected collision
  heights so Geyser's own correction logic cannot yank windowed players (the 3.x bug, fixed at the
  root instead of by counter-packets).
- **Command rewriting** for the unsigned `tp`-family: absolute Y in the first position triple,
  format-preserving, length-capped (512) before scanning; relative and caret coordinates untouched.
- **Ops/diagnostics**: `/skywindow info|watch|recent|doctor|stats [reset]|window <realY>` with
  nanosecond freeze accounting, quarantine registry for deterministically failing packet types,
  degraded-attach reporting, and the exact audited Geyser build printed in `doctor`.
- **Config hardening**: every numeric key clamped to a safe range; invalid values and unknown keys
  collected as load-time warnings (logged, never fatal); a shipped sample file whose values are
  test-pinned to equal the code defaults.
- **Safety nets**: 60 s session-close sweeper (reclaims state when disconnect events don't fire),
  already-wrapped chunk payloads detected as no-op (double-shift protection), `worldGeneration`
  epoch so post-respawn ghosts/held entries can never touch a new world, promise completion on
  every abandoned hold path (no hanging Geyser write futures).
- **Test suite**: 84 JUnit tests (unit + property-style + fuzz, incl. a 100k-entry MPMC race test for
  the held queue) plus `dev/verify/oracle.py`, a standalone Python mirror of the window/chunk
  contract executed both locally and before every CI build.
- **Project**: Maven enforcer (Java 21, banned Bukkit/Spigot/Paper/ProtocolLib and Geyser-Spigot
  dependencies, upper-bound conflict gate), `-Xlint:all`, dependabot for maven+actions, CI on
  push/PR + weekly schedule, release workflow publishing jar + sha256 on `v*` tags, editorconfig
  and gitattributes normalization.

### Changed

- (from 3.x) Everything. The old coordinate-mirroring plugin is not an evolution target: this is a
  Geyser *extension* living in the upstream mcprotocollib pipeline instead of a Paper plugin
  rewriting packets on both sides.

### Removed

- 3.x `BedrockPacketInterceptor`, `BedrockHeightOffset` plugin, reflection into
  `GeyserSession`, `config.yml`, and the manually committed `target/` build artifacts.

## [3.x] - deprecated

Last 3.x tag (`3.7.0`) is archived on the `3.7.0` branch. It stays documented only in
README "What was wrong with the old implementation". No further releases; update to 1.0.0.
