"""Generates the Build Battle map as a Sponge v2 schematic for FAWE/WorldEdit.

    python -I scripts/maps/buildbattle_map.py maps/buildbattle.schem

Layout (x = east, z = south, y = up; the min corner is where you stand when you //paste):
  * 10 plots of 25 x 25 (grass floor), 5 in a row on each side of a 7-wide walkway.
  * Plots are framed by 3-wide dark borders with a solid wall up the middle, as high as the build space, so nobody
    sees anyone else's build until voting: dark deepslate with orange bands, gold where walls meet. Over everything is
    an invisible barrier ceiling (it lets daylight in), 32 blocks above each floor.
  * The walkway is dark grey with an orange centre line, a gold marker in front of each plot and a gold/orange emblem
    in the middle (the lobby spot), under three archways (one at each end, one over the emblem).
"""
import gzip
import struct
import sys

PLOT = 25          # plot floor size
FRAME = 3          # border width
WALK = 7           # walkway width
PER_ROW = 5
BUILD = 32         # build height above the floor

W = FRAME + PER_ROW * (PLOT + FRAME)                 # 143
ROW_A = (FRAME, FRAME + PLOT - 1)                    # z 3..27
WALK_Z = (ROW_A[1] + FRAME + 1, ROW_A[1] + FRAME + WALK)   # z 31..37
ROW_B = (WALK_Z[1] + FRAME + 1, WALK_Z[1] + FRAME + PLOT)  # z 41..65
L = ROW_B[1] + FRAME + 1                              # 69
FLOOR = 1
CEILING = FLOOR + BUILD + 1                           # 34
H = CEILING + 1                                       # 35

DATA_VERSION = 3955  # 1.21; FAWE upgrades older block data on load

AIR = "minecraft:air"
BARRIER = "minecraft:barrier"
GRASS = "minecraft:grass_block[snowy=false]"
BASE = "minecraft:polished_deepslate"
TILES = "minecraft:deepslate_tiles"
TILE_SLAB = "minecraft:deepslate_tile_slab[type=bottom,waterlogged=false]"
CAP = "minecraft:orange_terracotta"
GOLD = "minecraft:gold_block"
WALK_EDGE = "minecraft:deepslate_tiles"
WALK_MID = "minecraft:gray_concrete"
WALK_LINE = "minecraft:orange_terracotta"
GLOW = "minecraft:shroomlight"
HANGING = "minecraft:lantern[hanging=true,waterlogged=false]"
CHISELED = "minecraft:chiseled_deepslate"
BANDS = (12, 23)  # heights of the orange bands along the walls


def plot_columns():
    """x ranges of the plots."""
    return [(FRAME + i * (PLOT + FRAME), FRAME + i * (PLOT + FRAME) + PLOT - 1) for i in range(PER_ROW)]


def in_ranges(v, ranges):
    return any(a <= v <= b for a, b in ranges)


def build():
    cols = plot_columns()
    rows_z = [ROW_A, ROW_B]
    blocks = {}

    def put(x, y, z, block):
        blocks[(x, y, z)] = block

    for x in range(W):
        for z in range(L):
            put(x, 0, z, BASE)
            in_col = in_ranges(x, cols)
            in_plot_row = in_ranges(z, rows_z)
            in_walk = WALK_Z[0] <= z <= WALK_Z[1] and FRAME <= x <= W - 1 - FRAME
            if in_col and in_plot_row:
                put(x, FLOOR, z, GRASS)
                continue
            if in_walk:
                if z in (WALK_Z[0], WALK_Z[1]):
                    block = WALK_EDGE
                elif z == (WALK_Z[0] + WALK_Z[1]) // 2:
                    block = WALK_LINE
                else:
                    block = WALK_MID
                put(x, FLOOR, z, block)
                continue
            # Border.
            put(x, FLOOR, z, BASE)

    # Border walls: the middle line of each border gets a wall, its sides a slab.
    border_x_mids = [a - 2 for a, _ in cols] + [cols[-1][1] + 2]          # x of vertical border middles
    border_z_mids = [ROW_A[0] - 2, ROW_A[1] + 2, ROW_B[0] - 2, ROW_B[1] + 2]  # z of horizontal border middles

    def border_cell(x, z):
        return not (in_ranges(x, cols) and in_ranges(z, rows_z)) and not (
            WALK_Z[0] <= z <= WALK_Z[1] and FRAME <= x <= W - 1 - FRAME)

    outer = lambda x, z: x in (border_x_mids[0], border_x_mids[-1]) or z in (border_z_mids[0], border_z_mids[-1])

    for x in range(W):
        for z in range(L):
            if not border_cell(x, z):
                continue
            on_x = x in border_x_mids and (outer(x, z) or ROW_A[0] - 3 <= z <= ROW_A[1] + 3 or ROW_B[0] - 3 <= z <= ROW_B[1] + 3)
            on_z = z in border_z_mids
            # The walkway is open along x: no vertical wall crosses it.
            if on_x or on_z:
                # A solid wall from the slab steps up to the ceiling.
                crossing = on_x and on_z
                for y in range(2, CEILING):
                    if y in BANDS or y == CEILING - 1:
                        block = GOLD if crossing else CAP
                    elif y <= 3 or crossing:
                        block = TILES
                    else:
                        block = BASE
                    put(x, y, z, block)
            else:
                # Beside a wall: a slab step, and a 3x3 base around each crossing.
                near_crossing = any(abs(x - bx) <= 1 for bx in border_x_mids) and any(abs(z - bz) <= 1 for bz in border_z_mids)
                put(x, 2, z, TILES if near_crossing else TILE_SLAB)

    # Walkway: a gold marker in front of each plot, an emblem in the middle (the lobby spot), lights along the edges.
    mid_z = (WALK_Z[0] + WALK_Z[1]) // 2
    for a, b in cols:
        put((a + b) // 2, FLOOR, mid_z, GOLD)
    cx = W // 2
    for dx in range(-3, 4):
        for dz in range(-3, 4):
            d = abs(dx) + abs(dz)
            if d <= 3:
                put(cx + dx, FLOOR, mid_z + dz, GOLD if d == 3 else (GLOW if d == 0 else CAP))
    for x in range(FRAME + 2, W - FRAME, 7):
        for z in (WALK_Z[0], WALK_Z[1]):
            if abs(x - cx) > 3:
                put(x, FLOOR, z, GLOW)

    # Archways over the walkway: one at each end and one over the emblem.
    for ax in (FRAME + 4, cx, W - 1 - FRAME - 4):
        for z in (WALK_Z[0], WALK_Z[1]):
            put(ax, 2, z, CHISELED)
            for y in range(3, 8):
                put(ax, y, z, TILES)
            put(ax, 5, z, GOLD)
        for z in range(WALK_Z[0], WALK_Z[1] + 1):
            put(ax, 8, z, CAP)
        for z in range(WALK_Z[0] + 1, WALK_Z[1]):
            put(ax, 9, z, TILES)
        put(ax, 10, mid_z, GOLD)
        put(ax, 7, mid_z, HANGING)

    # Ceiling over everything.
    for x in range(W):
        for z in range(L):
            put(x, CEILING, z, BARRIER)
    return blocks


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def nbt_string(s):
    data = s.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def tag(type_id, name, payload):
    return bytes([type_id]) + nbt_string(name) + payload


def compound(entries):
    return b"".join(entries) + b"\x00"


def write(path):
    blocks = build()
    palette = {AIR: 0}
    data = bytearray()
    for y in range(H):
        for z in range(L):
            for x in range(W):
                block = blocks.get((x, y, z), AIR)
                if block not in palette:
                    palette[block] = len(palette)
                data += varint(palette[block])
    palette_tag = compound([tag(3, name, struct.pack(">i", index)) for name, index in palette.items()])
    metadata = compound([tag(3, "WEOffsetX", struct.pack(">i", 0)),
                         tag(3, "WEOffsetY", struct.pack(">i", 0)),
                         tag(3, "WEOffsetZ", struct.pack(">i", 0))])
    schematic = compound([
        tag(3, "Version", struct.pack(">i", 2)),
        tag(3, "DataVersion", struct.pack(">i", DATA_VERSION)),
        tag(2, "Width", struct.pack(">h", W)),
        tag(2, "Height", struct.pack(">h", H)),
        tag(2, "Length", struct.pack(">h", L)),
        tag(11, "Offset", struct.pack(">i", 3) + struct.pack(">iii", 0, 0, 0)),
        tag(3, "PaletteMax", struct.pack(">i", len(palette))),
        tag(10, "Palette", palette_tag),
        tag(7, "BlockData", struct.pack(">i", len(data)) + bytes(data)),
        tag(9, "BlockEntities", bytes([10]) + struct.pack(">i", 0)),
        tag(10, "Metadata", metadata),
    ])
    root = tag(10, "Schematic", schematic)
    with gzip.open(path, "wb") as out:
        out.write(root)
    print(f"{path}: {W} x {H} x {L} ({W * H * L} blocks), palette {len(palette)}")
    centers_x = [(a + b) // 2 for a, b in plot_columns()]
    print("plot centres (x, y, z), standing on the floor:")
    for z in ((ROW_A[0] + ROW_A[1]) // 2, (ROW_B[0] + ROW_B[1]) // 2):
        print("  " + "  ".join(f"({x}, {FLOOR + 1}, {z})" for x in centers_x))
    print(f"lobby (walkway emblem): ({W // 2}, {FLOOR + 1}, {(WALK_Z[0] + WALK_Z[1]) // 2})")
    return blocks


if __name__ == "__main__":
    write(sys.argv[1] if len(sys.argv) > 1 else "buildbattle.schem")
