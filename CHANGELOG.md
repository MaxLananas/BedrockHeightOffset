# Changelog

All notable changes to Altus. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) for the extension's configuration,
commands and jar name. The pinned Geyser build is listed in the README.

## [Unreleased]

Changes since 1.0.0. Not yet released; the version in `pom.xml` is `1.1.0-SNAPSHOT`.

### Changed

- The project is now **Altus** (formerly SkyWindow). Jar: `altus-<version>.jar`. Package:
  `io.github.maxlananas.altus`. Extension id: `altus`. Command: `/altus`.
- Maven `groupId` is now `io.github.maxlananas` (it was `fr.buildtheearth` in 1.0.0). The repository name is unchanged.
- Migration: `extensions/skywindow/config.properties` is copied to `extensions/altus/` on first start (the old file
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

### Fixed

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
  dropped (fail closed) and reported by `/altus doctor`.
- Chunk-cache access from the session and reload threads was unsynchronized.

### Added

- `/altus stats` shows chunks that could not be replayed, journal overflows and transform failures.
- Release workflow: tag and Maven version must match, the tagged commit must be on `main`, existing releases are
  verified rather than overwritten, and the jar and checksums are published together with build information.
- Build workflow publishes the Surefire test summary on the job page.
- Logo under `assets/logo/`: a 1024 px master, PNG exports (512, 128, 32) and a monochrome variant, regenerated
  by `dev/release/export_logo.sh`. The logo replaces the earlier SkyWindow/Hypso artwork.

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
