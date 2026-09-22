# Tested against

- **Minecraft 1.21.10** (Geyser 2.11 extension API).
- **Geyser `2.11.0` API** - the hard dependency of the build.
- **MCProtocolLib `26.2-20260824.124638-17`** - the exact protocol library build Geyser itself pins. Packet API forms are validated against its sources (`ClientboundCommandsPacket`, chunk block-entity lists, NBT maps, `Component` chat forms, suggestion packets).
- **Mojang Brigadier** (vendored MIT sources under `fr.buildtheearth.skywindow.brigadier`, see `THIRD-PARTY-NOTICES.md`) - Minecraft's own command parser, used as the primary oracle engine.
