# SkyWindow

**Let Bedrock players join a Java server with an extremely tall world — like BuildTheEarth — and
build, move, and interact near Y=1952 without rubber-banding or block ghosts.**

SkyWindow is a ground-up rewrite. It is no longer a Bukkit plugin that fiddles with Bedrock packets;
it is a **Geyser extension** that translates absolute Y coordinates exactly once, per player, on
Geyser's Java-side protocol pipeline, into the client's *own* dimension range. Everything else —
Geyser's translators, the Bedrock protocol, the Java server — keeps speaking its normal
coordinates, unchanged and un-hooked.

> **The single most important number:** the Bedrock client can only ever *see* Y ∈ [-512, 512] in
> the overworld ([-64, 320] on clients/servers without extended-height negotiation). That is a
> hard, data-driven client limit that **no server-side software can lift** — see
> [The actual limit](#the-actual-limit-read-this-first). SkyWindow does not try to escape it;
> it *moves the world* through that window as players climb. Y=1952 becomes reachable and fully
> interactive, at the cost of a rare, nearly-invisible "window shift" roughly once per ~450 blocks
> climbed.

---

## Table of contents

- [What was wrong with the old implementation](#what-was-wrong-with-the-old-implementation)
- [The actual limit (read this first)](#the-actual-limit-read-this-first)
- [How SkyWindow works](#how-skywindow-works)
- [Why rubber-banding used to happen, and what specifically fixes it](#why-rubber-banding-used-to-happen-and-what-specifically-fixes-it)
- [Architecture](#architecture)
- [Installation](#installation)
- [Configuration](#configuration)
- [Commands and debugging](#commands-and-debugging)
- [Verification and tests](#verification-and-tests)
- [Known limitations (honest list)](#known-limitations-honest-list)
- [Performance](#performance)
- [Version compatibility](#version-compatibility)
- [Risks](#risks)

---

## What was wrong with the old implementation

The 3.x line translated coordinates on the **Bedrock wire** (via reflection into Geyser internals)
and kept a global offset notion. Auditing it against Geyser master produced a long defect list; the
ones that actually destroyed the "build at altitude" experience were:

1. **Double and triple translation.** The same Y passed through offset logic at the Bukkit layer,
   at the netty layer, and again implicitly through Geyser's cache, so the round trip
   (client → server → client) was not the identity. A placed block's confirm coordinate and the
   server's stored coordinate differed by one stale offset → *blocks placed, then deleted* — that
   "deletion" is the server being right and the client being lied to.
2. **Bedrock-side validation fights.** Geyser and the Bedrock client validate positions against the
   *dimension the client negotiated*. Faking out-of-window coordinates on the wire means half the
   stack silently clamps, drops or "corrects" them. The corrections are the rubber-banding: they
   are the client doing its documented job against inconsistent inputs.
3. **A global offset with per-player latency.** Offsets were applied on the Bukkit main thread and
   read on packet threads; during an offset transition, packets translated in-flight used the old
   value with new content, and the mismatch *was* the visible catastrophe.
4. **Byte-scanning chunk payloads** and patching subchunk index bytes in place: false positives on
   lookalike byte patterns corrupted palettes (chunk incoherence, missing blocks).
5. **Shifting things that are relative** (motion, velocity, cursor offsets) is a whole bug class
   of its own; relative quantities must never see an offset.

There is no salvageable part of that architecture in 4.0; everything is replaced.

## The actual limit (read this first)

Bedrock determines its own world bounds from `minecraft:dimension_bounds` (data-driven since the
1.18 height push). The **overworld** accepts bounds that are multiples of 16 within
**[-512, 512]**; other dimensions (Nether, End, and everything Geyser maps to a "384-height"
dimension) are capped at **1024** and **256** respectively. Outside the negotiated bounds the
client refuses to build/show chunks and sections, and blocks placed "by faith" past the bound are
reverted by the client's own authority check.

- **This is a client-side limit.** Minecraft: Bedrock Edition's executable must be modified to
  lift it. Every server-side "bypass" including SkyWindow is, by definition, unable to change it.
  (Geyser itself lists this as unfixable in its Current Limitations.)
- What *can* be done server-side — and what old SkyWindow tried and failed to do — is relabel which
  part of the tall Java world is currently inside the client's window.
- SkyWindow does that relabeling **one layer below the Bedrock wire**, on the Java protocol packets
  Geyser consumes. This is what makes the trick composable: Geyser never sees fake coordinates,
  so it never enters "correcting" mode.

Consequences of living inside the window rather than faking outside it:

- The highest **placeable** real block is `offset + clientMaxBlockY`; the highest player foot Y is
  one above that. With the default configuration on a 1952-tall world, the extension pins the
  offset at 1440, giving exactly `1440 + 511 = 1951` for blocks (the whole world, since 1952 is a
  player-stand-on-top height, not a place-on height) — see
  [the offset math](#the-math) for the general formula. No arbitrary "1952" ceiling exists in the
  code; it's derived from your dimension and your client.
- A player's client-side coordinate readout (F3-style debug screens) shows **window space**, not
  real Y. `~`-relative commands and the `/skywindow info` command exist for exactly that reason.

## How SkyWindow works

### The model

Per player, SkyWindow keeps one integer: the **offset** `O`, a multiple of 16, `0 ≤ O ≤ Omax`.

```
clientY = realY − O      (everything Geyser and the client work with, downstream)
realY   = clientY + O    (everything the Java server works with, upstream)
```

- Applies to **absolute Y only**: block positions, entity positions, particle/sound/explosion
  positions, section indices, chunk payloads, spawn point. Never to X/Z (the Bedrock client has
  no problem there), and **never to relative quantities** — motion, velocity, deltaMovement,
  cursor-within-block, look vectors, structure offsets/sizes are forwarded byte-identical.
- The invariant `realY = clientY + O` is checked at every packet boundary in both directions, and
  the whole design is built so that a *stale* O used for one packet still yields the *same* real
  coordinate that the client is currently standing at, which is what makes races survivable
  (details in [Architecture](#architecture)).

### The math

- `clientMinY`, `clientHeight`: **read from the session's own `BedrockDimension`** — i.e. from
  what the client and Geyser actually negotiated for this player. `-512/1024` for extended-height
  overworld clients, `-64/384` for legacy ones, `0/256` for Nether/End. Nothing is hardcoded.
- `javaMinY`, `javaMaxY`: read from the session's real dimension (Geyser's `ChunkCache` registry
  values, converted from section units to blocks).
- `windowingNeeded = the real dimension does not fit in the client window`.
  **On a normal server this is false, and SkyWindow is a provable no-op** — it stays fully passive,
  caches nothing, allocates nothing.
- `Omax = floor16(javaMaxY − clientMinY − clientHeight)` — the smallest offset that keeps the
  world's top placeable layer inside the window; going higher would push the roof under the
  client's floor without making anything reachable. (For BTE-shaped worlds:
  `1953+512−1024 = 1441 → 1440`, so top real block 1951 → client 511. ✔)
- Target offset for player at `realY`: `O* = snap16(clamp(realY − center, 0, Omax))`, where
  `center = clientMinY + clientHeight/2` (0 for the overworld). This *centers the window on the
  player*, which is also the hysteresis: after a switch the player sits mid-window, and the next
  switch only triggers when the window edge approaches again — about 450 climbed blocks later at
  full window height. No oscillation state is kept because none is needed.

### The window switch

When the monitor (fed by the player's own position packets and by teleports) decides a switch is
due, it executes **as one task on the player's netty event loop**:

1. Flip `O`. (Inbound and outbound translation read `O` at this same thread or as a volatile, so
   no packet is ever translated by "both" values.)
2. **Replay every cached chunk** — the originals are stored untouched, so the replay re-derives
   each windowed payload from the pristine server data. This is why *no* chunk re-request from the
   Java server is needed: the server's sent-set for that player is never disturbed, and Java
   players see nothing at all. Nearest chunks are injected first.
3. Inject a teleport that snaps the player's client-side position to
   `realY − O_new` at their current X/Z and rotation, so their view, camera and collision
   reference frame move with the terrain, not after it.
4. Hold outbound *position-bearing* packets (movement, block actions, use-item, vehicle moves)
   for ~400 ms (`freeze`). The client's stale frame is the one interval where a shifted Y could
   lie; rather than translate a lie, SkyWindow waits out a few hundred milliseconds. Movement resumes on
   its own — the next move packet is already correct. (Block-break ghosts from held destroy
   packets are reverted explicitly on unfreeze, via the authoritative block read.)
5. A cooldown (`switch-cooldown-ms`) debounces any pathological repeat.

The switch is designed to be nearly unnoticeable (no unloaded flashes thanks to the replay, no
fall thanks to the snap being issued in the same batch as the terrain), but it is *not literally
free*: mid-air it feels like a brief positional hiccup. It is also only ever *possible* because of
the invariant above — a packet translated with the old O or the new one still lands at the same
real coordinate in the frame the sender is currently using.

### High-altitude interaction

Place/break/raycast/pick-block/sign/command-block/vehicle packets all carry absolute block or
entity positions; each is shifted at the same single choke point and nothing else. Because Geyser
builds those Java packets from its own cache of the (already windowed) client state, and the
server then applies the (re-shifted) coordinates to the real world, a placement at client Y=505
during offset 1440 lands at real Y=1945 — exact integer arithmetic, verified round-trip in tests.

### Chunk translation (the part that used to corrupt chunks)

The Java `LevelChunkWithLight` payload is a flat sequence of `sectionCount` sections in
ascending-Y order — **no per-section indices in the payload itself**; the position of a section in
the array *is* its index, anchored at the real dimension's minY. Geyser re-homes each slot into
the Bedrock client's chunk-section grid using that same anchor.

SkyWindow does not patch bytes: it **re-homes the array**. Moving each section to
`slot = old − O/16` (with empty air sections synthesized for the vacated slots — an 8-byte
canonical singleton-palette encoding) makes *every* downstream consumer see content at exactly
`realY − O`: bedrock subchunk indices, the chunk cache, the block-entity lookup
(`javaChunks[(y>>4) − minSection]`), Geyser's waterlogged re-encoding, heightmap-free rendering.
Palettes, bit storages and counts are preserved as opaque byte ranges — no decoding, no
re-encoding, no scanning for "index-like bytes" (the fatal old trick). The walk is **tolerant**:
a payload that is short, long or corrupt mid-way is *normalized* to exactly the section count
Geyser's translator will loop over (air padding, content up to the anomaly preserved byte-exact)
and flagged in `/skywindow stats` — never passed through half-walked, because a truncated payload
would make Geyser's own decoder read past the section array into the light data. A visual hole is
acceptable; a decode exception on a packet thread is not.

## Why rubber-banding used to happen, and what specifically fixes it

The audit (requirement 4 — "identify the true responsible party") found the corrective-movement
engine on Geyser-Spigot is **inside Geyser itself, and it is legitimate**:

- `CollisionManager` (Geyser core) corrects player movement by sampling the **real server world**
  *at the coordinates the client is currently using* — because with Geyser's Spigot/ViaPlatform
  bridges, "the world" and "the window" are the same thing to it. On the legacy Geyser+plugin
  architecture the window coordinates (e.g. `447`) were being fed into real-world reads (`447` is
  open sky in the real dimension, while `1887` at the client's frame is solid) → Geyser concluded
  the client was inside solid blocks / floating / clipping → it sent corrections → **the
  rubber-band you saw was Geyser doing collision validation on mixed frames**. No configuration
  toggle exists to reroute those reads; on the Spigot platform there is no block cache to point
  them at.
- `BlockInventoryHolder` likewise re-reads the real block under an open container to detect
  "someone changed the block under you", and *reset* inventories the same way.
- Everything else on the correction list (break progress, client-side prediction, creative
  placement rollback) is cache/wire-driven and was never the culprit — verified by reading each
  implementation.

SkyWindow fixes this at the root instead of masking it: the extension **wraps the platform
`WorldManager` in a session-aware decorator** that adds the player's current offset to every
coordinate before reading the real world. Collision sampling, container-block checks, decorated
pot reads — all of them now resolve to the same real block the windowed client is looking at.
`CollisionManager`'s correction path then *never disagrees with anything*, so no corrections
occur. There is nothing to mask, and when the wrapper cannot be installed (e.g. a future Geyser
that reorganizes the bootstrap), `/skywindow doctor` reports it instead of letting you discover it as
rubber-banding.

The second root cause class — mixed frames *between* layers — is structurally excluded: exactly
one component (the per-session pipeline handler) knows offsets exist, and it sits below every
consumer that could double-translate. Geyser's translators receive a self-consistent
window-space world and produce a self-consistent Bedrock view; the server receives a
self-consistent real-space view. The "no triple translating" requirement is satisfied by
construction, not by convention.

## Architecture

```
                     Java server (Paper etc.)                 ← REAL coordinates only;
        ▲ │                                                  ← SkyWindow never touches it or
        │ ▼     netty pipeline inside Geyser:                ← requires any plugin there
   ┌───────────┐   [codec] → [skywindow] → [manager]           ▲
   │ mcprotocollib │        (this handler: the ONLY place      │
   └───────────┘         absolute Y is translated, both       │
        ▲        │        directions, from the session's      │
        │        ▼        current offset — and the snapshot   │
   ┌─────────────────┐     used by the world-manager reads)   │
   │  Geyser core    │                                        │
   │  translators    │──► Bedrock packets (window space,     │
   │                 │    never rewritten by SkyWindow)            │
   └─────────────────┘                                        │
        ▲        │                                            │
        │        ▼                                            │
   ┌───────────┐                                  ┌──────────┴─────────┐
   │ Bedrock   │ ◄───── RakNet / UDP ─────────────│  Bedrock client     │
   │  client   │                                   │  sees [-512..512]   │
   └───────────┘                                   └─────────────────────┘

   Side channel: GeyserImpl bootstrap's WorldManager is wrapped once, at startup:
   session-aware reads (collision manager, inventory holder, pots) add the caller
   session's offset before touching the real world — the rubber-band fix.
```

| Class (package `fr.buildtheearth.skywindow`) | Responsibility |
|---|---|
| `SkyWindowExtension` | Lifecycle glue: subscribes to Geyser events, owns `SkyWindowCore`, registers `/skywindow` (aliases `/sw`, `/bho`). |
| `SkyWindowCore` | Config load, per-session state registry, pipeline install (with bounded retry), `WorldManager` wrap (single guarded reflection point). |
| `SkyWindowConfig` | Flat properties config (`config.properties` in the extension folder). |
| `pipeline/SkyWindowHandler` | The choke point: inbound/outbound translation, position monitor, chunk cache, atomic switch, freeze, watch ring. |
| `window/WindowRules` | Pure offset/switch math. Also the single mirror target of the Python oracle. |
| `session/SkyWindowSession` | All per-player mutable state + byte-bounded original-chunk cache. Nothing global. |
| `translate/InboundYTransforms`, `OutboundYTransforms` | Per-packet-type policies (see [docs/PACKET-MATRIX.md](docs/PACKET-MATRIX.md)). |
| `translate/WindowedChunks` | Chunk payload section re-homing + block-entity list shift/prune. |
| `chunk/SectionCodec` | Byte-range walker for the Java chunk section format (fail-closed on garbage). |
| `translate/CommandYRewrite` | Absolute-Y rewrite for unsigned `/tp`-class commands (allowlisted; signature-safe). |
| `world/ShiftedWorldManager` | Session-aware `+O` decorator over the platform world manager. |
| `command/SkyWindowCommand` | `/skywindow info|watch|recent|doctor|stats`. |

**Why a Geyser extension and not a Bukkit+ProtocolLib plugin** (the obvious alternative): on
Geyser-Spigot, a Bukkit-plugin cannot reach the root cause at all — the collision/inventory world
reads live in Geyser core with no injection seam — and packet-level plugin work must reconstruct
Geyser's own per-session semantics (dimension negotiation, cache formats) from the outside, which
is exactly the fragile duplication the audit condemned. The extension compiles against Geyser's
own classes (shaded mcprotocollib included, parent-first loading, never bundled) and sits where
the coordinate systems legitimately meet. Deep integration is confined to **two identifiable,
version-sensitive seams**: the pipeline insertion point and the world-manager field; both are probed
at startup and reported by `/skywindow doctor`, and both degrade to "SkyWindow off for the affected feature"
rather than corrupting.

## Installation

1. You need **Geyser** (Spigot/Paper via `Geyser-Spigot`, or Standalone, with any
   bridge — Floodgate optional) that supports **extended build height** (Geyser ≥ the 1.21-era
   "overworld height" work; this repo is built and CI-verified against Geyser
   `2.11.2-SNAPSHOT`, see [Version compatibility](#version-compatibility)).
2. Get the jar: either a tagged [release](../../releases) (every `vX.Y.Z` tag publishes the jar plus a
   sha256 file on GitHub Releases), or build it yourself: `mvn package` (needs JDK 21; resolves the Geyser
   `api` + `core` artifacts and JUnit from the OpenGeyser repository, and enforces that no
   Bukkit/Spigot/Paper/ProtocolLib dependency ever sneaks in).
3. Put `skywindow-1.0.0.jar` into the **extensions** folder:
   `plugins/Geyser-Spigot/extensions/` (Spigot/Paper) or `extensions/` next to the standalone jar.
4. Start the server. A default `config.properties` is written next to the extension. **No Java-side
   plugin is required or wanted**; installing the old 3.x plugin alongside will corrupt chunks.

To check: `/skywindow doctor` from a Bedrock client (and see below).

## Configuration

`plugins/Geyser-Spigot/extensions/skywindow/config.properties`:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch. With `false` the extension loads and does nothing. |
| `switch-margin-blocks` | `64` | Distance from the window edge that triggers a re-centering switch (min 32). Lower = fewer switches, more risk of overshooting between checks. |
| `switch-cooldown-ms` | `2000` | Minimum gap between switches. |
| `freeze-ms` | `400` | How long position-bearing outbound packets are held during a switch. |
| `freeze-hold-actions` | `true` | Queue & replay dig/place packets sent during the freeze instead of dropping them (overflow still drops + ghost-reverts). |
| `freeze-hold-movement` | `true` | Movement sent during the freeze is held and replayed in the frame it was sent in, instead of dropped. Keep on for elytra/boat/fall continuity across switches. |
| `announce-switches` | `false` | Send the player a chat line when their window is re-homed. Off by default - switches are meant to be invisible. |
| `chunk-cache-max-chunks` / `chunk-cache-max-megabytes` | `2048` / `96` | Per-player original-chunk cache bounds (LRU). This is what makes switches seamless; smaller means possible terrain holes right after a switch (self-healing on the next natural chunk send). |
| `max-offset-blocks` | `0` (auto) | Hard cap on the offset. 0 derives it from the dimension (recommended); set only to shrink reachability, e.g. on a server whose build limit exceeds your testing confidence. |
| `rewrite-commands` | `tp,tppos,teleport` | Unsigned chat commands whose absolute Y is rewritten. Empty = off. |
| `log-switches` | `true` | One info-log line per switch (they are rare). |

There is deliberately no "window size" / "max height" knob: those were footguns in 3.x. The window
is the client's negotiated dimension, the cap is derived from the world.

Invalid values never crash the server: each one falls back to its default with a logged warning, and
unknown keys are reported too - a typo like `freeze-mss` will not silently do nothing forever.

## Commands and debugging

Bedrock-side, per player (development diagnostics are real, not simulated):

- `/skywindow` or `/skywindow info` — active offset, attached/switching state, client window vs real dimension,
  **your real Y** vs what the client shows, cached chunks count.
- `/skywindow watch on|off` + `/skywindow recent` — a rolling ring of every translated packet with before/after
  and the active offset (`IN MoveVehicle y-1440 (offset 1440)` …). Off by default; when off the
  recording is a single boolean check — production stays silent.
- `/skywindow doctor` — the four preflight facts: extension enabled, world-manager wrapper installed
  (the anti-rubber-band fix), pipeline handler attached to your session, dimension bounds read +
  whether windowing is engaged and why, the malformed-chunk passthrough counter, the last freeze
  duration and current switch backoff, any packet types quarantined after a deterministic transform
  failure, and the exact Geyser build this audit was run against. Run this after any Geyser update.
- `/skywindow stats [reset]` — per-session counters (since login, or since the last reset): packets
  in/out translated, chunks windowed/replayed, switches, actions held + queue overflows, drops while
  frozen, chunk anomalies, and the last/total freeze duration in microseconds.
- `/skywindow window <realY>` — force the *next* switch to home the window at a given real Y (QA tool;
  it goes through the normal switch machinery on the session event loop - freeze, replay, backoff and
  the derived cap all apply exactly as during automatic switches).

If a Bedrock player reports rubber-banding at altitude, in order: `/skywindow doctor` (world manager +
attached), `/skywindow watch on` while reproducing, `/skywindow recent` output. All numbers on screen come from
the same state the pipeline uses, so a "looks right but broken" state means the pipeline itself is
right and the report belongs upstream.

## Verification and tests

Correctness is defined behaviorally ("a Bedrock player stays in sync at altitude"), which no local
tool can fully prove — so here is exactly what *was* verified and where the residual risk lives:

- **Pure algorithm suite, executed here (this environment has no JVM):**
  `dev/verify/oracle.py` — the window math (reachability, alignment, clamp, exact integer
  round-trips over the full -64..1952+ range), the switch cadence (≤6 switches floor→roof, recenter
  or max-pin assertions, 20000-tick hover with ≤1 switch), the fit→never-switch property across
  vanilla/nether/end/legacy configurations, the chunk **reslice semantics on real serialized
  sections** (300 randomized payloads × 6 checks: content moved byte-identical, vacated slots
  canonical-empty, out-of-window dropped, walk exactly covers output), and malformed-input
  rejection (every truncation point raises, never mis-slices). All properties pass.
- **Java test suite (runs in CI with the real dependencies):** `mvn test` — mirrors of the same
  properties (`WindowRulesTest` incl. the Y = -64/0/319/320/510/1000/1500/1952 matrix, negative
  floors, origin transitions via hover/climb sims; `SectionCodecTest` incl. trailing-byte and
  storage-overflow rejection; `WindowedChunksTest` incl. the block-entity prune boundary that
  protects Geyser's own section array; `CommandYRewriteTest` incl. round-trip vs the packet-layer
  shift; `SkyWindowSessionTest` for config/no-op semantics).
- **API-surface verification:** every mcprotocollib/Geyser/core type and member this extension
  compiles against was checked against the authoritative upstream sources (Lombok `@Data`/`@With`
  shapes included), and every `org.geysermc.*` import is resolved mechanically against the clones.
  Compilation itself happens in CI against the pinned Geyser build.
- **Property-style Java tests (CI):** `TransformPropertyTest` runs randomized coordinates through the
  transform tables asserting the shift is exactly +O outbound / −O inbound with every other field
  preserved (relative deltas, cursors, faces, sequences) — the same properties `oracle.py` mirrors in
  Python, now also against the real 1.21 packet objects; `SectionCodecFuzzTest` throws 500 random
  payloads plus truncated-structured ones at the chunk slicer and asserts it never throws, always
  tiles its output exactly, and preserves parsed sections byte-identical; `HeldQueueTest` checks the
  freeze queue under a two-producer/two-consumer 100k-entry race for losslessness and order;
  `SkyWindowConfigTest` pins clamping, warning collection, and the sample-file/defaults round-trip.
- **What still requires a live server (documented, not claimed):** an actual Bedrock client's
  behavior during the 400 ms freeze, end-to-end piston/redstone/fluid interaction at
  client Y≈460, reconnect/respawn transitions against a specific server build. Run the checklist in
  [docs/VERIFICATION.md](docs/VERIFICATION.md) on a staging server before pointing BuildTheEarth at
  a new version. This repo does not claim field-verified status for anything beyond the above.

## Known limitations (honest list)

- **Fundamental:** nothing can make the Bedrock client *display* outside its negotiated dimension;
  at the very top of the world the window is pinned (offset = `Omax`), so content *above* the
  world's build height is simply where the server puts it (air) — and a player flying 1–2 blocks
  past `Omax + clientMaxY` sees the position clamp until they come back. There is no known
  server-side fix and SkyWindow does not pretend otherwise.
- **Nether/End/custom dims mapped to a ≤256-height dimension:** automatically passive (windowing is
  off when content fits) — a Nether *taller than 256 real blocks* is out of reach of any
  server-side method; the client's window itself is the hard ceiling (256). No windowing, no
  switching there.
- **Switch flash:** during a window switch there is a sub-second interval where a fast climber's
  screen may show terrain mid-replacement or a moment of fall before the snap lands (the terrain
  is replayed before the snap, but the client's own motion keeps running). Cooldown + margin make
  this rare (~once per 450 climbed blocks); it is *cosmetic* by the invariant — positions stay
  consistent. Old SkyWindow's equivalent moment was the rubber-band; this one leaves no correction.
- **Commands with absolute coordinates are only rewritten for the configured `tp`-family**, only
  unsigned, and only the first position triple. `/setblock`, `/fill`, `/clone`, `/place` etc.
  interpret the numbers the client sends literally: use relative coordinates (`~ ~ ~`), or
  `execute positioned`, or run them from the server console / a Java client. (This is a
  documentation-grade limitation of *any* wire-level Y offset scheme — the server has no way to
  know a player's view frame.)
- **Piston animation and block-place sounds** on the Spigot platform are emitted by Geyser's own
  platform listeners with *real* coordinates; for windowed players that can make a piston at the
  world floor briefly ghost into a high player's view (or a place-sound not be heard at the top).
  Purely cosmetic — no sync impact (state and collision come from the translated stream). A proper
  fix belongs in Geyser core (upstream issue-worthy); SkyWindow deliberately does not **not** rewrite Bedrock
  packets to patch it, for the same reason it doesn't patch them at all.
- **Item frames/banners on windowed blocks**: their *block entity* interactions resolve through the
  wrapped world manager, so state is coherent; but some Geyser entity paths re-derive bedrock-space
  positions from cached client state only — those are consistent within the window and were
  exercised in code review; on a future Geyser that changes that derivation, `/skywindow doctor` +
  staging retest is the contract.
- **Bedrock's block-reach / camera quirks near ±512 client Y** (rendering clipping at the very
  edges of the dimension, not SkyWindow-induced) exist on extended-height vanilla Geyser too; the margin
  configuration exists precisely so players are centered away from the edges.
- **Very tall dimensions (>~4000 sections) make every chunk payload huge** (126 sections is fine;
  300000 is not). That ceiling is Geyser's own chunk format cost, and SkyWindow inherits it; SkyWindow's cache
  is byte-capped so it never explodes, but such servers are impractical *for Geyser itself*, not
  for SkyWindow specifically.
- **Signed chat commands** (`enforce-secure-profile`) can't be rewritten (signatures). SkyWindow sends
  them untouched; if an OP needs windowed absolute coords there, use console.
- **`/skywindow window <realY>` is deliberately the only forcing tool**: it re-homes the next switch
  through the *normal* switch machinery (event loop, backoff, freeze, chunk replay) rather than
  assigning an offset behind the pipeline's back — the 3.x habit of setting offsets asynchronously
  was a bug source and stays gone. It cannot bypass the derived cap either: the target is clamped
  exactly like every automatic switch.

## Performance

- Idle cost on ordinary servers (windowing not needed): the handler does one `instanceof`
  check + one volatile read per packet, allocates nothing, caches nothing; the world-manager
  decorator adds one map lookup (identity `GeyserSession → state`) that returns 0 and is skipped.
- Active-window cost is *copy-then-forward* only for packets that carry absolute Y (a small minority
  of the stream — movement, block updates, chunk data). Every transform is
  `packet.withField(value)` (Lombok copy-on-write on immutable packets): no mutation shared objects
  could race, and untouched packets are forwarded by reference.
- Chunk translation walks the payload's section boundaries once (pointer arithmetic, no palette
  decoding) and rebuilds one `byte[]` — Geyser would decode those palettes anyway; we add zero
  palette work.
- Memory: bounded per player (default: 2048 chunks ≤ 96 MB of originals, LRU) plus a handful of
  objects per session. No global caches.
- Switches: replay cost is bounded by cache size and is nearest-first; a 20-chunk-view replay is
  on the order of a few hundred KB on the event loop, ~ms of CPU. Rare (centred-by-design).
- The critical path (per packet) has no logging, no streams, no regexes, no allocations in the
  pass-through case; watch ring work is one volatile check when disabled.

## Version compatibility

- Built and CI-tested (Maven `verify`: compile + full JUnit suite incl. fuzz/property tests, GitHub Actions) against
  **Geyser `2.11.2-SNAPSHOT`** via the published `api` + `core` artifacts; their compile-scope
  transitives provide exactly the mcprotocollib / cloudburst / netty classes the runtime has —
  that precise combination is what CI verifies (see `.github/workflows/build.yml`).
- Runtime requirement is "the Geyser your server runs", because the extension resolves core classes
  parent-first from it; a Geyser update can move the two seam points (pipeline handler names, world
  manager field) — `/skywindow doctor` tells you immediately, and the failure mode of each seam is
  off-for-you, not corruption.
- **No untested compatibility claims are made.** The old README listed versions nobody verified;
  this one lists the one CI verifies and states how to check yours. MC/protocol specifics that this
  design *does* care about: Java protocol ≥ 1.18 chunk format (yes for anything relevant),
  teleport packet shape per collib's pinned version (the pinned Geyser build defines it; that's why
  the pin is explicit in the pom and overridable via `-Dgeyser.version`).

## Risks

- **SkyWindow on a server where it should not run:** on any server whose dimensions fit the client, SkyWindow
  is a verified no-op — but still: it *is* packet surgery. Staging first.
- **Geyser updates:** the two seams are checked at startup per-session and reported by `/skywindow
  doctor`; unattached/degraded is fail-safe (players simply experience vanilla Geyser behavior —
  out-of-window content is clipped by Geyser as today).
- **Don't combine** with the old 3.x plugin, or any plugin that rewrites coordinates/heightmaps
  for Bedrock (e.g. height-extender clones): double translation is exactly what the previous rewrite died of.
- Rollback = delete the jar from `extensions/`; nothing about the world or players changes (no
  persisted state exists — deliberate).

---

*Docs deep-dives: [ARCHITECTURE.md](docs/ARCHITECTURE.md) (design decisions, packet chain, threads)
· [PACKET-MATRIX.md](docs/PACKET-MATRIX.md) (per-packet policy table)
· [VERIFICATION.md](docs/VERIFICATION.md) (staging checklist to run with real clients).*
