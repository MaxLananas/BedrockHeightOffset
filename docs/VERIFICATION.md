# Staging verification checklist

Run on a staging server before a public deployment. Each step names the exact expected observation;
if reality differs, capture `/skywindow watch on` + `/skywindow recent` output and `/skywindow doctor` before filing.

## Setup

1. Paper/Spigot 1.21.x with **extended overworld height enabled** (BTE-style: `min-y: -64`,
   `height: 2016` or a test value ≥ 1100 so windows actually engage).
2. Geyser-Spigot (pinned version this repo CI builds against), Floodgate optional.
3. SkyWindow extension jar in `plugins/Geyser-Spigot/extensions/` (or `extensions/` for standalone) — nothing else.
4. One Bedrock client (current release), one Java client for control comparison.

## Preflight

- [ ] Server log contains `[SkyWindow] world manager wrapped...` and the activation line.
- [ ] `/skywindow doctor`: **attached ✔**, **world manager ✔**, windowing line matches your dimension.
- [ ] `/skywindow info` near spawn: offset 0 (real space), cached chunks > 0.

## Passive-mode proof (do not skip — this is the zero-risk guarantee)

- [ ] Set dimension to a vanilla-height world (`height: 384`). `/skywindow info` → `windowing ...
      skipped`, stats counters **all zero** after full play. If any counter moves here, stop:
      the no-op property is violated.

## Core scenarios (dimension ≥ ~1100 tall; default config)

| # | Action | Expected |
|---|---|---|
| 1 | `/tp @s 0 600 0` (Bedrock chat) at low Y | No rubber-band; ground under feet present; `/skywindow info` offset ≈ snap600 = 608-ish → client shows Y≈0 |
| 2 | Place + break blocks at Y≈600 | Blocks stay. Rejoin: still there (server truth). |
| 3 | Climb ladder/elytra continuously up ~1000 blocks | At most 1–2 brief terrain re-settles (`/skywindow stats`: windowSwitches 1–2, chunkReplays ≈ view area); no corrective movement packets (no kick/teleport back); watch ring shows snap teleports only at switch points |
| 4 | Hover exactly at margin (fly up/down repeatedly around 460–500 client-Y) | ≤1 switch (cooldown + centering hysteresis) |
| 5 | Open chest/hopper/barrel/furnace at altitude; walk away and back | Containers open/close without being reset |
| 6 | Redstone: place repeater + dust at altitude, flip lever | Runs; hoppers move items; no rollback of powered blocks |
| 7 | Water/lava placement at altitude | Flows client-side as on Java control; updates converge |
| 8 | Piston extends/retracts under your feet at altitude | **No rubber-band** (the fix's whole point). Cosmetic piston ghosts in your view when pistons run at the world *floor* are the documented platform-listener limitation (README). |
| 9 | `/tp 0 1500 0` from Bedrock chat at offset 0 area | Lands at real 1500 (command rewrite). `/tp ~ ~64 ~` still relative ✓. A signed chat command (Java-style client) is untouched. |
| 10 | Die → respawn / `/kill` at altitude | Respawn at bed/surface without an out-of-window jump; re-login at altitude: chunks load, one snap on first movement, normal play |
| 11 | Ride a horse/boat/minecart at altitude | No desync when dismounting; vehicle stays |
| 12 | Change dimension (nether portal) and back | Nether passive (offset 0); return to overworld at altitude: chunks replay/snap, playable |
| 13 | Two Bedrock players, different heights (400 vs 1500) | Each sees own window; they see each other at coherent relative positions; both can `/tp` to each other (relative/`~`) |
| 14 | `/skywindow stats` after all the above | `malformed chunks = 0`; drops only during freeze windows |
| 15 | Restart server mid-climb, rejoin at altitude | Clean recovery, no ghosts (cache rebuilds at identity, then one switch) |
| 16 | `/skywindow stats reset`, then `/skywindow window <realY near ceiling>` | counters re-baseline (viewed deltas start at ~0); a forced switch lands through the normal protocol: terrain replay, snap, then movement resumes with **no** leftover offset in `info` (offset shown matches the forced frame); `doctor` shows the freeze µs and backoff |
| 17 | Elytra dive *through* a switch point with `freeze-hold-movement=true` | fall/velocity continues after the snap (held movement replayed in-frame); with the key set `false`, movement during the freeze is dropped instead - visible as a momentary stall. Either way: no rubber-band afterwards |
| 18 | Spam `/skywindow window` back and forth at the same edge | switches throttle: `doctor` shows backoff climbing (2,4,8) and switch interval widening; server tick unaffected; toggling stops cleanly when you stop |
| 19 | `announce-switches=true`: climb until a natural switch | exactly one chat line per switch, no spam during backed-off bursts |

## Negative / stress

- [ ] Set `max-offset-blocks: 320`; climb above reachability → expect Geyser's normal out-of-window
      clipping behavior above the cap (blocks invisible above), **no** corruption below.
- [ ] Huge view distance (32) with tiny chunk cache (`chunk-cache-max-chunks: 64`) → switches work
      but may leave holes around you until the next natural chunk send — self-heals, no desync.
- [ ] Turn `enabled: false` → byte-identical behavior to no extension at all.
- [ ] Set `freeze-ms: 50` (aggressive short freeze) and do scenario 2+3: held-action path still
      must not leave ghost blocks (`stats`: `actionsHeld` moves, `heldOverflow` ideally 0, drops
      only on genuine overflow with authoritative re-send); a piston at the world *floor* below a
      windowed player may still briefly ghost in view - documented cosmetic, not a failure.

## Sign-off

Record: Geyser build, MC versions, client platform/version, this checklist with the `/skywindow doctor` +
`/skywindow stats` outputs. Only after this list passes on staging should a deployment be described as
"verified against" those versions — the README version policy applies to you too.
