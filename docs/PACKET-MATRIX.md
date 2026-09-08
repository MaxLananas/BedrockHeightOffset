# Packet matrix

Every Java-protocol packet type that crosses the SkyWindow choke point (`SkyWindowHandler`, positioned
after mcprotocollib's `codec`), with its per-direction policy. **A** = absolute Y shifted by ±O,
**S** = section index shifted (A applied inside re-slicing), **R** = relative quantity — *must never
be touched*, **—** = passes through untouched by design.

This file is the contract between `translate/InboundYTransforms`, `translate/OutboundYTransforms`
and `translate/WindowedChunks`. A new packet type appearing in an update is a *reviewed decision*,
not an accident: the matrix is re-derived per Geyser pin by grepping Geyser translators for
position consumers (see docs/ARCHITECTURE.md "Seam upgrades").

## Clientbound (Java server → Geyser): shift by −O

| Packet | What is shifted | Notes |
|---|---|---|
| `LevelChunkWithLight` | S: payload section array (out[w]=in[w+Δ]); block-entity `y` −O with pruning outside the real-section array | Heightmaps & light data: **pass through** — Geyser master consumes neither (verified). |
| `SectionBlocksUpdate` | `chunkY` section index −Δs; each `BlockChangeEntry` absolute pos −O | collib stores entries as global positions, both are shifted consistently. |
| `BlockUpdate` | `BlockChangeEntry` pos −O | |
| `AddEntity` | `y` (double) −O | `movement` is R; `data` is verified to carry no packed coords in current protocol (FallingBlockData stores the block state id only). |
| `TeleportEntity` | `position` −O | `deltaMovement` is R. relatives list semantics: absolute positions per vanilla server usage; monitor records the packet for the switch-snap reuse. |
| `EntityPositionSync` | `position` −O | 1.21.2+ path; `deltaMovement` R. |
| `MoveVehicle` | `position` −O | |
| `MoveMinecart` | each lerp-step `position` −O | `movement` in the step is R. |
| `BlockEvent` | pos −O | note blocks. |
| `BlockDestruction` | pos −O | break progress render. |
| `BlockEntityData` | pos −O | NBT contents (sign lines etc.) are coordinate-free. |
| `LevelEvent` | pos (Vector3i) −O | |
| `LevelParticles` | `y` −O | offsets & velocity are R. |
| `Sound` (category-positions) | `y` −O | |
| `Explode` | `center` −O | `playerKnockback` R; block-particle list carries no coords in current collib shape. |
| `SetDefaultSpawnPosition` | `globalPos.position` −O | respawn target / compass. |
| `Login` | — (side-effect: capture `entityId` as player java id; cache/window refresh) | no position to shift in current shape. |
| `Respawn` | — (side-effect: clear chunk cache, recompute window) | dimension change handled by re-send semantics of the server itself. |
| `ForgetLevelChunk` | — (side-effect: cache eviction) | x/z only, no Y. |
| Everything else (`MoveEntity*`, `SetEntityMotion`, `SetHealth`, `SetPassengers`, damage, rotations, inventories, containers, chat, scoreboard, teams, boss bars, border, dimension sync, …) | — | no absolute Y, or handled upstream by their nature. `MoveEntityPos/Rot` are *relative by protocol* — shifting them is the classic desync bug; they must stay untouched. |

## Serverbound (Geyser → Java server): shift by +O

| Packet | What is shifted | Notes |
|---|---|---|
| `MovePlayerPos`, `MovePlayerPosRot` | `y` +O | Also the position monitor for switch evaluation (realY = y+O, no extra tracking state). |
| `PlayerAction` (all dig/place/start/abort states) | pos +O | During a switch: held (freeze) and replayed on unfreeze translated for the sender's frame; overflow (16) drops, and dropped destroy positions get an authoritative block re-send. |
| `UseItemOn` | pos +O | Held across switches like `PlayerAction`. `cursorX/Y/Z` are R (inside-block offsets); `face` is block-relative. |
| `MoveVehicle` | position +O | |
| `PickItemFromBlock` | pos +O | |
| `SignUpdate` | pos +O | |
| `SetCommandBlock` | pos +O | OP-tool edit at altitude. |
| `SetCommandMinecart` | — | entity-targeted, position already fixed at spawn. |
| `SetStructureBlock` | pos +O | `offset`/`size` are R. |
| `SetJigsawBlock` | pos +O | |
| `ChatCommandSigned` | command string: first position triple's Y +O — **only** for allowlisted commands **and** when `signatures` is empty | Signed commands are never modified (would invalidate the signature). |
| `AcceptTeleport` | — | id-only; the id we snap with reuses the server's last teleport so acks line up with Geyser's own state machine. |
| `ContainerClick`, `SetCarriedItem`, `Swing`, `PlayerCommand`, `Input`, `Interact`, `Attack`, `Spectate`, `CloseContainer`, … | — | no absolute Y (entity-relative targets are resolved by id on the server). |

## Why the Bedrock side is not touched at all

Old SkyWindow rewrote `UpdateBlock`/`SetEntityData`/`MoveActor…` on the RakNet wire. Two facts killed
that (see audit, docs/ARCHITECTURE.md):

1. Geyser's own cache/translation already produces *window-consistent* Bedrock packets once the
   Java packets are shifted at the choke point — rewriting them again is double translation by
   construction, and the 3.x "blocks vanish" behaviour is exactly that.
2. The Bedrock packet objects/serializers are not a stable API surface for an extension to own.

The only remaining real-space noise that reaches the Bedrock wire comes from *platform listeners*
in the Geyser bootstrap (place-sound, piston BE cosmetics). Those are documented in README as
cosmetic and left alone rather than patched mid-flight.
