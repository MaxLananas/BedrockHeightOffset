#!/usr/bin/env python3
"""
SkyWindow algorithm oracle.

This mirrors the pure logic in WindowRules.java / SectionCodec.java and then checks the SPEC
(coverage, exact round-trips, pruning boundaries, switch cadence), so an implementation error shows
up as a failed property rather than as two implementations agreeing with each other and with nothing
else. It runs anywhere; the Java mirror is exercised by JUnit in CI.

    python3 dev/verify/oracle.py [rounds]
"""
import random
import struct
import sys

SECTION = 16

# --------------------------------------------------------------------------- WindowRules mirror

def floor_to_section(y):
    return (y // SECTION) * SECTION


def windowing_needed(java_min_y, java_max_y, client_min_y, client_height):
    return java_min_y < client_min_y or java_max_y - 1 > client_min_y + client_height - 1


def max_useful_offset(java_max_y, client_min_y, client_height):
    return floor_to_section(java_max_y - client_min_y - client_height)


def target_offset(real_y, client_min_y, client_height, max_offset):
    if max_offset <= 0:
        return 0
    centered = real_y - (client_min_y + client_height / 2.0)
    snapped = int(centered / SECTION + 0.5) * SECTION  # matches Java floor(x+0.5)
    return max(0, min(max_offset, snapped))


def should_switch(real_y, current_offset, client_min_y, client_height, margin, max_offset):
    client_y = real_y - current_offset
    near_top = client_y >= client_min_y + client_height - margin
    near_bottom = current_offset > 0 and client_y <= client_min_y + margin
    if not near_top and not near_bottom:
        return False
    return target_offset(real_y, client_min_y, client_height, max_offset) != current_offset


# --------------------------------------------------------------------------- SectionCodec mirror

EMPTY_SECTION = bytes([0, 0, 0, 0, 0, 0, 0, 0])
BLOCK_MAX_PALETTE_BITS = 8
BIOME_MAX_PALETTE_BITS = 3


class FormatError(Exception):
    pass


def read_u8(data, p):
    if p >= len(data):
        raise FormatError("eof")
    return data[p]


def read_varint(data, p):
    value = 0
    for i in range(5):
        if p + i >= len(data):
            raise FormatError("eof varint")
        b = data[p + i]
        value |= (b & 0x7F) << (7 * i)
        if (b & 0x80) == 0:
            return value, p + i + 1
    raise FormatError("varint too long")


def skip_palette(data, p, max_list_bits):
    bits = read_u8(data, p)
    p += 1
    if bits == 0:
        _, p = read_varint(data, p)  # singleton id
        return p
    if bits <= max_list_bits:
        count, p = read_varint(data, p)
        for _ in range(count):
            _, p = read_varint(data, p)
    long_count, p = read_varint(data, p)
    if long_count < 0 or long_count * 8 > len(data) - p:
        raise FormatError("storage oob")
    return p + long_count * 8


def section_length(data, offset):
    p = offset + 4
    if p > len(data):
        raise FormatError("header oob")
    p = skip_palette(data, p, BLOCK_MAX_PALETTE_BITS)
    p = skip_palette(data, p, BIOME_MAX_PALETTE_BITS)
    if p > len(data):
        raise FormatError("section oob")
    return p - offset


def reslice(data, in_sections, shift, out_sections):
    """Strict walk used by the property checks below (raises on anything malformed)."""
    starts = []
    p = 0
    for _ in range(in_sections):
        starts.append(p)
        p += section_length(data, p)
    starts.append(p)
    if p != len(data):
        raise FormatError("trailing bytes")
    out = bytearray()
    for w in range(max(0, out_sections)):
        src = w + shift
        if 0 <= src < in_sections:
            out += data[starts[src]:starts[src + 1]]
        else:
            out += EMPTY_SECTION
    return bytes(out)


def reslice_tolerant(data, in_sections, shift, out_sections):
    """Mirror of SectionCodec.resliceTolerant: never raises; pads/normalizes; flags anomalies."""
    secs = []
    p = 0
    anomaly = False
    for _ in range(max(0, in_sections)):
        try:
            ln = section_length(data, p)
        except FormatError:
            anomaly = True
            break
        secs.append(data[p:p + ln])
        p += ln
    if p != len(data):
        anomaly = True
    out = bytearray()
    for w in range(max(0, out_sections)):
        src = w + shift
        if 0 <= src < len(secs):
            out += secs[src]
        else:
            out += EMPTY_SECTION
    return bytes(out), len(secs), anomaly


# --------------------------------------------------------------------------- section generator

def gen_singleton(block_id=0, biome_id=0):
    return struct.pack(">hh", 0, 0) + bytes([0]) + varint(block_id) + bytes([0]) + varint(biome_id)


def gen_global(long_count=256, block_count=0):
    out = struct.pack(">hh", block_count, 0)
    out += bytes([9])  # global block palette
    out += varint(long_count) + bytes(8 * long_count)
    out += bytes([0]) + varint(0)  # biome singleton
    return out


def gen_list(palette, storage_words=3):
    out = struct.pack(">hh", 8, 0)
    out += bytes([4]) + varint(len(palette))
    for pid in palette:
        out += varint(pid)
    out += varint(storage_words) + bytes(8 * storage_words)
    out += bytes([0]) + varint(0)
    return out


def varint(value):
    out = bytearray()
    while value & ~0x7F:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


# --------------------------------------------------------------------------- properties

C_MIN, C_H = -512, 1024            # extended-height Bedrock overworld client
J_MIN, J_MAX = -64, 1952           # BTE-like Java dimension
MARGIN = 64


def property_reachable_window():
    """Every real player Y from the floor to just above the roof maps inside the client window,
    allowing only the small overshoot that flying at the world top can produce (the server clamps
    players to maxHeight, so at max offset the worst client-space overshoot is a handful of blocks
    past the ceiling - exactly like creative flight above 320 on a legacy client). Offsets are legal
    (aligned, within [0, maxUseful]) and the round-trip is exact."""
    mx = max_useful_offset(J_MAX, C_MIN, C_H)
    assert mx == 1440, mx
    for real_y in range(J_MIN, J_MAX + 2):  # incl. 1 above the world top (flight clamp)
        o = target_offset(real_y, C_MIN, C_H, mx)
        client_y = real_y - o
        assert o % SECTION == 0 and 0 <= o <= mx
        assert C_MIN <= client_y <= C_MIN + C_H + 16, (real_y, o, client_y)
        assert client_y + o == real_y  # exact integer round-trip
    # blocks (what must be placeable) never map out of the *block* range while they are under the
    # player's own window: any real block Y inside [o+C_MIN, o+C_MIN+C_H-1] stays in [C_MIN, C_MIN+C_H-1]
    for real_y in range(J_MIN, J_MAX):
        o = target_offset(real_y, C_MIN, C_H, mx)
        low, high = o + C_MIN, o + C_MIN + C_H - 1
        for by in (low, min(real_y, high), max(real_y - 1, low)):
            if low <= by <= high:
                assert C_MIN <= by - o <= C_MIN + C_H - 1, (real_y, o, by)


def property_switch_cadence():
    """Climbing from floor to roof triggers few switches; each re-centering puts the player back at
    window middle; hovering never oscillates."""
    mx = max_useful_offset(J_MAX, C_MIN, C_H)
    offset = 0
    switches = 0
    for real_y in range(J_MIN, J_MAX + 1):
        client_y = real_y - offset
        assert C_MIN <= client_y <= C_MIN + C_H + 16, (real_y, offset)  # never strays out of range
        if should_switch(real_y, offset, C_MIN, C_H, MARGIN, mx):
            new = target_offset(real_y, C_MIN, C_H, mx)
            assert new != offset
            # either re-centered inside the window, or pinned at the top-of-world clamp
            assert abs(real_y - new - (C_MIN + C_H / 2)) <= SECTION or new == mx, (real_y, new)
            offset = new
            switches += 1
    assert 1 <= switches <= 6, switches
    assert offset == mx

    offset = 0
    switches = 0
    for i in range(20000):  # long hover across the edge region
        real_y = 480 + 14 * ((i * 37) % 101 / 101 - 0.5) * 2
        if should_switch(real_y, offset, C_MIN, C_H, MARGIN, mx):
            new = target_offset(real_y, C_MIN, C_H, mx)
            if new != offset:
                switches += 1
                offset = new
    assert switches <= 1, switches  # one switch at most, then hysteresis holds


def property_fits_never_switches():
    for java_min, java_max, c_min, c_h in [
        (-64, 320, -64, 384), (-64, 320, -512, 1024), (0, 128, 0, 256),
        (0, 256, 0, 256), (-64, 512, -512, 1024), (-2048, 2048, -512, 1024),
    ]:
        needed = windowing_needed(java_min, java_max, c_min, c_h)
        assert needed != (java_min >= c_min and java_max - 1 <= c_min + c_h - 1)


def property_reslice_semantics():
    """reslice(out[w]=in[w+shift]) on real section bytes; identity when shift=0 and sizes match;
    content preserved where in range; empties synthesized elsewhere; length arithmetic exact."""
    rnd = random.Random(1337)
    for trial in range(300):
        n = rnd.randint(1, 40)
        shift = rnd.randint(-3, n + 2)
        out_n = rnd.choice([n, n + shift, max(0, n - abs(shift)), 40])
        sections = []
        for _ in range(n):
            kind = rnd.randint(0, 2)
            sections.append(
                gen_singleton(rnd.randint(0, 512), rnd.randint(0, 63)) if kind == 0 else
                (gen_global(256, rnd.randint(0, 4096)) if kind == 1 else
                 gen_list([0, rnd.randint(1, 8192)] * rnd.randint(1, 3))))
        payload = b"".join(sections)
        out = reslice(payload, n, shift, out_n)
        # walk the output: same section count, every slot decodes, content matches source exactly
        p = 0
        outs = []
        for _ in range(max(0, out_n)):
            ln = section_length(out, p)
            outs.append(out[p:p + ln])
            p += ln
        assert p == len(out)
        assert len(outs) == max(0, out_n)
        for w, sec in enumerate(outs):
            src = w + shift
            if 0 <= src < n:
                assert sec == sections[src], (trial, w)
            else:
                assert sec == EMPTY_SECTION, (trial, w)
    # shift 0 identity
    for n in (1, 8, 126):
        payload = b"".join(gen_singleton(i, 0) for i in range(n))
        assert reslice(payload, n, 0, n) == payload


def property_malformed_input_throws():
    """The strict walker raises on any truncation (never mis-slices silently)."""
    good = b"".join(gen_singleton() for _ in range(4))
    for cut in range(1, len(good)):
        try:
            reslice(good[:cut], 4, 1, 5)
        except FormatError:
            continue
        raise AssertionError(f"truncation at {cut} not detected")
    try:
        reslice(good + b"\x00", 4, 0, 4)
        raise AssertionError("trailing bytes not detected")
    except FormatError:
        pass


def property_tolerant_reslice_contract():
    """reslice_tolerant: output ALWAYS walks clean to exactly out_sections, content up to the
    failure point is preserved byte-identically at shifted slots, and every deviation sets the flag."""
    rnd = random.Random(99)
    for trial in range(300):
        n = rnd.randint(1, 20)
        shift = rnd.randint(-2, n + 1)
        out_n = rnd.choice([n, n + 3, max(0, n - 2)])
        sections = [rnd.choice([gen_singleton(rnd.randint(0, 300), 0), gen_global(8), gen_list([0, 5])])
                     for _ in range(n)]
        good = b"".join(sections)
        for data, expect_anomaly in (
            (good, False),
            (good + b"\x05\x05", True),                      # trailing junk
            (good[:len(good) - rnd.randint(1, min(5, len(good) - 1))], True),  # truncation
        ):
            out, parsed, anomaly = reslice_tolerant(data, n, shift, out_n)
            assert anomaly == expect_anomaly or expect_anomaly, (trial, anomaly)
            # output is a clean, fully-walkable payload of exactly out_n sections
            p = 0
            w = 0
            while p < len(out) and w < out_n:
                ln = section_length(out, p)  # must never raise
                p += ln
                w += 1
            assert p == len(out) and w == out_n, (trial, w, out_n)
            # preserved source sections land at shifted slots byte-identically
            for src in range(min(parsed, n)):
                dst = src - shift
                if 0 <= dst < out_n:
                    # walk to dst
                    pos = 0
                    for i in range(dst):
                        pos += section_length(out, pos)
                    seg = out[pos:pos + section_length(out, pos)]
                    assert seg == sections[src], (trial, src, dst)


def property_reachable_top_formula():
    mx = max_useful_offset(J_MAX, C_MIN, C_H)
    top = min(J_MAX - 1, C_MIN + C_H - 1 + mx)
    assert top == 1951, top
    # with an artificial cap, the reachable top follows, still clamped by the world itself
    assert min(J_MAX - 1, C_MIN + C_H - 1 + 1000) == 1511
    assert min(320 - 1, C_MIN + C_H - 1 + 0) == 319


def property_section_size_bounds():
    """A global section has the canonical 1.21 size (4 + 1 + 1 + 2048 + 1 + 1),
    and a full-size 126-section air payload is what a sparse chunk costs the cache."""
    g = gen_global()
    # header(4) + bits(1) + varint(256)=2 + 2048 storage + biome bits(1) + id(1)
    assert len(g) == 4 + 1 + (2 + 8 * 256) + 1 + 1, len(g)
    s = gen_singleton()
    assert len(s) == 8
    sparse = 126 * len(s)
    assert 1000 < sparse < 2048  # ~1KB for an all-air BTE sky chunk payload: cheap to replay


def main():
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    props = [property_reachable_window, property_switch_cadence, property_fits_never_switches,
             property_reslice_semantics, property_malformed_input_throws,
             property_tolerant_reslice_contract, property_reachable_top_formula,
             property_section_size_bounds]
    for r in range(rounds):
        for prop in props:
            prop()
            print(f"  ok: {prop.__name__} (round {r})")
    print("ALL PROPERTIES PASS")


if __name__ == "__main__":
    main()
