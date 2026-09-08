# 3. `config.properties`, not YAML

Date: 2026-09-08 — Status: accepted

## Context

Geyser extensions conventionally ship YAML (SnakeYAML), but at extension runtime the YAML
implementation visible to extension classloaders is Geyser's *relocated* copy
(`org.geysermc.geyser.yaml…`), which extensions must not import — and shipping a private SnakeYAML
copy inside the extension means a shaded dependency with its own CVE surface for 12 key-values.
3.x additionally suffered from YAML config drift (keys present in one version's sample, consumed
elsewhere, silently ignored).

## Decision

The standard `java.util.Properties` parser against
`extensions/skywindow/config.properties`: zero dependencies, parse cannot fail (bad values are
strings until *we* read them, and our readers clamp + collect warnings), unknown keys are
detectable (a `Properties` instance enumerates everything), and the sample file doubles as a
defaults oracle — a test asserts every sample value equals the code default, so documentation
drift fails CI instead of shipping.

## Consequences

- No nested config, no lists beyond comma-split `rewrite-commands`, no comments-in-values. Fine for
  12 flat knobs; revisit only if the config ever grows structure, via a vendored parser, never by
  importing Geyser's relocated YAML.
- `reload` is trivially complete: Geyser reloads fire a full re-parse (`core.reload()`), because
  `Properties.load` is cheap and there is no comment-preservation obligation.
