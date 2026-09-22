# Changelog

## 1.3.0 (unreleased)

- Universal command oracle on **Mojang Brigadier**: the wire command tree is rebuilt into real Brigadier nodes (vendored MIT sources, package-relocated to `fr.buildtheearth.skywindow.brigadier`, see `THIRD-PARTY-NOTICES.md`) and parsed with Minecraft's own parser - literal-exact matching, argument fallback with backtracking, redirect aliases, and Y character ranges straight from the parse. The 1.2.0 structural walker remains as fallback engine; the grammar heuristics remain the last resort. Pinned contracts are unchanged.
- **In-game dev commands removed**: `/skywindow audit|map|reveal|preview` are gone - the extension is fully invisible and zero-config.
- Contributor credited as **MaxLananas** (extension manifest, POM, README).
- Deterministic seeded fuzz suite (`FuzzTest`): rewriter (never throws, token/length contracts), `resliceTolerant` (exact output lengths), tree index over hostile random trees (never throws).
- `docs/RESEARCH-NOTES.md`: Geyser's upstream "can't fix" (GeyserMC/Geyser#3804), BedrockHeightGuard's opposite approach, Brigadier MIT provenance.


All notable changes to SkyWindow. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) against the
pinned Geyser build (see README "Version compatibility" for why that pin *is* the API surface).

## [1.2.1] - 2026-09-21

Packet-precision release: the last command-text carriers are covered, with an exact character-level
mapping for the one round-trip that can desync under rewriting, and the "no packet forgotten"
contract now gates text payloads too.

### Added

- **Tab-completion round-trip is exact**: `ServerboundCommandSuggestionPacket` partial commands are
  rewritten like every command text; `ClientboundCommandSuggestionsPacket` suggestion ranges are
  mapped back to the player's editing frame through `SuggestionRanges` (token-aligned, character-
  exact offsets, per-transaction journal, degrade-forward when unknown). Both directions travel
  through the freeze/replay machinery as command packets.
- **`/skywindow preview <command...>`**: dry-run of the exact rewrite pipeline (type it, see what
  the wire would carry, execute nothing).
- **Audit v2**: the packet-coverage gate now fails CI when a known text carrier (7 entries) leaves
  the pipeline or a new `String command|text` packet field appears uncovered. Reviewed non-carriers
  documented (chat content; sign/book `run_command` values execute through the chat path at click
  time and are never rewritten in place).
- **`SectionCodecStressTest`**: reslice proofs at BTE scale (128 sections / 2048 blocks, the exact
  126-section offset geometry, mixed palette types at 96 sections, adversarial negative palette
  counts at scale).

### Fixed

- Suggestion range mapping walks full token coordinates (a common-prefix shortcut that skewed
  offsets was removed before release; every expected offset is hand-traced and pinned).

## [1.2.0] - 2026-09-21

The universal commands release: **every command of every plugin now works with zero configuration**.
Instead of naming commands, SkyWindow reads the Brigadier command tree the server itself sends
(`ClientboundCommandsPacket`) and shifts exactly the tokens the server would parse as coordinates.

### Added

- **`CommandTreeIndex`**: a walker over the server's declared command tree (literal-first matching,
  argument fallback with backtracking, redirect/alias expansion, nested-command recursion).
  `minecraft:vec3`/`minecraft:block_pos` arguments carry a Y that gets shifted; `column_pos`/
  `vec2`/`rotation` deliberately carry none; `MESSAGE`/greedy strings eat their tail untouched;
  plain numeric arguments named `y*`/`y1`/`maxHeight`-style shift like coordinates (plugin
  x/y/z-integer commands); `execute ... run` and run-as commands recurse into the root tree.
- The tree is the **primary** oracle for chat commands, command blocks, command minecarts and the
  command-block editor display (both directions). A plugin installed tomorrow is covered tomorrow,
  with nothing to declare.
- The built-in vanilla grammar and the `rewrite-commands`/`command-position-schemas` config remain
  as fallbacks for messages the tree cannot parse (typos, cut text, exotic untyped shapes).

### Changed

- `CommandYRewrite.rewrite` gained a tree-aware form; the three-argument form keeps the exact
  grammar-only behavior (all pinned tests unchanged).

## [1.1.1] - 2026-09-21

Builders' commands release: the Y-rewrite now covers the **entire positional command surface** -
chat commands, command blocks, command minecarts and the command-block editor display - and the
"every packet" contract is machine-enforced in CI.

### Added

- **Full vanilla command grammar rewrite** (`CommandYRewrite` v2): `tp`/`teleport` (all forms
  including `facing <pos>`), `setblock`, `fill`, `fillbiome`, `clone` (every corner), `summon`,
  `particle`, `playsound`, `damage ... at`, `data|item ... block`, `loot insert|spawn|replace block`,
  `setworldspawn`, `spawnpoint`, `spreadplayers ... under`, `forceload`, `place`, `placefeature`, and
  **recursive `execute ... run` chains** (`positioned`/`facing`/`if|unless block|blocks|loaded|biome|
  items block`/`summon`/`store ... block`, depth-capped). Relative (`~`/`^`) coordinates are never
  touched (the server resolves them in real space). SNBT/quoted regions are tokenization-protected
  and survive byte-identical.
- **Command blocks are now fully translated**: `SetCommandBlock` rewrites the stored `command`
  text alongside its position (edits are authored in window space, stored in real space), and the
  block-entity `Command` NBT on `ClientboundBlockEntityDataPacket` is rewritten back down for the
  editor display. Command minecart text (`SetCommandMinecart`) is covered too.
- **Custom plugin commands**: `command-position-schemas= name:i,j;...` (explicit Y-token indices,
  overrides the vanilla grammar per name) joins the legacy `rewrite-commands` first-triple allowlist;
  new master switch `rewrite-vanilla-commands` (default `true`).
- **`/skywindow explain <x y z>`**: builder converter (window ↔ real) with the rewritten `/tp`
  preview.
- **CI packet-coverage audit** (`dev/audit/packet_coverage.py`): fails the build if MCProtocolLib
  grows any positional packet the transform chains do not mention. Two reviewed relative-quantity
  exclusions documented in `dev/audit/coverage_allowlist.txt`.
- `docs/TERRA-NOTES.md`: how TerraPlusPlus/TerraPlusMinus/TerraMinusMinus place real-world altitude
  in Java worlds (they clamp to `maxWorldY`) and what that validates about SkyWindow.

### Changed

- `rewrite-commands` alone no longer decides whether rewriting is active; `rewrite-vanilla-commands`
  (default `true`) is the master switch for the built-in grammar. Servers that want the old
  allowlist-only behavior set `rewrite-vanilla-commands=false`.
- `InboundYTransforms.apply` gained a config-aware overload; the two-argument form keeps the vanilla
  grammar (behavior unchanged for callers).

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
  `TrackedWaypoint` VEC3I coordinates (locator bar), `DebugBlockValue` and
  `GameTestHighlightPos` (absolute half only).
- **Hidden absolute positions inside particle payloads**: `TrailParticleData` beam targets and
  `BlockPositionSource` vibration aims (Geyser's particle translator consumes the latter
  verbatim) are shifted; entity-relative sources and color/item payloads stay byte-identical.
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
- Ghost-revert dig actions matched `PlayerAction.FINISH_DIGGING` (the real enum constant — the
  audit draft's `STOP_DIGGING` never existed in MCProtocolLib).

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
