<p align="center">
  <img src="assets/logo/hauteur-512.png" width="128" alt="Hauteur logo">
</p>

# Hauteur

Hauteur is a [Geyser](https://geysermc.org) extension. It lets Bedrock players build and move at Java-world
heights that the Bedrock client cannot display natively (for example around Y = 1950 on a BuildTheEarth-style
world), by shifting the vertical window the client sees as the player climbs.

> **Hard limit.** The Bedrock client only renders a fixed vertical range, set by the client itself
> (`minecraft:dimension_bounds`). No server-side software can extend it. Hauteur does not bypass that range;
> it moves the window along the world. Content outside the current window is not displayed, and the window
> is re-homed with a short, visible adjustment.

Status: **experimental**. The translation logic is covered by automated tests and a Python oracle, but it has
**not** been verified against a real Bedrock client on a live server. See [Verification](docs/VERIFICATION.md).

## What it does

- Keeps one integer per player, the offset `O` (a multiple of 16). Client Y = real Y − `O`.
- Translates every absolute-Y packet at one point in Geyser's Java-side pipeline. Relative quantities
  (motion, cursor offsets, relative teleports) are never shifted.
- Caches the original chunk packets and the block changes received since, so a window switch rebuilds the
  client's view without asking the server again.
- Holds, resolves or drops client packets sent during a switch's short freeze. Each decision is explicit
  (see [Architecture](docs/ARCHITECTURE.md) and [Packet matrix](docs/PACKET-MATRIX.md)).
- Does nothing on ordinary worlds: when the real dimension already fits the client window, no chunk is
  cached and no packet is changed.

## Requirements

| Component | Version | Status |
|---|---|---|
| Java | 21 | Used by CI (Temurin 21). |
| Maven | 3.9.11 | Provided by the Maven Wrapper (`./mvnw`); CI and release use it. A local Maven 3.9+ also works. |
| Geyser | `2.11.2-SNAPSHOT` (`geyser.version` in `pom.xml`) | The API surface in use was checked against the Geyser and MCProtocolLib sources of that snapshot. |
| Server | A Geyser-supported platform (Spigot/Paper, Fabric, Velocity, BungeeCord, standalone) | Windowing is exercised by automated tests only. No live server has been tested for any platform. |
| Bedrock client | Current release | Not tested in this repository. |

Hauteur has not been tested alongside the 3.x Bukkit plugin or any other plugin that rewrites chunks or player
positions. Do not assume the two coexist.

## Installation

1. Install Geyser on your server (see the [Geyser setup guide](https://geysermc.org/wiki/geyser/setup/)).
2. Download `hauteur-<version>.jar` from the [releases page](../../releases) and verify it:
   ```sh
   sha256sum -c SHA256SUMS.txt
   ```
3. Copy the jar into the Geyser extensions folder:
   - Spigot/Paper: `plugins/Geyser-Spigot/extensions/`
   - Standalone: `extensions/` next to the Geyser jar
4. Start the server. A `config.properties` file is written to `extensions/hauteur/`.
5. From a Bedrock client, run `/hauteur doctor`. It reports whether the pipeline and the world-manager wrapper
   are attached.

Upgrading from 1.0.x: the configuration in `extensions/skywindow/` is copied to `extensions/hauteur/` on the
first start (the old file is kept), and the old commands `/skywindow`, `/sw` and `/bho` remain as aliases.

## Configuration

Edit `extensions/hauteur/config.properties` (see the sample written on first start). Unknown keys and invalid
values produce a startup warning and keep the default. A value outside a key's range is clamped to the nearest
limit and the change is logged the same way. Most keys are reloaded with `/geyser reload`.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `switch-margin-blocks` | `64` | Re-home the window when the player is this close to its edge (32–256). |
| `switch-cooldown-ms` | `2000` | Minimum time between two switches for one player (250–60000). |
| `freeze-ms` | `400` | Length of the freeze after a switch (100–5000). |
| `freeze-hold-actions` | `true` | Hold block actions sent during a freeze and replay them when their frame is unambiguous. `false` drops them, with a block re-send for dropped digs. |
| `freeze-hold-movement` | `true` | Hold movement sent during a freeze and replay it in the frame it was sent in. `false` drops it. |
| `announce-switches` | `false` | Chat message on each switch. |
| `chunk-cache-max-chunks` | `2048` | Maximum cached chunks per player (64–32768). |
| `chunk-cache-max-megabytes` | `96` | Byte budget for cached chunks per player, strict (8–1024). |
| `max-offset-blocks` | `0` | Cap on the offset, aligned down to 16. `0` derives it from the world. |
| `rewrite-commands` | `tp,tppos,teleport` | Unsigned chat commands whose absolute Y is shifted. Empty disables it. |
| `log-switches` | `true` | One log line per switch. |

## Commands

`/hauteur` (aliases `/skywindow`, `/sw`, `/bho`), Bedrock players only:

- `info`: offset, window bounds, cached chunks.
- `watch on|off`, `recent`: the last translated packets, for debugging.
- `doctor`: attachment and world-manager status, packet types quarantined in this session, Geyser build.
- `stats [reset]`: counters for this session (translations, replays, switches, held and dropped packets,
  journal overflows, transform failures).
- `window <realY>`: force a window switch around a real Y (builder tooling).

## Known limitations

These are current, tested-or-documented facts, not promises:

- **Not verified on a real Bedrock client.** Automated tests cover the Java-side translation and the decision
  logic only. Freeze timing, client adoption of the snap and visual results need the manual procedure in
  [VERIFICATION.md](docs/VERIFICATION.md).
- **Client adoption is inferred from time.** Hauteur does not receive an acknowledgement that the Bedrock client
  has adopted a new frame. The freeze lasts `freeze-ms`, and held packets are resolved by plausibility.
- **Ambiguous packets are dropped.** When a switch is small (for example a clamp at the world edge) and both
  frames are plausible, held movement is dropped rather than guessed. The player may stall briefly.
- **Chunks above the cache budget are not replayed.** A chunk larger than `chunk-cache-max-megabytes` on its
  own is not cached. After a switch it is missing until the server sends it again. A chunk whose block-change
  journal exceeds 4096 positions is evicted for the same reason.
- **Light data is not re-homed.** Geyser does not read light updates, so this does not affect Bedrock output,
  but the data is kept with the chunk and counted against the budget.
- **World reads for collision and containers depend on the wrapper.** If the world-manager wrapper cannot be
  installed, `/hauteur doctor` reports it, and direct world reads inside Geyser may disagree with the window.
- **Not translated:** packets without an absolute Y (the server's own `ChunksBiomes` payload is ignored by
  Geyser and not windowed), test-block debugging packets, and signed chat commands (rewriting would break
  their signature). See [PACKET-MATRIX.md](docs/PACKET-MATRIX.md).
- **Failure policy.** When a packet type fails to transform while the window is active, that type is dropped
  for the session (fail closed) and logged once. The quarantine belongs to the session: a reconnect starts with
  an empty list.
- **Performance is measured only on synthetic chunks.** A window switch replays every cached chunk on the channel
  event loop. `dev/bench/SectionCodecBench.java` times the codec alone (see
  [VERIFICATION.md](docs/VERIFICATION.md)); the replay cost on a live server with real chunks has not been measured.

## Verification

Reproduce the automated checks:

```sh
python3 dev/verify/oracle.py 5   # coordinate-math and codec oracle, no dependencies
python3 -m unittest discover -s dev/verify -p 'test_*.py'   # test summary tool
python3 -m unittest discover -s dev/release -p 'test_*.py'  # release check
./mvnw -B verify               # compiles against Geyser, runs JUnit (Java 21)
```

CI runs all of them on every push (`.github/workflows/build.yml`) and publishes the Surefire summary on the job page.
The current state of each check is in [docs/VERIFICATION.md](docs/VERIFICATION.md).

## Reporting problems

- Security issues: see [SECURITY.md](SECURITY.md). Do not open a public issue.
- Bugs: open an issue with the Geyser build, the server platform, the Minecraft Java and Bedrock versions,
  the output of `/hauteur doctor` and `/hauteur stats`, and, if possible, the output of `/hauteur watch on` followed
  by `/hauteur recent` right after the problem.

## Documentation

- [Architecture](docs/ARCHITECTURE.md): model, freeze protocol, failure policy.
- [Packet matrix](docs/PACKET-MATRIX.md): what is translated, held, dropped or left alone, packet by packet.
- [Verification](docs/VERIFICATION.md): automated checks and the manual procedure.
- [Changelog](CHANGELOG.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md)
- Decision records: [docs/adr](docs/adr/).

## Licence

Hauteur is released under the [MIT License](LICENSE). Copyright (c) 2026 MaxLananas.
