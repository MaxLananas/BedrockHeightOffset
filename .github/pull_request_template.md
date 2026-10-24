## What and why

<!-- The behaviour change, in one or two sentences. Link the issue if there is one. -->

## Checklist

- [ ] `python3 dev/verify/oracle.py 5` passes.
- [ ] `./mvnw -B verify` passes (CI is the reference run).
- [ ] New or changed packet handling is classified in `OutboundYTransforms` / `InboundYTransforms`, in
      `docs/PACKET-MATRIX.md`, and in the tests.
- [ ] No assertion was removed or weakened to make the change pass.
- [ ] Nothing shifts a relative quantity (motion, relative teleports, cursor offsets).
- [ ] New cross-thread state is `volatile` or guarded, and is documented in `docs/ARCHITECTURE.md`.
- [ ] README (install, configuration, limitations) and `CHANGELOG.md` `[Unreleased]` are updated when behaviour
      changes for users.
- [ ] Anything that was **not** run or verified on a real Bedrock client is stated below.

## Verification

<!-- Commands run and their results. Do not claim a result that was not produced. -->

## Not verified

<!-- Live-server, real-client, performance or platform checks that this change still needs. -->
