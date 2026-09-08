# 1. Window the client's view; never spoof coordinates

Date: 2026-09-08 — Status: accepted

## Context

The Bedrock client hard-clamps positions and block rendering to its negotiated dimension bounds
(overworld with extended-height support: -512..512). A server whose world exceeds that (e.g.
BuildTheEarth at ~1952) has three candidate strategies:

a. **Spoof**: keep the client where it is and lie in the display layer about Y (old SkyWindow 3.x
   and its relatives, plus several "height extender" plugins).
b. **Reject**: clamp/deny movement outside 0..256 / -512..512 (upstream Geyser's position after
   rejecting coordinate spoofing in PR #5002 era; the status quo for unpatched servers).
c. **Window**: move a 1024-block *viewport* along the real axis per player. The client always
   displays real content at `realY − O`; the server and all Java players see pure real coordinates;
   every packet crossing the boundary is transformed at exactly one place, in exactly two
   directions.

## Decision

(c). One offset `O` per session, section-aligned, applied as −O inbound / +O outbound at the
upstream choke point, with re-homing switches when the player approaches the window edge.

## Consequences

- Server-side authority is never bent: anti-cheat, plugins, Java players, and persistence all see
  real coordinates; the transformation is purely a display-frame change, which is *why* it can be
  made provably inert for players outside the window concern.
- The offset must be *coherent state* (chunk data, entities, particles, sounds, acks all shift by
  the same O at the same instant) — hence the freeze protocol around switches; that is the entire
  complexity budget of this project.
- Relative quantities (delta movement, cursor offsets, entity rel-motion) must never be touched;
  the packet matrix documents the classification per type.
- Content beyond the window still needs a switch to become visible; we cannot beat the client's
  clamp, only move it — bounded, rare (≥32 blocks of margin, ~1 switch per 450 climbed), and
  announced option.
- (a) was rejected: display-layer lies desynchronize Geyser's own caches (collision, chunk builder,
  block-change replay) and are exactly what produced 3.x rubber-banding/vanishing blocks.
  (b) remains what unpatched servers do; SkyWindow is orthogonal to it.
