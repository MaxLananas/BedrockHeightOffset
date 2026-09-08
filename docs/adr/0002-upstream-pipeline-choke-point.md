# 2. One netty handler on the Java upstream, after mcprotocollib's codec

Date: 2026-09-08 — Status: accepted

## Context

3.x rewrote packets on *both* sides: a Paper listener plus a reflection-installed interceptor on
the Bedrock (RakNet) pipeline. Every Bedrock-side rewrite double-applied what Geyser had already
translated, and both the Bedrock packet classes and the RakNet serializers are explicitly not
extension API. The rewrite question: where can *all* traffic be transformed once, with stability?

## Decision

Install `SkyWindowHandler` into Geyser's **upstream** (Java) channel — the mcprotocollib pipeline —
immediately after the `codec` handler (locate via `NetworkConstants.CODEC_NAME` / `MANAGER_NAME`,
`addBefore`; retry with backoff while the session is still bootstrapping, mark degraded on
giving up). That point sees: every decoded Java packet inbound (`channelRead`) and every Java
packet outbound before serialization (`write`), for exactly one session.

## Consequences

- Packets are transformed as *typed immutable objects* (`@With` copies), not bytes: no serializer
  knowledge, no version sniffing of the Bedrock protocol, and untouched packets are forwarded by
  reference with zero allocation — which is what makes the idle cost of this extension one
  `instanceof` per packet.
- Geyser's own translators then produce Bedrock-side packets that are window-consistent by
  construction; there is no second place to keep in sync, and no Bedrock-side hook exists at all.
- Placement is a seam (handler names can move across Geyser versions). It fails safe:
  attach retries, then passive + `doctor` reports it. The pom enforces that no platform-plugin
  artifact is ever depended on to reach it — `api` + `core` + `mcprotocollib` only.
- The one non-pipeline side effect (the `WorldManager` collision decorator) is likewise installed
  per session, is fail-soft, and is documented as a seam, not hidden.
