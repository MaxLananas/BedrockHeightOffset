#!/usr/bin/env python3
"""Packet coverage audit - enforces SkyWindow's "every packet with a position is handled" contract.

Walks a MCProtocolLib checkout, finds every ingame/login/configuration packet class that declares a
positional field (Vector3i/Vector3d/GlobalPos or a known positional nested type), and verifies the
SkyWindow transform chains mention each of them. A packet with a position that is NOT translated is
a wrong-block-at-altitude waiting to happen, so this gate deliberately fails the build.

Usage: packet_coverage.py <mcprotocollib-root> <skywindow-src-main-java-root>

Escape hatch: dev/audit/coverage_allowlist.txt (one class simple name per line, '#' comments with
the reason). Keep it EMPTY unless a packet is provably position-free despite the heuristic, or
exists only in MCProtocolLib master beyond the pinned protocol version (then say so in the comment).

The reverse direction (SkyWindow referencing a class absent from master) is a WARNING only: SkyWindow
builds against the pinned MCProtocolLib (see SkyWindowCore.TESTED_AGAINST), whose shapes can differ
from master.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

PACKET_DIRS = (
    "protocol/src/main/java/org/geysermc/mcprotocollib/protocol/packet/ingame",
    "protocol/src/main/java/org/geysermc/mcprotocollib/protocol/packet/login",
    "protocol/src/main/java/org/geysermc/mcprotocollib/protocol/packet/configuration",
)
VECTOR_TYPES = ("Vector3i", "Vector3d", "Vector2i", "Vector2f", "GlobalPos", "BlockPos", "ChunkPos")
NESTED_POSITIONAL = {
    "PlayerSpawnInfo", "MinecartStep", "VibrationParticleData", "BlockPositionSource",
    "EntityPositionSource", "BlockHitResultValue", "StructureTemplateTestInstanceBlock",
}
TRANSFORM_FILES = {
    "serverbound": "fr/buildtheearth/skywindow/translate/OutboundYTransforms.java",
    "clientbound": "fr/buildtheearth/skywindow/translate/InboundYTransforms.java",
}
FIELD_RE = re.compile(
    r"^\s*private\s+(?:final\s+)?(?:@[\w.]+(?:\([^)]*\))?\s+)*"
    r"(?P<type>[A-Za-z][\w.]*(?:<[^;=]*>)?(?:\[\])?)\s+(?P<name>\w+)\s*(?:=[^;]*)?;")
DECODE_CTOR_RE = re.compile(r"public\s+\w+Packet\s*\(\s*ByteBuf")
NEST_START_RE = re.compile(r"^\s*(?:public|private|protected|static|\s)*(?:static\s+)?(?:final\s+)?(?:class|record|enum)\s+\w+")


def load_allowlist(root: Path) -> set[str]:
    path = Path(__file__).resolve().parent / "coverage_allowlist.txt"
    names: set[str] = set()
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.split("#", 1)[0].strip()
            if line:
                names.add(line)
    return names


def base_type(type_str: str) -> str:
    return type_str.split("<", 1)[0].split(".", 1)[0].replace("[]", "").strip()


def packet_fields(java_file: Path) -> list[tuple[str, str]]:
    """Declared fields above the ByteBuf decode constructor (the packet's own state)."""
    fields: list[tuple[str, str]] = []
    in_comment = False
    for raw in java_file.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw
        if in_comment:
            if "*/" in line:
                in_comment = False
                line = line.split("*/", 1)[1]
            else:
                continue
        if "/*" in line and "*/" not in line:
            in_comment = True
            line = line.split("/*", 1)[0]
        line = line.split("//", 1)[0]
        if DECODE_CTOR_RE.search(line):
            break
        if NEST_START_RE.search(line) and "class" not in java_file.stem:
            continue
        match = FIELD_RE.match(line)
        if match:
            fields.append((match.group("type"), match.group("name")))
    return fields


def is_positional(type_str: str, name: str) -> bool:
    base = base_type(type_str)
    if base in VECTOR_TYPES:
        return True
    if base in NESTED_POSITIONAL:
        return True
    if base in ("List", "Collection") and any(n in type_str for n in NESTED_POSITIONAL):
        return True
    return False


# ---------------------------------------------------------------- text carriers

# Packets whose payload can carry COMMAND TEXT (positions hiding in strings, not fields). The
# position-field audit above cannot see these; this curated gate + auto-scan makes sure none is
# forgotten when protocol versions move things around. Checked against the SkyWindow sources.
TEXT_CARRIERS = {
    "ServerboundChatCommandPacket": "chat command text",
    "ServerboundChatCommandSignedPacket": "signed chat command text (gated on empty signatures)",
    "ServerboundSetCommandBlockPacket": "stored command-block command",
    "ServerboundSetCommandMinecartPacket": "command-minecart command",
    "ServerboundCommandSuggestionPacket": "tab-completion partial command",
    "ClientboundCommandSuggestionsPacket": "suggestion ranges (mapped back to the editing frame)",
    "ClientboundBlockEntityDataPacket": "block-entity Command NBT (editor display)",
}
# Text payloads that are CONTENT, not coordinates - reviewed and deliberately not rewritten.
TEXT_ALLOWLIST_EXTRA = {
    "ServerboundChatPacket": "plain chat message content (content, not coordinates)",
}


def check_text_carriers(mcpl_root: Path, src_root: Path, allowlist: set) -> list:
    chain_text = ""
    for rel in TRANSFORM_FILES.values():
        chain_text += (src_root / rel).read_text(encoding="utf-8", errors="replace")
    chain_text += (src_root / "fr/buildtheearth/skywindow/pipeline/SkyWindowHandler.java").read_text(
        encoding="utf-8", errors="replace")
    errors = []
    for name, why in sorted(TEXT_CARRIERS.items()):
        if name not in chain_text:
            errors.append(f"text carrier NOT referenced anywhere in the pipeline: {name} ({why})")
    # Auto-scan: any packet declaring a String field named 'command' or 'text' must be covered
    # or explicitly allowlisted (the curated list above is for array/NBT carriers the scan cannot see).
    covered = set(TEXT_CARRIERS) | set(TEXT_ALLOWLIST_EXTRA) | allowlist | set(
        re.findall(r"instanceof (\w+Packet)\b", chain_text))
    for rel_dir in PACKET_DIRS:
        packet_dir = mcpl_root / rel_dir
        if not packet_dir.is_dir():
            continue
        for java_file in sorted(packet_dir.rglob("*Packet.java")):
            simple = java_file.stem
            if simple in covered:
                continue
            for ftype, fname in packet_fields(java_file):
                if ftype == "String" and fname in ("command", "text"):
                    errors.append(
                        f"packet with a String field '{fname}' is not covered by any text rule: "
                        f"{simple} ({java_file.name}) - rewrite it, or add a reviewed allowlist entry")
    return errors


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    mcpl_root = Path(sys.argv[1])
    src_root = Path(sys.argv[2])
    allowlist = load_allowlist(mcpl_root)

    transforms: dict[str, str] = {}
    for direction, rel in TRANSFORM_FILES.items():
        path = src_root / rel
        if not path.exists():
            print(f"ERROR: transform chain missing: {path}")
            return 1
        transforms[direction] = path.read_text(encoding="utf-8", errors="replace")

    errors: list[str] = []
    warnings: list[str] = []
    checked = 0
    positional_total = 0

    for rel_dir in PACKET_DIRS:
        packet_dir = mcpl_root / rel_dir
        if not packet_dir.is_dir():
            warnings.append(f"missing directory in MCProtocolLib checkout: {rel_dir}")
            continue
        for java_file in sorted(packet_dir.rglob("*Packet.java")):
            checked += 1
            simple = java_file.stem
            subpath = str(java_file.relative_to(mcpl_root))
            direction = "serverbound" if "/serverbound/" in subpath.replace("\\", "/") else "clientbound"
            fields = packet_fields(java_file)
            positional = [(t, n) for t, n in fields if is_positional(t, n)]
            if not positional:
                continue
            positional_total += 1
            if simple in allowlist:
                continue
            chain = transforms[direction]
            if f"instanceof {simple} " not in chain and f"instanceof {simple}\n" not in chain:
                errors.append(
                    f"{direction} packet with position {positional} is NOT handled: {simple} ({subpath})")

    for direction, chain in transforms.items():
        for name in sorted(set(re.findall(r"instanceof (\w+Packet)\b", chain))):
            # reverse check is pin-sensitive -> warning only (see module docstring)
            if not any((mcpl_root / d / f"{name}.java").exists() or
                       list((mcpl_root / d).rglob(f"{name}.java")) for d in PACKET_DIRS if (mcpl_root / d).is_dir()):
                warnings.append(f"{direction} chain references {name} not found in master (pin-sensitive)")

    errors.extend(check_text_carriers(mcpl_root, src_root, allowlist))
    print(f"packet coverage audit: {checked} packet classes scanned, "
          f"{positional_total} carry positions, {len(TEXT_CARRIERS)} text carriers gated, "
          f"allowlist entries: {len(allowlist)}")
    for warning in warnings:
        print(f"WARNING: {warning}")
    if errors:
        print("\n".join(f"ERROR: {e}" for e in errors))
        print(f"\nFAILED: {len(errors)} positional packet(s) unhandled. Handle them in the transform "
              f"chains, or add a justified entry to dev/audit/coverage_allowlist.txt.")
        return 1
    print("OK: every positional packet is referenced by its transform chain.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
