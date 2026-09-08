# Contributing

Short version: this extension moves pixels and blocks around a *client limitation* on a production
bridge, so the bar for changes is "provably inert when not needed, provably correct when needed".
The review checklist at the bottom is not optional.

## Environment

- JDK 21, Maven 3.9+. That's all: `mvn test` runs everything, `mvn package` builds the extension
  jar (shaded only into `target/`; the jar is self-contained because Geyser provides every runtime
  dependency parent-first).
- CI compiles against the pinned `geyser.version` (`2.11.2-SNAPSHOT`) from the OpenGeyser
  repository. If your change needs a newer Geyser API, bump the pin in the same PR and re-run the
  packet-matrix audit (below).

## Ground rules

1. **No new runtime dependencies.** The enforcer bans Bukkit/Spigot/Paper/ProtocolLib/Geyser-Spigot
   outright; do not add anything else either - Geyser's classloader already provides netty,
   mcprotocollib, cloudburst math. If you think you need a dependency, the design is wrong.
2. **No new config key without all three**: default in `SkyWindowConfig`, entry in
   `sampleFileContents()` (value = default; a test enforces the round-trip), row in the README
   config table. Numeric keys get a clamp range, and clamping is silent - validation warnings are
   only for *bad input*.
3. **Threading contract**: per-session state is event-loop confined except the documented volatiles
   and the two cross-thread structures (`HeldQueue`, ghost-revert set). A new field on
   `SkyWindowSession` is event-loop-only unless you write down who else touches it, in the class
   javadoc, and in `docs/ARCHITECTURE.md`.
4. **Never throw from a packet path.** Translation failures quarantine the type and pass the packet
   through; chunk parse failures degrade to air. If a new code path can throw on packet input, it
   needs a fuzz test (see `SectionCodecFuzzTest` for the pattern: random + truncated inputs,
   assert-the-invariant, not assert-the-bytes).
5. Tests are behavioral: `oracle.py` (Python, dependency-free, mirrors the math and the codec
   contract - runs on any machine), then JUnit (real packet objects). A transform change must land
   with both sides updated, because CI only runs what the matrix says exists.

## The packet-matrix audit (required on every Geyser/protocol bump, and when adding packet types)

1. `grep -r "position\|\.getY()\|Vector3i" <Geyser checkout>/Core/src/main/java/org/geysermc/geyser/translator/`
   (both directions: `protocol/java/*` and `packet/protocol/play`) against the mcprotocollib packet
   list for the pinned protocol version.
2. For each position-carrying packet not already in `docs/PACKET-MATRIX.md`: classify A (shift),
   R (relative - must not touch), or — (document why: usually "no absolute Y in this shape"; the
   minecart-command row is the model - cite the exact field list you verified).
3. Extend `InboundYTransforms`/`OutboundYTransforms` + `TransformPropertyTest` matrix + `oracle.py`
   mirror in one commit. Update the audit stamp at the top of the matrix doc.

## Submitting

- Branch from `main`, small commits, `mvn test` green locally; CI is the merge gate (no force-push
  past a red run without explanation in the PR).
- User-visible behavior changes: update README (install/config/limitations) + CHANGELOG in the same
  PR. The "Known limitations" list is maintained like an API surface - it is what makes claims here
  trustworthy.
- Release PRs (version bump in `pom.xml`): the annotated tag `vX.Y.Z` is pushed *after* merge and
  the release workflow publishes jar + sha256; never tag from a PR branch.
