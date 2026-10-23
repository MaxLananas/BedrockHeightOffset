# Contributing

Altus changes how Minecraft coordinates reach a production bridge. The bar for a change is that it is
provably inert when windowing is not needed, and provably correct when it is. Read
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/PACKET-MATRIX.md](docs/PACKET-MATRIX.md) first.

## Environment

- JDK 21 and Maven 3.9+. Python 3 for the oracle (no dependencies).
- `python3 dev/verify/oracle.py 5` and `mvn -B verify` must both pass. CI runs them on every push.
- Maven resolves Geyser `api` and `core` from the OpenGeyser repository (`geyser.version` in `pom.xml`). A change
  that needs a newer Geyser API bumps the pin in the same pull request and re-runs the packet-matrix audit.

## Ground rules

1. **No new runtime dependencies.** Geyser's classloader provides netty, mcprotocollib and cloudburst math. The
   enforcer bans Bukkit, Spigot, Paper, ProtocolLib and Geyser-Spigot.
2. **Configuration keys need three things:** a default in `AltusConfig`, an entry in `sampleFileContents()` equal to
   the default (a test checks this), and a row in the README table. Numeric keys get a clamp range.
3. **Threading.** Per-session state is written on the channel event loop. Anything the outbound path reads is
   `volatile`. Freeze transitions and outbound writes run under `freezeLock`; do not add a path that changes
   `offset`, `frozen` or the held queue outside it. Document any new cross-thread field in the class Javadoc and in
   `docs/ARCHITECTURE.md`.
4. **Never throw from a packet path.** A transform failure quarantines its packet type and fails closed while the
   window is active. A new path that can throw on packet input needs a fuzz or property test (see
   `SectionCodecFuzzTest`).
5. **Classify, do not default.** A new outbound packet with an absolute Y needs a row in
   `OutboundYTransforms.frozenDisposition` and in `docs/PACKET-MATRIX.md`. A new inbound one needs a row in
   `InboundYTransforms`. Relative quantities are never shifted.
6. **Tests are behavioural.** A transform change lands with the `TransformPropertyTest` or `PositionCoverageTest`
   update and the `dev/verify/oracle.py` mirror in the same commit. Do not delete or weaken an assertion to make a
   change pass; if a test is wrong, say why in the commit message.
7. **Comments** describe invariants, protocol constraints, concurrency contracts and non-obvious traps. Do not
   restate code, and do not record how a change was produced.

## Submitting

- Branch from `main`. Keep commits small and explain the behaviour change in the message.
- User-visible changes update the README (install, configuration, limitations), the CHANGELOG `[Unreleased]`
  section, and any affected document in `docs/`.
- The "Known limitations" section in the README is part of the contract. Do not remove an item without evidence.
- The build workflow is the merge gate. Do not merge with a red run unless the pull request explains why.

## Releases

- Version bumps in `pom.xml` and the matching `## [X.Y.Z]` changelog section are merged through a normal pull
  request.
- Tag only from `main`, with an annotated tag `vX.Y.Z`: `git tag -a vX.Y.Z -m "Altus X.Y.Z"` then `git push origin vX.Y.Z`.
- The release workflow checks that the tag matches `pom.xml`, that the changelog section exists, and that the commit
  is on `main`. It publishes the jar, `SHA256SUMS.txt` and `build-info.txt`.
- Releases are immutable. Re-running the workflow for the same tag is a no-op if the checksums match, and a failure
  otherwise. Do not delete and recreate a release to change its files; publish a new version.
- Do not create extra tags, for example archive tags, unless a release needs one: the release workflow reacts to
  every `v*` tag.

## Review checklist

- [ ] `oracle.py` and `mvn verify` pass locally or in CI.
- [ ] New packet types are classified in the code, in the matrix, and in the tests.
- [ ] Nothing is shifted that is relative.
- [ ] Failure paths fail closed and are visible in `/altus doctor` or the logs.
- [ ] Cross-thread state is listed in the class Javadoc and in `docs/ARCHITECTURE.md`.
- [ ] README, CHANGELOG and `docs/VERIFICATION.md` reflect the change.
