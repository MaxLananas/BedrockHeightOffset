# Packet matrix

Audit stamp: last re-derived against Geyser `2.11.2-SNAPSHOT` (Minecraft **1.21.10**, MCProtocolLib
`26.2`) on 2026-09-21, from the authoritative upstream sources (every packet class below was read,
not guessed). `SkyWindowCore.TESTED_AGAINST` carries the same string and is printed by `/skywindow doctor`,
so a running server can always be checked against the audit it was compiled with.

Every Java-protocol packet type that crosses the SkyWindow choke point (`SkyWindowHandler`, positioned
after mcprotocollib's `codec`), with its per-direction policy. **A** = absolute Y shifted by ±O,
**S** = section index shifted (A applied inside re-slicing), **R** = relative quantity — *must never
be touched*, **—** = passes through untouched by design.

This file is the contract between `translate/InboundYTransforms`, `translate/OutboundYTransforms`
and `translate/WindowedChunks`. A new packet type appearing in an update is a *reviewed decision*,
not an accident: the matrix is re-derived per Geyser pin by walking the MCProtocolLib packet classes
for `Vector3i`/`Vector3d`/`GlobalPos`/block-pos metadata fields and grepping Geyser translators for
position consumers.

**The relative-flags rule (this is the one that silently breaks first on protocol updates):**
`ClientboundPlayerPositionPacket` and `ClientboundTeleportEntityPacket` carry a `List<PositionElement>`
of relative flags. When `PositionElement.Y` is present, their `position.y` field is a **delta**, not a
coordinate (Geyser's `JavaPlayerPositionTranslator`/`JavaTeleportEntityTranslator` add the entity's
current position for any flagged axis). SkyWindow therefore shifts those packets' Y **only** when
`Y` is not flagged, and forwards flagged ones by reference. `deltaMovement` is *always* relative.

## Clientbound (Java server → Geyser): shift by −O

| Packet | What is shifted | Notes |
|---|---|---|
| `LevelChunkWithLight` | S: payload section array (out[w]=in[w+Δ]); block-entity `y` −O with pruning outside the real-section array | Heightmaps & light data: **pass through** — Geyser master consumes neither (verified @ 2.11.x); client-side lighting needs no Y knowledge beyond what Geyser derives from the translated blocks. |
| `SectionBlocksUpdate` | `chunkY` section index −Δs; each `BlockChangeEntry` absolute pos −O | collib stores entries as global positions, both are shifted consistently. |
| `BlockUpdate` | `BlockChangeEntry` pos −O | |
| `AddEntity` | `y` (double) −O | `movement` is R; `data` (`ObjectData`: falling block, projectile owner, minecart type, warden flag, generic int) verified coordinate-free. |
| `PlayerPosition` | `position.y` −O **only when `relatives` lacks `PositionElement.Y`** | The player-teleport family: login spawn, `/tp`, portals, ender pearls, BTE warps. `deltaMovement` is R; `id` is a teleport id (Geyser acks it downstream untouched). Also feeds the switch monitor. |
| `TeleportEntity` | `position.y` −O with the same relative-Y rule | `deltaMovement` R. relatives list semantics honored exactly like `JavaTeleportEntityTranslator`. Player-targeted teleports are recorded for the switch-snap reuse. |
| `EntityPositionSync` | `position` −O | 1.21.2+ path; `deltaMovement` R; no relatives on this packet (always absolute). |
| `SetEntityData` | metadata values of type `BLOCK_POS`/`OPTIONAL_BLOCK_POS` −O | These are the absolute-position metadata in the protocol: **bed position** (sleep at altitude), **ender-crystal beam target**, **creaking home** (Geyser translators verified). Display-entity `ROTATIONS`/translation metadata is entity-local (R) and never touched; matching is by `MetadataTypes` singleton identity. |
| `MoveVehicle` | `position` −O | |
| `MoveMinecart` | each lerp-step `position` −O | `movement` in the step is R. |
| `BlockEvent` | pos −O | note blocks. |
| `BlockDestruction` | pos −O | break progress render. |
| `BlockEntityData` | pos −O | NBT contents (sign lines etc.) are coordinate-free. |
| `OpenSignEditor` | pos −O | Geyser forwards this straight to Bedrock `OpenSignPacket` — without the shift, the sign editor at altitude opens on empty sky. |
| `LevelEvent` | pos (Vector3i) −O | |
| `LevelParticles` | `y` −O + **particle payload absolute targets** (`TrailParticleData.target`, `BlockPositionSource` inside `VibrationParticleData`) −O | offsets & velocity are R; `EntityPositionSource` and color/item payloads untouched (payload rebuilt only when it carries an absolute target). |
| `DebugBlockValue` | `blockPos` −O | debug subscription values observe one block (bees/brains/pathing overlays). |
| `GameTestHighlightPos` | `absolutePos` −O | `relativePos` is tester-relative (R) and stays. |
| `Sound` (category-positions) | `y` −O | |
| `PlayerLookAt` | `x/y/z` anchor `y` −O | camera "look at" targets (`JavaPlayerLookAtTranslator` computes the rotation delta from this point). |
| `DamageEvent` | `sourcePosition` (nullable) `y` −O | damage tilt/knockback direction source (`JavaDamageEventTranslator`). Packet has no `@With` — exact rebuild, all other fields copied. |
| `Explode` | `center` −O | `playerKnockback` R; `blockParticles` verified coordinate-free (`particle + scaling + speed`). |
| `SetDefaultSpawnPosition` | `globalPos.position` −O | respawn target / compass. |
| `Login` | `commonPlayerSpawnInfo.lastDeathPos` −O (side-effect: capture `entityId` as player java id; cache/window refresh) | death-screen coordinates / recovery compass (`JavaLoginTranslator`). ctor shape is pin-sensitive: the pinned 26.2 artifact ends `(..., PlayerSpawnInfo, boolean, boolean)` while current upstream master ends `(..., PlayerSpawnInfo, boolean)` - the transform uses `withCommonPlayerSpawnInfo`, which is shape-agnostic. |
| `Respawn` | `commonPlayerSpawnInfo.lastDeathPos` −O (side-effect: clear chunk cache, recompute window) | dimension change handled by re-send semantics of the server itself. |
| `TrackedWaypoint` | `VEC3I` waypoint `vector.y` −O | locator-bar waypoints; `CHUNK`/`AZIMUTH`/`EMPTY` data untouched. |
| `ForgetLevelChunk` | — (side-effect: cache eviction) | x/z only, no Y. |
| Everything else (`MoveEntity*`, `SetEntityMotion`, `SetHealth`, `SetPassengers`, `SetEntityLink`, damage, rotations, inventories, containers, chat, scoreboard, teams, boss bars, border, dimension sync, chunk biomes (Geyser ignores `ChunksBiomes` @ 2.11.x), light updates (positional arrays, Geyser ignores), …) | — | no absolute Y, or handled upstream by their nature. `MoveEntityPos/Rot` are *relative by protocol* — shifting them is the classic desync bug; they must stay untouched. |

## Serverbound (Geyser → Java server): shift by +O

| Packet | What is shifted | Notes |
|---|---|---|
| `MovePlayerPos`, `MovePlayerPosRot` | `y` +O | Also the position monitor for switch evaluation (realY = y+O, no extra tracking state). |
| `PlayerAction` (all dig/drop/swap/release states) | pos +O | During a switch: held (freeze) and replayed on unfreeze with frame resolution (reach check); overflow drops (queue cap 96), and dropped dig/finish/abort actions get an authoritative block re-send (ghost revert, stored in real space). |
| `UseItemOn` | pos +O | Held across switches like `PlayerAction`. `cursorX/Y/Z` are R (inside-block offsets); `face` is block-relative. Dropped placements revert **both** the clicked block and the position a block would appear at (pos+face). |
| `MoveVehicle` | position +O | |
| `PickItemFromBlock` | pos +O | |
| `SignUpdate` | pos +O | text lines untouched. |
| `SetCommandBlock` | pos +O | OP-tool edit at altitude. |
| `SetCommandMinecart` | — | carries no position at all on the wire - fields are exactly `(entityId, command, doesTrackOutput)` (verified); the minecart's block position lives server-side. |
| `SetStructureBlock` | pos +O | `offset`/`size` are R. |
| `SetJigsawBlock` | pos +O | |
| `JigsawGenerate` | pos +O | jigsaw "generate" action. |
| `BlockEntityTagQuery` | pos +O | NBT query target. |
| `SetTestBlock`, `TestInstanceBlockAction` | pos +O | 1.21.9 test-instance tools. |
| `ChatCommandSigned` | command string: first position triple's Y +O — **only** when `signatures` is empty | Signed commands are never modified (would invalidate the signature). What Geyser itself sends (empty signatures + zero checksum). |
| `ChatCommand` | command string: first position triple's Y +O (allowlisted) | the plain unsigned command packet; covered for completeness so a future Geyser swap cannot silently lose rewriting. |
| `AcceptTeleport` | — | id-only; the id we snap with reuses the server's last teleport so acks line up with Geyser's own state machine. |
| `ContainerClick`, `SetCarriedItem`, `Swing`, `PlayerCommand`, `Input`, `Interact`, `Attack`, `Spectate`, `CloseContainer`, … | — | no absolute Y (entity-relative targets are resolved by id on the server; `Interact`'s INTERACT_AT location is entity-relative = R). |

## Freeze coverage (why *every* position-bearing serverbound packet is held)

During a switch freeze the client may still be sending in the previous frame. `isPositionBearing`
covers the **whole** table above (not just dig/place): a sign edit, command-block save, jigsaw
generate or NBT query written mis-translated is a wrong-block action at altitude. Frame resolution on
replay: packets with a primary block position are reach-checked against the player's real Y (≤ 7
blocks — both frame hypotheses cannot satisfy it simultaneously since offsets are section-aligned);
movement/vehicle/commands are replayed in the frame they were sent in.

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

## Injection path (synthetic packets)

Chunk replays, the switch snap and ghost reverts are injected with `ctx.fireChannelRead(...)` at the
SkyWindow handler's own context: they land in mcprotocollib's `manager` handler (the `NetworkSession`
that dispatches to Geyser's packet listeners) exactly like a forwarded server packet, in window space,
without re-entering the transform. Injecting at the manager's own context would skip the manager and
die at the netty tail (silent discard) — the pipeline fact that matters: `flush-handler` is
outbound-only and `manager` is the terminal *inbound* handler.
