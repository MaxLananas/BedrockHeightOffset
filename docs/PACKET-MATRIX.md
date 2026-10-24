# Packet matrix

Every packet class in mcprotocollib that carries an absolute Y, or that Geyser reads a Y from, and what Hauteur does
with it. `A` = shifted by the offset, `R` = relative and never shifted, `-` = no absolute Y in this shape.

Audited against Geyser `2.11.2-SNAPSHOT` (commit `b2dab3c73`) and MCProtocolLib `b77d50c3` (the 2026-07-09
snapshot Geyser depends on). Re-audit on every Geyser bump, following [CONTRIBUTING.md](../CONTRIBUTING.md).

## Server to client (inbound, real to client)

| Packet | Field(s) | Action | Note |
|---|---|---|---|
| `ClientboundLevelChunkWithLightPacket` | chunk payload | Re-home sections (`WindowedChunks`) | Original cached; light arrays not re-homed. A code search of the Geyser default branch finds no reference to `LightUpdateData` in Geyser core, while it finds `getChunkData` in the chunk translator. |
| `ClientboundForgetLevelChunkPacket` | x, z | Cache eviction | No Y. |
| `ClientboundBlockUpdatePacket` | entry position | A; journaled | |
| `ClientboundSectionBlocksUpdatePacket` | entries; `chunkY` | A entries; `chunkY` -= offset/16; journaled | |
| `ClientboundBlockEntityDataPacket` | position | A; journaled | NBT is coordinate-free. |
| `ClientboundBlockEventPacket`, `ClientboundBlockDestructionPacket`, `ClientboundLevelEventPacket` | position | A | |
| `ClientboundLevelParticlesPacket`, `ClientboundSoundPacket` | y | A | Offsets and velocity are R. |
| `ClientboundExplodePacket` | center | A | `playerKnockback` is R. |
| `ClientboundDamageEventPacket` | sourcePosition (absolute, nullable) | A | Null when the source has no position. Entity and cause ids are not positions. |
| `ClientboundAddEntityPacket` | y | A | |
| `ClientboundTeleportEntityPacket` | position (absolute) | A | Relative Y (`R`) is not shifted. Player id: authoritative switch trigger. |
| `ClientboundEntityPositionSyncPacket` | position | A | Player id: authoritative switch trigger. |
| `ClientboundPlayerPositionPacket` | position (absolute) | A | Own-player teleport. Relative Y is not shifted. Authoritative switch trigger. |
| `ClientboundMoveVehiclePacket` | position | A | |
| `ClientboundMoveMinecartPacket` | each step position | A | Step movement is R. |
| `ClientboundPlayerLookAtPacket` | y (target) | A | |
| `ClientboundOpenSignEditorPacket` | position | A | |
| `ClientboundSetDefaultSpawnPositionPacket` | global position | A | |
| `ClientboundLoginPacket`, `ClientboundRespawnPacket` | - | New world: cache cleared, offset reset | The death location is written unshifted. Geyser's `setLastDeathPosition` writes it to the Bedrock player metadata (`PLAYER_LAST_DEATH_POS`). A search of the Geyser tree at `b2dab3c73` finds no Java reader of the stored value. Hauteur calls `startNewWorld`, which resets the offset, before forwarding these packets, so the value is in the real frame. A later switch does not rewrite it; the effect on the compass is untested. Re-check on every Geyser bump. |
| `ClientboundMoveEntityPos/Rot/PosRot`, `ClientboundSetEntityMotionPacket` | delta or velocity | R | Shifting relative quantities desyncs entities. |
| `ClientboundChunksBiomesPacket` | biome payload | Not windowed | Geyser does not read this packet. |
| `ClientboundDebug*`, `ClientboundGameTestHighlightPosPacket`, `ClientboundTestInstanceBlockStatus`, `ClientboundMapItemDataPacket` | positions | Not windowed | Debug and test tooling, or no client-visible effect. Documented, not verified. |

## Client to server (outbound, client to real)

| Packet | Field(s) | Action | During a freeze |
|---|---|---|---|
| `ServerboundMovePlayerPosPacket`, `ServerboundMovePlayerPosRotPacket` | y | A (`y + O`); switch evaluation | Hold (`freeze-hold-movement`) |
| `ServerboundMovePlayerRotPacket`, `ServerboundMovePlayerStatusOnlyPacket` | - | - | Transmit |
| `ServerboundMoveVehiclePacket` | position | A | Hold (movement) |
| `ServerboundPlayerActionPacket` | position (digging, and a drop taken from the block-break action) | A for `START/CANCEL/FINISH_DIGGING` and a non-zero `DROP_ITEM`; other actions carry a zero position and are untouched | Block actions: hold (`freeze-hold-actions`); dropped digs are re-sent. Others: transmit |
| `ServerboundUseItemOnPacket` | position | A (cursor and face are R) | Hold (`freeze-hold-actions`) |
| `ServerboundPickItemFromBlockPacket` | pos | A | Hold |
| `ServerboundSignUpdatePacket` | position | A | Hold |
| `ServerboundSetCommandBlockPacket` | position | A | Hold |
| `ServerboundSetStructureBlockPacket` | position | A (offset and size are R) | Hold |
| `ServerboundSetJigsawBlockPacket` | position | A | Hold |
| `ServerboundJigsawGeneratePacket` | position | A (levels are R) | Hold |
| `ServerboundBlockEntityTagQueryPacket` | position | A | Hold |
| `ServerboundSetTestBlockPacket` | position | A | Hold |
| `ServerboundTestInstanceBlockActionPacket` | pos | A | Hold |
| `ServerboundChatCommandSignedPacket` | command | Rewrites the first position triple of allowlisted commands, only when unsigned | Drop when unsigned; transmit when signed |
| `ServerboundInteractPacket` | location | R (offset within the entity) | Transmit |
| `ServerboundAcceptTeleportationPacket` | id | - | Transmit |
| `ServerboundSetCommandMinecartPacket` | command, flag | - (no position on the wire) | Transmit |
| Other serverbound packets (inventory, chat, containers, abilities, input, ...) | - | - | Transmit |

## Why some packets are dropped rather than guessed

- **Unsigned commands** carry coordinates inside a string. The frame cannot be decided from the string alone, and
  signed commands cannot be rewritten without invalidating their signature.
- **Movement and block actions** are held and resolved by plausibility (see [ARCHITECTURE.md](ARCHITECTURE.md)).
  A packet with no unique plausible frame is dropped, so it never lands at the wrong altitude.

## Audit steps

1. Search Geyser's translators for the packet classes listed above and for any new `Vector3i`/`position`/`getY`
   usage in `core/src/main/java/org/geysermc/geyser/translator/`.
2. For each position-carrying packet, classify it as A, R or `-` and record the reason.
3. Update `InboundYTransforms`, `OutboundYTransforms` (including `frozenDisposition` and `blockPosition`), the
   tests in `translate/`, and `dev/verify/oracle.py` in the same commit.
