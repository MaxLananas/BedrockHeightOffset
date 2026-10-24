# Changelog

All notable changes to Hauteur. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) for the extension's configuration,
commands and jar name. The pinned Geyser build is listed in the README.

## [Unreleased]

Changes since 1.0.0. Not yet released; the version in `pom.xml` is `1.1.0-SNAPSHOT`.

### Changed

- The project is now **Hauteur** (formerly SkyWindow). It was briefly named Altus on this branch; the name was
  dropped before any release because a NeoForge mod already uses the `/altus` command. Jar: `hauteur-<version>.jar`. Package:
  `io.github.maxlananas.hauteur`. Extension id: `hauteur`. Command: `/hauteur`.
- Maven `groupId` is now `io.github.maxlananas` (it was `fr.buildtheearth` in 1.0.0). The repository name is unchanged.
- Migration: `extensions/skywindow/config.properties` is copied to `extensions/hauteur/` on first start (the old file
  is kept). `/skywindow`, `/sw` and `/bho` remain as command aliases.
- Licensed under the MIT License (see `LICENSE`).
- GitHub Actions are pinned to commit SHAs, with the tag in a comment. The workflows run with read-only
  permissions by default; only the publish job can write to releases.
- CI, release and the README use the Maven Wrapper (`./mvnw`, Maven 3.9.11, Apache `only-mvnw` script). The
  distribution is not yet pinned by checksum; see `.mvn/wrapper/maven-wrapper.properties`.
- Test results are published as check-run annotations: one totals line and one error per failing test. They stay
  readable when the job log is not.
- The pipeline no longer depends on Geyser session types. It reads the session, settings and logger through
  small interfaces (`PlayerSession`, `PipelineSettings`, `Diagnostics`) with Geyser adapters. This is internal.
- The per-player transition state (window offset, freeze flags and epoch, anchor) is private to `HauteurSession` and
  read and written through accessors. This is internal.
- The `/hauteur stats` counters are addressed by one enum (`HauteurSession.Stat`). A reset captures every counter
  by construction, so a new counter cannot be left out of the reset.
- Release jars are reproducible for a given tag: entry timestamps are taken from the tagged commit. A release built
  against a Geyser SNAPSHOT is flagged in the workflow log, because the snapshot can change under it.

### Fixed

- A dropped destroy was recorded for the re-check after a switch only for the first eight distinct positions of
  that freeze. Later ones were not re-checked, so the client could keep showing a block the server still has, and
  nothing counted them.
  The limit is now 256 positions (about 16 KB, estimated), and anything beyond it is counted (`GHOST_OVERFLOWS`).
- With a switch margin of half the window height minus 8 or more (for example 128 on a 256-high window), the window
  re-homed at almost every section boundary: about 55 switches per 1000 blocks walked, with one-block reversals,
  each one a frozen input and a chunk replay. The margin is now capped at half the window height minus 16. The
  default (64) is unchanged. Measured by simulating the policy; not reproduced on a live client.
- A player action that names no block (releasing the held item, swapping hands, stab, and a drop from the inventory)
  was shifted by the window offset and, during a switch, held and resolved as a block action. Geyser sends these with a
  zero position, which is not a block, so the action was dropped when neither frame was plausible. The consequence (a
  released bow or food left in use on the server) follows from the code and was not reproduced on a live server.
  These are now sent unchanged and never held. Digging, and a drop taken from the block-break action (which carries the
  client's block position), still name a block and are shifted and held as before.
- The chunk cache evicted the oldest chunks to fit its budget without any record. Those chunks were then missing after a
  window switch, with no counter to explain it. Budget evictions are now counted (`CHUNK_EVICTED_BUDGET`, shown by
  `/hauteur stats`), and the README states the eviction rule.
- `rewrite-commands=/tp` kept the slash, never matched a chat message, and silently disabled the rewrite. A leading
  slash is now accepted, and an entry that is not a plain command name is reported and ignored.
- Window targets for very large heights (above about 2e9 blocks) overflowed an int product and went to offset 0, while
  larger values went to the maximum. The snap is now computed and clamped in double precision; non-finite heights give 0.
- Player names: a stacked combining-mark name ("zalgo") is capped at three marks per base character, so it cannot bloat a
  log line. The zero-width joiner stays allowed, as documented.
- Standalone Geyser without Erosion: direct world reads (collision correction, swimming and climbing checks) were shifted
  by the offset a second time, because Geyser reads its own window-space chunk cache there. This comes from reading
  Geyser's source and was not reproduced live. The wrapper now shifts only the reads that reach the real world, with
  Geyser's own rule.
- With an Erosion backend, async lookups fell back to the synchronous read, and each batch read sent one request per
  block. Both now reach the delegate's async and batch methods, with the offset applied.
- Failures that were swallowed silently are now logged: the session sweeper (each run and its scheduling), restoring
  the platform world manager, and re-reading a block to revert a dropped destroy. Each used to leave a stale state with no trace.
- The held-write queue could exceed its limit under concurrent writes: producers checked the size, then added, so
  several could pass the check together. A slot is now reserved first, and the limit is exact (a test with eight
  racing producers shows 101 accepted entries against the old code, 100 against the new one).
- Damage events carry an absolute source position. It was sent unshifted, so the hit direction pointed one window
  offset away from the real source. It is now shifted like the explosion centre; an absent position is left as it is.
- `/hauteur window` accepted `NaN`, infinite and out-of-dimension values, and the handler could anchor on a non-finite
  Y. Such a value could snap the client outside the world on detach. Both now refuse it, and a non-finite client Y
  drops the packet without moving the anchor.
- `/hauteur stats reset` did not reset the freeze-time total, so the average freeze after a reset mixed two periods.
- Packets held during a window switch were written but never flushed. They could wait in Netty's outbound
  buffer until the next packet from Geyser, which may never come if the player stops moving. The freeze now ends
  with a flush.
- Disabling or reloading the extension while a player was shifted left the client in the shifted frame, and its
  next movement reached the server at the wrong height. The client now gets its real-space view back (cached chunks
  replayed at offset 0, snap to the real Y) before the pipeline is removed.
- A reload after an earlier enable could leave a world-manager wrapper that disable never restored. The original
  manager is now adopted from the existing wrapper and restored on disable.
- A failed dimension read replaced the window with "not needed", switching windowing off while a player was
  shifted. The previous snapshot is kept instead.
- Shutdown now cancels the session sweeper; its scheduled task used to outlive the extension.
- Player names in logs go through one sanitizer: control characters are replaced, names are cut to 32 characters
  without splitting a surrogate pair.
- Window switches replayed the chunk as it arrived and reverted later block and block-entity edits. Each cached
  chunk now keeps a journal of those changes and replays it.
- The chunk-cache budget was not strict: a 32-chunk exemption let it be exceeded, and light arrays were not
  counted. The budget is now strict, and an oversized chunk is not cached.
- Own-player teleports (`ClientboundPlayerPositionPacket`) were not translated, so a teleport to altitude reached
  the Bedrock client at the real Y. They are now translated, and they trigger the window switch.
- Relative Y values in teleport packets were shifted as if absolute.
- During a freeze, sign, command block, structure, jigsaw and pick-block packets were sent untranslated, placing
  them at the wrong height. They are now held as block actions.
- Packets held across a switch were always replayed in the pre-switch frame, including movement sent after the
  client had adopted the new one. Held packets are now resolved against the player's last known position, and
  dropped when no frame is plausible.
- The stale offset survived a respawn or login, so direct world reads could use the wrong height.
- Destroyed-block ghosts were re-sent for the wrong position after a switch.
- `freeze-hold-actions=false` had no effect.
- Invalid boolean values were silently read as `false`; they now keep the default and produce a warning.
- Changes to the chunk-cache budget on reload did not apply to connected players, and re-enabling the extension
  did not attach connected players until they reconnected.
- A packet type whose transform failed was passed through untranslated while the window was active. It is now
  dropped (fail closed) and reported by `/hauteur doctor`.
- Chunk-cache access from the session and reload threads was unsynchronized.
- The quarantine of failing packet types was kept for the whole process, so one player's failure silenced
  translation for every later player. It is now per session, and a reconnect starts with an empty list.
- Negative palette sizes in a chunk section were skipped as if valid. They are now reported as malformed.
- Command rewrite: decimal results lost precision (`-15.99999 + 16` became `0.000009999999999621423`), a double
  space inside the coordinate triple was collapsed, and absurd exponents could build very large numbers. Decimal
  shifts are exact, whitespace is kept, and inputs that cannot be represented are left unchanged.
- Plain (unsigned) chat commands are rewritten and dropped while the frame is uncertain, like their signed
  counterparts. Geyser sends only the signed form today, so this is hardening.
- Player names: Unicode bidirectional overrides, zero-width spaces, the byte-order mark and line or paragraph
  separators are neutralised in logs. The zero-width joiner (U+200D) is kept, so emoji sequences stay readable.
- Configuration values outside a key's range (for example `switch-margin-blocks=9999`) are still clamped, but the
  change is now logged at startup or reload. Previously the clamp was silent.
- `/hauteur watch` turned the watch on for any argument other than `off`. It now accepts only `on` and `off`.
- `/hauteur stats reset` did not reset chunks that could not be replayed, journal overflows or transform failures.
- `rewrite-commands` is trimmed and lower-cased, so an entry with spaces or capitals still matches.
- A malformed `\uXXXX` escape in `config.properties` stopped startup; it now falls back to the defaults and logs.
- Reload work was done on Geyser's pre-reload event, which Geyser's own disable and enable then undid. Only the
  post-reload event now triggers it (see `docs/adr/0004-reload-lifecycle.md`).
- A scheduler retry that raced with Geyser's shutdown could throw; it now stops quietly.
- A block change recorded on the channel thread while a window switch read a cached chunk's journal could race that
  read. The journal is now guarded by its entry's lock.

### Added

- `/hauteur stats` shows chunks that could not be replayed, journal overflows and transform failures.
- Release workflow: tag and Maven version must match, the tagged commit must be on `main`, existing releases are
  verified rather than overwritten, and the jar and checksums are published together with build information.
- Build workflow publishes the Surefire test summary on the job page.

- Continuous integration runs the Python tests of `dev/verify` and `dev/release`, compiles the codec benchmark
  and runs a short smoke pass of it. The weekly run re-resolves the Geyser snapshot (`-U`) as its comment says.
- `dev/release/check_release.py` names a `-SNAPSHOT` version explicitly, and ends a changelog section at the next
  level-2 heading.

### Known limitations

See the README. In particular, the behaviour has not been verified on a real Bedrock client, and no performance
measurement is recorded.

## [1.0.0] - 2026-09-08

First release of the rewritten extension (then named SkyWindow). It replaced the 3.x plugin line; 3.x and 1.0.0
must never run on the same server.

- Height windowing with a per-player offset, applied at a single pipeline choke point, with margin, cooldown and
  backoff, and a freeze around each switch.
- Chunk re-slicing for extended-height payloads.
- Freeze hold and replay for block actions and movement.
- A `WorldManager` decorator for direct world reads.
- Command rewriting for unsigned `tp`-family commands.
- `/skywindow info|watch|recent|doctor|stats|window` diagnostics.
- Configuration clamping and a sample file whose values are tested against the code defaults.
