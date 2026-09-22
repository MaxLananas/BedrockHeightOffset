# Research notes

Sources and upstream findings behind SkyWindow, so every design decision stays traceable.

## The core problem: Bedrock clients cannot render Y beyond 320

- **GeyserMC/Geyser#3804 - "High and low coordinates break the game"** (issue, labelled *Can't
  Fix* / *Missing Client Feature*). The Geyser team closes this upstream as unfixable at the
  translation layer alone: a Bedrock client physically cannot display the world above its own
  height cap, no matter how the packets are translated. The issue's own "Idea for fix: spoof the
  bedrock player's coordinates or force fit them within bedrock bounds" is exactly SkyWindow's
  approach - a coordinate *window* with per-Y content preservation (section bitsets) does what
  Geyser refuses to do upstream.
- **GeyserMC/Geyser#3392** - Geyser's own runtime warning "The server uses chunks higher than
  Bedrock can accept! ... height must remain between Y0 and Y256" confirms the same boundary and
  the Nether dimension variant of it.

## Community approaches we deliberately diverge from

- **BedrockHeightGuard** (Modrinth, BTE community plugin) does the *opposite* of SkyWindow: it
  restricts (was: kick/ban/message) Bedrock players moving above Y=320 to protect them from the
  client bugs - instead of enabling the sky like SkyWindow's window + content-preserving rewrite.
- **Terra** (PolyhedralDev) clamps world height rather than translating per-packet (see
  `docs/TERRA-NOTES.md` for the deep-dive).

## Mojang Brigadier - the parser we vendor

- Source: https://github.com/Mojang/brigadier, MIT licensed (Copyright (c) Microsoft Corporation).
  Vendored (package-relocated to `fr.buildtheearth.skywindow.brigadier`) per `THIRD-PARTY-NOTICES.md`.
- Why vendoring: `com.mojang:brigadier` is published to `libraries.minecraft.net` (not Maven
  Central), so depending on it would add a fragile remote repository to the build; the whole
  library is 47 files, and embedding the *exact* parser Minecraft itself uses gives SkyWindow's
  command oracle byte-perfect parse semantics - literal-first matching, argument fallback with
  backtracking, redirect aliases (execute ... run style), and character ranges from the parse -
  instead of a reimplementation's approximations. This mirrors how Commodore (lucko) embeds
  Brigadier for Bukkit plugins.
- SkyWindow only ever *parses* with Brigadier (no command registration, no execution): existing
  server commands are never modified or intercepted - the parser is the reader, `CommandYRewrite`
  is the writer, and both speak the wire tree the server itself published.
