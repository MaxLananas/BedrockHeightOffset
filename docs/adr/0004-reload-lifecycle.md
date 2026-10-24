# 4. Reload runs on the post-reload event only

Date: 2026-10-23 — Status: accepted

## Context

Geyser reloads its own bootstrap when asked to (`/geyser reload`). Two events surround that work, and
their order was checked in the Geyser sources (`GeyserImpl`, pinned snapshot in `pom.xml`):

1. `GeyserPreReloadEvent` fires **before** Geyser calls `bootstrap.onGeyserDisable()` and then
   `bootstrap.onGeyserEnable()`.
2. `GeyserPostReloadEvent` fires **after** both have returned.

Hauteur wraps the world manager, attaches to player channels and schedules its sweeper. Geyser's enable and
disable cycle can replace or unwrap those objects. Work done in the pre-reload event is therefore undone by
the very cycle it precedes, and an attach performed there can point at a world manager that is about to be
replaced.

## Decision

- `HauteurExtension` subscribes to `GeyserPostReloadEvent` only. The pre-reload handler was removed.
- `core.reload()` re-reads `config.properties`, rebuilds the derived command configuration and re-applies the
  world-manager wrap and per-player attach. It is idempotent: running it when nothing changed is a no-op for
  each player that is already attached.
- Configuration changes that only affect later packets (margins, timeouts, rewrite commands) apply on the next
  packet. Nothing requires a reconnect.

## Consequences

- A reload can take effect a moment later than the pre-reload event would suggest. That is the price of not
  racing Geyser's own bootstrap.
- A reload that changes `enabled` from `true` to `false` still returns players to real space through the
  post-reload path. The manual procedure in `docs/VERIFICATION.md` describes this check, but it has not been run
  on a live server yet. The `HauteurCore` install and reload paths have no automated test, because they need a
  live Geyser.
- If Geyser ever reorders these events, this decision and the `onPostReload` Javadoc must be revisited together.
