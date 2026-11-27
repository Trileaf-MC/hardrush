#!/usr/bin/env python3
"""江南园林大厅包的生成器：建筑和功能位的唯一来源。

    python3 lobby-packs/chinese-garden/generate.py

写出 out/build.gmv1.json（上传到 genMC）和 build.hardrush.json（建筑清单，origin 由 scripts/genmc.py export 填）。
只用标准库，随机种子固定，同样的生成器产出同样的文件。坐标 x 东、y 上、z 南。

布局（南 → 北）：门厅与前院（抵达区）→ 月洞门 → 池园（怪物庭院：石拱桥、池心三怪亭、曲桥、展位回廊，
西岸藏珍阁、东岸百草庐）→ 八角门 → 戏台院（集结区），东侧竹林里是复生石坛。整座园子被粉墙围住。
"""
import json
import math
import random
from pathlib import Path

HERE = Path(__file__).resolve().parent
SEED = 20261127
SIZE = 96
G = 18          # 地面方块所在的高度
F = G + 1       # 玩家的脚所在的高度
AXIS = 48       # 南北中轴线

WALL_LO, WALL_HI = 4, 91        # 外墙所在的行列
MOON_Z, HALL_Z = 72, 32         # 月洞门墙、回廊后墙
POND_N, POND_S = 41, 66         # 池水南北的极限

WHITE, BASE = "minecraft:white_concrete", "minecraft:stone_bricks"
TILE = "minecraft:deepslate_tiles"
POST, BEAM, PLANK = "minecraft:stripped_mangrove_log[axis=y]", "minecraft:dark_oak_planks", "minecraft:mangrove_planks"
RAIL = "minecraft:mangrove_fence"
WATER = "minecraft:water[level=0]"
GRASS, MOSS, PODZOL = "minecraft:grass_block", "minecraft:moss_block", "minecraft:podzol"
ASSEMBLY_FLOOR = ("minecraft:red_wool", "minecraft:yellow_wool")   # 只用于集结区
ALTAR_FLOOR = "minecraft:gilded_blackstone"                         # 只用于祭坛踏板
SOILS = (GRASS, MOSS, PODZOL)


def tile_slab(kind="bottom"):
    return f"minecraft:deepslate_tile_slab[type={kind}]"


def eave_slab(kind="bottom"):
    """檐口最外一圈的滴水：比瓦面浅一号，勾出屋檐的轮廓。"""
    return f"minecraft:stone_brick_slab[type={kind}]"


def tile_stairs(facing, half="bottom"):
    return f"minecraft:deepslate_tile_stairs[facing={facing},half={half}]"


def stone_stairs(facing, half="bottom"):
    return f"minecraft:stone_brick_stairs[facing={facing},half={half}]"


def lattice(facing):
    """漏窗：竖起来的深色橡木活板门。"""
    return f"minecraft:dark_oak_trapdoor[facing={facing},half=bottom,open=true]"


def wall_sign(facing):
    return f"minecraft:mangrove_wall_sign[facing={facing}]"


def leaves(kind):
    return f"minecraft:{kind}_leaves[persistent=true]"


LANTERN, HANGING = "minecraft:lantern[hanging=false]", "minecraft:lantern[hanging=true]"


class Noise:
    """平滑的二维值噪声，0–1。"""

    def __init__(self, rng, cell):
        self.cell = cell
        n = SIZE // cell + 3
        self.grid = [[rng.random() for _ in range(n)] for _ in range(n)]

    def at(self, x, z):
        fx, fz = x / self.cell, z / self.cell
        tx, tz = fx - int(fx), fz - int(fz)
        n = len(self.grid) - 1
        ix, iz = int(fx) % n, int(fz) % n
        tx, tz = tx * tx * (3 - 2 * tx), tz * tz * (3 - 2 * tz)
        g = self.grid
        top = g[ix][iz] * (1 - tx) + g[ix + 1][iz] * tx
        bottom = g[ix][iz + 1] * (1 - tx) + g[ix + 1][iz + 1] * tx
        return top * (1 - tz) + bottom * tz


class Build:
    def __init__(self):
        self.blocks = {}   # (x, y, z) -> (方块, 部件)
        self.markers = []
        self.signs = []
        self.water = set()     # 池水的 (x, z)
        self.keep = set()      # 不种东西的 (x, z)：路、建筑、功能位

    def set(self, x, y, z, block, part):
        if not (0 <= x < SIZE and 0 <= z < SIZE and y >= 0):
            raise ValueError(f"{part} 的 {block} 放到了范围外：{(x, y, z)}")
        self.blocks[(x, y, z)] = (block, part)

    def put(self, x, y, z, block, part):
        """只放进空格子：植物不覆盖建筑。"""
        if (x, y, z) not in self.blocks and 0 <= x < SIZE and 0 <= z < SIZE:
            self.blocks[(x, y, z)] = (block, part)

    def fill(self, x0, y0, z0, x1, y1, z1, block, part):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    self.set(x, y, z, block, part)

    def clear(self, x, y, z):
        self.blocks.pop((x, y, z), None)

    def at(self, x, y, z):
        return self.blocks.get((x, y, z), ("", ""))[0]

    def solid(self, x, y, z):
        return (x, y, z) in self.blocks

    def reserve(self, x0, z0, x1, z1):
        self.keep.update((x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1))

    def marker(self, function, kind, at, **extra):
        self.markers.append({"function": function, "as": kind, "at": list(at), **extra})

    def standing(self, function, kind, at, yaw):
        """落点、模型、展位：at 是脚所在的空气格。"""
        x, y, z = at
        if not self.solid(x, y - 1, z) or self.solid(x, y, z) or self.solid(x, y + 1, z):
            raise ValueError(f"{function} 在 {at} 站不住")
        self.marker(function, kind, at, yaw=yaw)

    def bound(self, function, kind, at, block_prefix):
        """方块、点击、文字：at 是方块本身。"""
        if not self.at(*at).startswith(block_prefix):
            raise ValueError(f"{function} 在 {at} 应当是 {block_prefix}，实际是 {self.at(*at) or '空气'}")
        self.marker(function, kind, at)

    def text(self, at, front):
        if "sign" not in self.at(*at):
            raise ValueError(f"{at} 不是告示牌")
        self.signs.append({"at": list(at), "front": front, "back": []})

    def plaque(self, at, facing, front, part):
        """一块写了字的墙上告示牌（说明牌）：背后要有实心方块。"""
        self.set(*at, wall_sign(facing), part)
        self.text(at, front)

    def board(self, x, y, z, facing, front, part):
        """匾额：(x, y, z) 是额枋正下方那格，补一段三格宽的匾托，牌子挂在它朝外的一面。"""
        dx, dz = {"south": (0, 1), "north": (0, -1), "east": (1, 0), "west": (-1, 0)}[facing]
        for i in (-1, 0, 1):
            self.set(x + i * abs(dz), y, z + i * abs(dx), BEAM, part)
        self.plaque((x + dx, y, z + dz), facing, front, part)

    def verify(self):
        """后放的方块可能盖掉先放的功能位：收尾时再对一遍。"""
        for sign in self.signs:
            if "sign" not in self.at(*sign["at"]):
                raise ValueError(f"告示牌 {sign['at']} 被盖掉了，现在是 {self.at(*sign['at']) or '空气'}")
        for marker in self.markers:
            x, y, z = marker["at"]
            if marker["as"] in ("point", "model") and (not self.solid(x, y - 1, z) or self.solid(x, y, z) or self.solid(x, y + 1, z)):
                raise ValueError(f"{marker['function']} 在 {marker['at']} 站不住了")
            if marker["as"] in ("block", "click", "text") and not self.solid(x, y, z):
                raise ValueError(f"{marker['function']} 在 {marker['at']} 的方块没了")


# ---------------------------------------------------------------- 地形与水

def inset(x, z):
    """到园子外缘的距离：四角是圆的。"""
    dx, dz = max(0, 9 - x, x - 86), max(0, 9 - z, z - 86)
    return 9 - math.hypot(dx, dz) if dx or dz else min(x, z, SIZE - 1 - x, SIZE - 1 - z)


def terrain(b, rng, rough):
    """一整块浮在虚空里的园地：墙内是平的草地，墙外一圈碎石岸，底部向中间收成石根。"""
    stones = ["minecraft:stone", "minecraft:stone", "minecraft:andesite", "minecraft:tuff", "minecraft:cobblestone"]
    deep = ["minecraft:deepslate", "minecraft:deepslate", "minecraft:tuff", "minecraft:cobbled_deepslate"]
    for x in range(SIZE):
        for z in range(SIZE):
            d = inset(x, z) + 1.6 * (rough.at(x, z) - 0.5)
            inner = inset(x, z) >= 3.5
            if d < 0 and not inner:
                continue
            top = G if inner else G - int((3.5 - d) * 0.9 + 1.5 * rough.at(x + 30, z))
            depth = 3 + 11 * min(1, max(d, 0) / 18) ** 0.8 + 3 * rough.at(x, z + 30)
            bottom = max(1, G - int(depth))
            for y in range(bottom, top + 1):
                if y == top:
                    block = GRASS if inner or rng.random() < 0.55 else rng.choice(stones + ["minecraft:mossy_cobblestone"])
                elif y >= top - 2:
                    block = "minecraft:dirt"
                else:
                    block = rng.choice(deep if y < G - 8 else stones)
                b.set(x, y, z, block, "地形/园地" if inner else "地形/石岸")
            if inner and rough.at(x + 50, z + 50) > 0.68:
                b.set(x, G, z, MOSS, "地形/园地")


def in_pond(x, z, shape):
    if not POND_N <= z <= POND_S:
        return False
    if (43 <= x <= 60 and 41 <= z <= 47) or (44 <= x <= 52 and 61 <= z <= 66):   # 两座桥下面一定是水
        return True
    wobble = 0.22 * (shape.at(x, z) - 0.5)
    lobes = ((48, 54, 23, 12.5), (33, 50, 10, 9), (63, 57, 10, 8.5))
    return any(((x - cx) / rx) ** 2 + ((z - cz) / rz) ** 2 + wobble <= 1 for cx, cz, rx, rz in lobes)


def on_island(x, z):
    return 41 <= x <= 55 and 48 <= z <= 60 and not (abs(x - 48) == 7 and abs(z - 54) == 6)


def pond(b, rng, shape):
    """池塘：两格深的水，泥底，一圈石岸，池心留一座方岛。"""
    for x in range(SIZE):
        for z in range(SIZE):
            if in_pond(x, z, shape) and not on_island(x, z):
                b.water.add((x, z))
    for x, z in sorted(b.water):
        b.clear(x, G, z)
        b.set(x, G - 1, z, WATER, "水景/池水")
        b.set(x, G - 2, z, WATER, "水景/池水")
        b.set(x, G - 3, z, rng.choice(["minecraft:clay", "minecraft:clay", "minecraft:mud", "minecraft:coarse_dirt"]), "水景/池底")
        b.set(x, G - 4, z, "minecraft:stone", "水景/池底")
    rim = ["minecraft:stone", "minecraft:andesite", "minecraft:mossy_cobblestone", "minecraft:cobblestone", "minecraft:mossy_stone_bricks"]
    quiet = lambda x, z: (40 <= x <= 60 and z in (40, 67)) or 18 <= x <= 26 and 50 <= z <= 54 or 70 <= x <= 78 and 50 <= z <= 54
    for x, z in sorted(b.water):
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, -1), (1, -1), (-1, 1)):
            cell = (x + dx, z + dz)
            if cell in b.water or on_island(*cell):
                continue
            b.set(cell[0], G, cell[1], rng.choice(rim), "水景/石岸")
            b.keep.add(cell)
            if rng.random() < 0.13 and not quiet(*cell):     # 岸边零星的立石
                b.set(cell[0], F, cell[1], rng.choice(rim[:4]), "水景/石岸")
    for x in range(41, 56):
        for z in range(48, 61):
            if on_island(x, z):
                edge = x in (41, 55) or z in (48, 60) or not on_island(x - 1, z - 1) or not on_island(x + 1, z + 1) \
                    or not on_island(x - 1, z + 1) or not on_island(x + 1, z - 1)
                b.set(x, G, z, BASE if edge else "minecraft:polished_andesite", "水景/池心岛")
                for y in range(G - 3, G):
                    b.set(x, y, z, "minecraft:stone", "水景/池心岛")
                b.keep.add((x, z))


def shore_distance(b):
    """每个陆地格到池水的切比雪夫距离（只算 4 以内）。"""
    dist = {}
    frontier = set(b.water)
    seen = set(b.water)
    for step in range(1, 5):
        nxt = set()
        for x, z in frontier:
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    cell = (x + dx, z + dz)
                    if cell not in seen:
                        seen.add(cell)
                        dist[cell] = step
                        nxt.add(cell)
        frontier = nxt
    return dist


def pave(b, rng, x0, z0, x1, z1, part, court=False):
    mix = ["minecraft:stone_bricks"] * 5 + ["minecraft:polished_andesite"] * 2 + ["minecraft:cracked_stone_bricks", "minecraft:andesite"] \
        if court else ["minecraft:andesite"] * 3 + ["minecraft:stone_bricks"] * 2 + ["minecraft:gravel", "minecraft:cobblestone", "minecraft:polished_andesite"]
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if (x, z) in b.water or b.at(x, G, z) not in SOILS:
                continue
            b.set(x, G, z, rng.choice(mix), part)
            b.keep.add((x, z))


def paths(b, rng):
    dist = shore_distance(b)
    for (x, z), d in sorted(dist.items()):       # 沿池岸的一圈园路
        if d in (2, 3) and 6 <= x <= 89 and 38 <= z <= 69 and not on_island(x, z):
            pave(b, rng, x, z, x, z, "园路/环池路")
    pave(b, rng, 34, 73, 62, 83, "抵达区/前院铺地", court=True)
    pave(b, rng, 20, 80, 33, 82, "园路/书斋小径")
    pave(b, rng, 63, 79, 73, 81, "园路/茶亭小径")
    pave(b, rng, 42, 67, 54, 71, "庭院/月洞门内铺地", court=True)
    pave(b, rng, 18, 51, 26, 53, "园路/藏珍阁小径")
    pave(b, rng, 70, 51, 78, 53, "园路/百草庐小径")
    pave(b, rng, 46, 27, 50, 31, "园路/集结院甬道", court=True)
    pave(b, rng, 59, 21, 71, 23, "园路/石坛小径")
    pave(b, rng, 26, 38, 70, 39, "园路/廊前路")


# ---------------------------------------------------------------- 粉墙

def wall(b, cells, along, part, top=F + 3, windows=(), skip=()):
    """一道粉墙黛瓦：石墙基、白墙身、两坡瓦顶。cells 是 (x, z) 列表，along 是墙的走向（x 或 z）。"""
    for x, z in cells:
        if (x, z) in skip:
            continue
        b.set(x, F, z, BASE, f"{part}/墙基")
        b.fill(x, F + 1, z, x, top, z, WHITE, f"{part}/墙身")
        b.set(x, top + 1, z, TILE, f"{part}/瓦顶")
        b.set(x, top + 2, z, tile_slab(), f"{part}/瓦顶")
        sides = ((0, -1, "south"), (0, 1, "north")) if along == "x" else ((-1, 0, "east"), (1, 0, "west"))
        for dx, dz, facing in sides:
            if not b.solid(x + dx, top + 1, z + dz):
                b.set(x + dx, top + 1, z + dz, tile_stairs(facing), f"{part}/瓦顶")
        b.keep.add((x, z))
        if (x, z) in windows:
            for y in (F + 2, F + 3):
                b.set(x, y, z, lattice("south" if along == "x" else "east"), f"{part}/漏窗")


def window_cells(cells, start, step=7):
    """沿墙每隔几格开一扇三格宽的漏窗。"""
    out = set()
    for i in range(start, len(cells) - 3, step):
        out.update(cells[i:i + 3])
    return out


def round_gate(b, part):
    """月洞门：墙在门两侧升高，门洞是直径五格的圆。"""
    cells = [(x, MOON_Z) for x in range(41, 56)]
    wall(b, cells, "x", part, top=F + 8)
    hole = {(x, y) for x in range(43, 54) for y in range(F, F + 8) if (x - AXIS) ** 2 + (y - F - 3) ** 2 <= 12.25}
    ring = set()
    for x, y in hole:
        b.clear(x, y, MOON_Z)
        ring.update((x + dx, y + dy) for dx in (-1, 0, 1) for dy in (-1, 0, 1) if (x + dx, y + dy) not in hole and y + dy >= F)
    for x, y in ring:
        side, up = (1 if x > AXIS else -1), (1 if y > F + 3 else -1)
        if (x - side, y) in hole and (x, y - up) in hole:    # 台阶状的拐角用楼梯削圆
            block = f"minecraft:polished_andesite_stairs[facing={'east' if side > 0 else 'west'},half={'top' if up > 0 else 'bottom'}]"
        else:
            block = "minecraft:polished_andesite"
        b.set(x, y, MOON_Z, block, f"{part}/门框")
    b.reserve(44, MOON_Z - 2, 52, MOON_Z + 2)


def walls(b):
    lo, hi = WALL_LO, WALL_HI
    ring = [(x, lo) for x in range(lo, hi + 1)], [(x, hi) for x in range(lo, hi + 1)]
    for cells in ring:
        wall(b, cells, "x", "围墙", top=F + 4)
    for x in (lo, hi):
        wall(b, [(x, z) for z in range(lo + 1, hi)], "z", "围墙", top=F + 4)
    west = [(x, MOON_Z) for x in range(lo + 1, 41)]
    east = [(x, MOON_Z) for x in range(56, hi)]
    wall(b, west, "x", "月洞门墙", windows=window_cells(west, 3))
    wall(b, east, "x", "月洞门墙", windows=window_cells(east, 3))
    round_gate(b, "月洞门墙/月洞门")
    west = [(x, HALL_Z) for x in range(lo + 1, 25)]
    east = [(x, HALL_Z) for x in range(72, hi)]
    wall(b, west, "x", "回廊院墙", windows=window_cells(west, 2, 6))
    wall(b, east, "x", "回廊院墙", windows=window_cells(east, 2, 6))


# ---------------------------------------------------------------- 屋顶与木构

def roof(b, x0, z0, x1, z1, y, part, rings=None):
    """四坡瓦顶：(x0, z0)–(x1, z1) 是檐口外沿，y 是檐口（也是额枋）的高度。
    檐口两圈是半砖，往里每圈升一格；四角起翘。rings 只铺外面几圈（重檐的下檐）。"""
    i = 0
    while x0 + i <= x1 - i and z0 + i <= z1 - i and (rings is None or i < rings):
        a0, c0, a1, c1 = x0 + i, z0 + i, x1 - i, z1 - i
        yy = y if i < 2 else y + i - 1
        if min(a1 - a0, c1 - c0) <= 1:
            ridge(b, a0, c0, a1, c1, yy, part)
            return
        for x in range(a0, a1 + 1):
            for z in range(c0, c1 + 1):
                if x not in (a0, a1) and z not in (c0, c1):
                    continue
                corner_gap = min(abs(x - a0), abs(x - a1)) + min(abs(z - c0), abs(z - c1))
                if i == 0:
                    block = eave_slab("top" if corner_gap <= 1 else "bottom")
                elif i == 1:
                    block = tile_slab("top")
                else:
                    block = tile_stairs("south" if z == c0 else "north" if z == c1 else "east" if x == a0 else "west")
                b.set(x, yy, z, block, part)
        if i == 0:
            for x, z in ((a0 - 1, c0 - 1), (a1 + 1, c0 - 1), (a0 - 1, c1 + 1), (a1 + 1, c1 + 1)):
                if 0 <= x < SIZE and 0 <= z < SIZE:
                    b.set(x, yy + 1, z, eave_slab(), f"{part}/翘角")
            for x, z in ((a0, c0), (a1, c0), (a0, c1), (a1, c1)):
                b.set(x, yy + 1, z, eave_slab(), f"{part}/翘角")
        i += 1


def ridge(b, a0, c0, a1, c1, y, part):
    part = f"{part}/屋脊"
    if a1 - a0 <= 1 and c1 - c0 <= 1:       # 攒尖：宝顶
        b.fill(a0, y, c0, a1, y, c1, TILE, part)
        if a0 == a1 and c0 == c1:
            b.set(a0, y + 1, c0, "minecraft:chiseled_deepslate", part)
            b.set(a0, y + 2, c0, "minecraft:deepslate_brick_wall", part)
        return
    b.fill(a0, y, c0, a1, y, c1, TILE, part)
    along_x = a1 - a0 > c1 - c0
    if along_x:
        for z in range(c0, c1 + 1):
            b.fill(a0, y + 1, z, a1, y + 1, z, tile_slab(), part)
            b.set(a0, y + 1, z, tile_stairs("west"), part)
            b.set(a1, y + 1, z, tile_stairs("east"), part)
    else:
        for x in range(a0, a1 + 1):
            b.fill(x, y + 1, c0, x, y + 1, c1, tile_slab(), part)
            b.set(x, y + 1, c0, tile_stairs("north"), part)
            b.set(x, y + 1, c1, tile_stairs("south"), part)


def frame(b, x0, z0, x1, z1, top, posts, part, floor="minecraft:smooth_stone"):
    """木构架：地面、立柱（posts 是 (x, z) 列表，立到 top - 1）、一圈额枋（在 top）。"""
    b.fill(x0, G, z0, x1, G, z1, floor, f"{part}/地面")
    b.reserve(x0 - 1, z0 - 1, x1 + 1, z1 + 1)
    for x, z in posts:
        b.fill(x, F, z, x, top - 1, z, POST, f"{part}/立柱")
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if x in (x0, x1) or z in (z0, z1):
                b.set(x, top, z, BEAM, f"{part}/额枋")


def panel(b, cells, top, part, block=WHITE, base=BASE):
    """柱间的墙：一格墙基，上面是墙身，一直顶到额枋下。"""
    for x, z in cells:
        b.set(x, F, z, base, part)
        b.fill(x, F + 1, z, x, top - 1, z, block, part)


def stone_lamp(b, x, z, part="照明/石灯"):
    b.set(x, F, z, "minecraft:stone_brick_wall", part)
    b.set(x, F + 1, z, LANTERN, part)
    b.set(x, F + 2, z, "minecraft:stone_brick_slab[type=bottom]", part)
    b.keep.add((x, z))


# ---------------------------------------------------------------- 抵达区

def gate_hall(b):
    """门厅：背靠南墙的正门（门是关着的，玩家就出现在门内）。"""
    part = "抵达区/门厅"
    x0, x1, z0, z1, top = 41, 55, 84, 90, F + 4
    frame(b, x0, z0, x1, z1, top, [(x, z0) for x in (41, 45, 51, 55)] + [(41, z1), (55, z1)], part, floor="minecraft:polished_andesite")
    for x in (x0, x1):
        panel(b, [(x, z) for z in range(z0 + 1, z1 + 1)], top, f"{part}/山墙")
        for y in (F + 2, F + 3):
            b.set(x, y, 87, lattice("east"), f"{part}/山墙")
    # 假的大门：嵌在南墙里的朱漆门板和门钉。
    for x in range(46, 51):
        for y in range(F, F + 4):
            frame_cell = x in (46, 50) or y == F + 3
            b.set(x, y, WALL_HI, "minecraft:dark_oak_planks" if frame_cell else PLANK, f"{part}/大门")
    for x in (47, 49):                                # 门环
        b.set(x, F + 1, WALL_HI - 1, "minecraft:polished_blackstone_button[face=wall,facing=north]", f"{part}/大门")
    b.plaque((48, F + 3, WALL_HI - 1), "north", ["", "速 园", "", ""], f"{part}/匾额")
    for x in (43, 48, 53):
        b.set(x, top - 1, z0, HANGING, f"{part}/灯笼")
    roof(b, x0 - 2, z0 - 2, x1 + 2, z1 + 3, top, f"{part}/屋顶")
    for x in (46, 50):
        for z in (86, 88):
            b.standing("landing_central", "point", (x, F, z), 180)


def study(b):
    """书斋：朝东敞开，两座讲台是规则和战绩。"""
    part = "抵达区/书斋"
    x0, x1, z0, z1, top = 10, 18, 75, 87, F + 4
    frame(b, x0, z0, x1, z1, top, [(x1, z) for z in (75, 78, 84, 87)] + [(x0, z0), (x0, z1)], part, floor="minecraft:spruce_planks")
    panel(b, [(x0, z) for z in range(z0 + 1, z1)], top, f"{part}/后墙")
    for z in (z0, z1):
        panel(b, [(x, z) for x in range(x0 + 1, x1)], top, f"{part}/山墙")
        for x in (13, 14, 15):
            for y in (F + 2, F + 3):
                b.set(x, y, z, lattice("south"), f"{part}/山墙")
    for z in range(z0 + 1, z1):                      # 一面墙的书
        for y in range(F, top):
            b.set(x0 + 1, y, z, "minecraft:bookshelf", f"{part}/书架")
    for z, function, label in ((79, "rules", "规则"), (83, "history", "战绩")):
        b.set(15, F, z, "minecraft:lectern[facing=east]", f"{part}/{label}讲台")
        b.bound(function, "click", (15, F, z), "minecraft:lectern")
        sign_z = z - 1 if function == "rules" else z + 1
        b.set(15, F, sign_z, "minecraft:mangrove_sign[rotation=12]", f"{part}/{label}讲台")
        b.text((15, F, sign_z), [label, "右键翻阅"])
    # 书案和坐凳
    b.set(13, F, 81, "minecraft:mangrove_slab[type=top]", f"{part}/书案")
    b.set(14, F, 81, "minecraft:mangrove_slab[type=top]", f"{part}/书案")
    b.set(13, F + 1, 81, "minecraft:lantern[hanging=false]", f"{part}/书案")
    b.set(12, F, 81, "minecraft:dark_oak_stairs[facing=west,half=bottom]", f"{part}/书案")
    for z in (76, 86):
        b.set(x1, top - 1, z, HANGING, f"{part}/灯笼")
    b.board(x1, top - 1, 81, "east", ["", "书 斋", "规则与战绩", ""], f"{part}/匾额")
    roof(b, x0 - 2, z0 - 2, x1 + 2, z1 + 2, top, f"{part}/屋顶")


def tea_pavilion(b):
    """前院东侧的小方亭：石桌和美人靠。"""
    part = "抵达区/茶亭"
    x0, x1, z0, z1, top = 74, 78, 78, 82, F + 3
    frame(b, x0, z0, x1, z1, top, [(x0, z0), (x1, z0), (x0, z1), (x1, z1)], part)
    for x in range(x0 + 1, x1):
        b.set(x, F, z0, "minecraft:mangrove_stairs[facing=north,half=bottom]", f"{part}/美人靠")
        b.set(x, F, z1, "minecraft:mangrove_stairs[facing=south,half=bottom]", f"{part}/美人靠")
    for z in range(z0 + 1, z1):
        b.set(x1, F, z, "minecraft:mangrove_stairs[facing=east,half=bottom]", f"{part}/美人靠")
    b.set(76, F, 80, "minecraft:polished_andesite", f"{part}/石桌")
    b.set(76, F + 1, 80, "minecraft:potted_bamboo", f"{part}/石桌")
    roof(b, x0 - 2, z0 - 2, x1 + 2, z1 + 2, top, f"{part}/屋顶")


def arrival(b):
    gate_hall(b)
    study(b)
    tea_pavilion(b)
    for x, function in ((43, "status"), (53, "credits")):
        b.set(x, F + 1, MOON_Z + 1, wall_sign("south"), "抵达区/月洞门告示")
        b.bound(function, "text", (x, F + 1, MOON_Z + 1), "minecraft:mangrove_wall_sign")
    for x in (40, 56):
        stone_lamp(b, x, 75)
        stone_lamp(b, x, 82)


# ---------------------------------------------------------------- 怪物庭院

def arch_bridge(b):
    """石拱桥：从月洞门内的铺地跨到池心岛。"""
    part = "庭院/石拱桥"
    profile = {66: (F, "north"), 65: (F + 1, "north"), 64: (F + 1, None), 63: (F + 1, None), 62: (F + 1, "south"), 61: (F, "south")}
    for z, (level, facing) in profile.items():
        for x in range(46, 51):
            edge = x in (46, 50)
            if z in (66, 61):
                b.fill(x, G - 2, z, x, G, z, BASE, f"{part}/桥墩")
            elif z in (65, 62):
                b.set(x, G, z, stone_stairs("south" if z == 65 else "north", "top"), f"{part}/拱券")
                b.set(x, F, z, BASE, f"{part}/拱券")
            b.set(x, level, z, BASE if edge or not facing else stone_stairs(facing), f"{part}/桥面")
            if edge:
                b.set(x, level + 1, z, "minecraft:stone_brick_wall", f"{part}/栏杆")
    for x in (45, 51):
        stone_lamp(b, x, 68, f"{part}/桥头石灯")


def zigzag_bridge(b):
    """曲桥：贴着水面的木栈道，从池心岛折向东北上岸。"""
    part = "庭院/曲桥"
    deck = {(x, z) for x in range(46, 51) for z in range(46, 48)} | {(x, z) for x in range(46, 58) for z in range(42, 46)} \
        | {(x, 41) for x in range(53, 58)}
    for x, z in sorted(deck):
        b.set(x, G, z, "minecraft:spruce_planks", f"{part}/桥面")
        open_water = any((x + dx, z + dz) in b.water and (x + dx, z + dz) not in deck
                         for dx in (-1, 0, 1) for dz in (-1, 0, 1))
        if open_water:
            b.set(x, F, z, "minecraft:spruce_fence", f"{part}/栏杆")
            if (x + z) % 3 == 0:
                b.fill(x, G - 2, z, x, G - 1, z, "minecraft:spruce_log[axis=y]", f"{part}/桥桩")


def role_hall(b):
    """池心的三怪亭：过了石拱桥迎面就是三座神龛，模型面朝来路。"""
    part = "庭院/三怪亭"
    x0, x1, z0, z1, top = 43, 53, 51, 57, F + 4
    posts = [(x, z) for x in (x0, x1) for z in (51, 54, 57)] + [(46, z1), (50, z1)]
    frame(b, x0, z0, x1, z1, top, posts, part)
    panel(b, [(x, z0) for x in range(x0 + 1, x1)], top, f"{part}/屏风", block=PLANK)
    for x in (46, 50):                                # 屏风上分隔三龛的立柱
        b.fill(x, F, z0, x, top - 1, z0, "minecraft:dark_oak_planks", f"{part}/屏风")
    for x, function, name in ((45, "role_zombie", "僵尸"), (48, "role_skeleton", "骷髅"), (51, "role_creeper", "苦力怕")):
        shrine = f"{part}/{name}神龛"
        b.set(x, F, z0 + 1, "minecraft:chiseled_stone_bricks", shrine)
        for dx in (-1, 1):
            b.set(x + dx, F, z0 + 1, "minecraft:red_candle[candles=3,lit=true]", shrine)
        b.plaque((x, F, z0 + 2), "south", [name, "右键模型选角", "挑战时右键", "目标展位追猎"], shrine)
        b.standing(function, "model", (x, F + 1, z0 + 1), 0)
    for x in (44, 52):
        b.set(x, top - 1, z1, HANGING, f"{part}/灯笼")
    b.fill(x0 + 1, top, z0 + 1, x1 - 1, top, z0 + 1, BEAM, f"{part}/额枋")      # 神龛上方的横枋，挂灯用
    for x in (47, 49):
        b.set(x, top - 1, z0 + 1, HANGING, f"{part}/灯笼")
    b.board(48, top - 1, z1, "south", ["", "三 怪 亭", "选一个怪物", ""], f"{part}/匾额")
    roof(b, x0 - 2, z0 - 2, x1 + 2, z1 + 2, top, f"{part}/屋顶")


SLOTS_X = (27, 30, 33, 36, 39, 42, 54, 57, 60, 63, 66, 69)


def gallery(b):
    """展位回廊：池北的长廊，后墙前一排石座是追猎目标展位，正中的八角门通往戏台院。"""
    part = "庭院/展位回廊"
    x0, x1, top = 26, 70, F + 3
    b.fill(x0, G, HALL_Z + 1, x1, G, 37, "minecraft:smooth_stone", f"{part}/地面")
    b.reserve(x0 - 1, HALL_Z - 2, x1 + 1, 39)
    for x in range(x0 - 2, x1 + 3):                    # 后墙
        b.set(x, F, HALL_Z, BASE, f"{part}/后墙")
        b.fill(x, F + 1, HALL_Z, x, top, HALL_Z, WHITE, f"{part}/后墙")
    for x in range(46, 51):                            # 八角门
        for y in range(F, F + 4):
            if abs(x - AXIS) <= (1 if y in (F, F + 3) else 2):
                b.clear(x, y, HALL_Z)
    for x in range(x0, x1 + 1, 4):
        b.fill(x, F, 37, x, top - 1, 37, POST, f"{part}/立柱")
    b.fill(x0, top, 37, x1, top, 37, BEAM, f"{part}/额枋")
    open_bays = (26, 38, 46, 54, 66)
    for x in range(x0, x1, 4):
        if x in open_bays:
            continue
        for i in (1, 2, 3):
            b.set(x + i, F, 37, RAIL, f"{part}/栏杆")
    for x in range(x0 + 2, x1, 4):
        b.set(x, top - 1, 37, HANGING, f"{part}/灯笼")
    # 两坡瓦顶，脊沿东西向。
    rows = {39: eave_slab(), 38: tile_slab("top"), 31: tile_slab("top"), 30: eave_slab()}
    for x in range(x0 - 1, x1 + 2):
        for z, block in rows.items():
            b.set(x, top, z, block, f"{part}/屋顶")
        for z, y, facing in ((37, 1, "north"), (36, 2, "north"), (35, 3, "north"), (34, 3, "south"), (33, 2, "south"), (32, 1, "south")):
            b.set(x, top + y, z, tile_stairs(facing), f"{part}/屋顶")
    # 展位
    for x in SLOTS_X:
        b.set(x, F, HALL_Z + 1, "minecraft:polished_andesite", f"{part}/目标石座")
        b.set(x, F + 2, HALL_Z, lattice("south"), f"{part}/漏窗")
        b.standing("display_slot", "point", (x, F + 1, HALL_Z + 1), 0)
    for x, function, label in ((44, "page_prev", "上一页"), (52, "page_next", "下一页")):
        b.set(x, F, HALL_Z + 1, "minecraft:lectern[facing=south]", f"{part}/翻页讲台")
        b.bound(function, "click", (x, F, HALL_Z + 1), "minecraft:lectern")
        b.plaque((x, F + 2, HALL_Z + 1), "south", [label, "右键翻页", "只对自己生效"], f"{part}/翻页讲台")


def treasure_tower(b):
    """藏珍阁：池西的两层楼阁，重檐攒尖，底层放两种宝库。"""
    part = "庭院/藏珍阁"
    x0, x1, z0, z1 = 9, 17, 48, 56
    mid, top = F + 5, F + 10
    b.fill(x0, G, z0, x1, G, z1, "minecraft:polished_andesite", f"{part}/地面")
    b.reserve(x0 - 2, z0 - 2, x1 + 2, z1 + 2)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if x not in (x0, x1) and z not in (z0, z1):
                continue
            corner = x in (x0, x1) and z in (z0, z1)
            for y in range(F, top):
                block = POST if corner else BEAM if y == mid else BASE if y == F else WHITE
                b.set(x, y, z, block, f"{part}/楼身")
            b.set(x, top, z, BEAM, f"{part}/额枋")
    b.fill(x0 + 1, mid, z0 + 1, x1 - 1, mid, z1 - 1, "minecraft:dark_oak_planks", f"{part}/楼板")
    for z in (51, 52, 53):                              # 朝东的门
        for y in range(F, F + 3):
            b.clear(x1, y, z)
    b.fill(x1, F + 3, 51, x1, F + 3, 53, BEAM, f"{part}/门楣")
    for y in (mid + 2, mid + 3):                        # 二层四面的花窗
        for i in (-1, 0, 1):
            b.set(x0, y, 52 + i, lattice("east"), f"{part}/花窗")
            b.set(x1, y, 52 + i, lattice("west"), f"{part}/花窗")
            b.set(13 + i, y, z0, lattice("south"), f"{part}/花窗")
            b.set(13 + i, y, z1, lattice("north"), f"{part}/花窗")
    for y in (F + 2, F + 3):
        b.set(13, y, z0, lattice("south"), f"{part}/花窗")
        b.set(13, y, z1, lattice("north"), f"{part}/花窗")
    roof(b, x0 - 3, z0 - 3, x1 + 3, z1 + 3, mid, f"{part}/下檐", rings=3)
    roof(b, x0 - 2, z0 - 2, x1 + 2, z1 + 2, top, f"{part}/上檐")
    # 宝库
    for z, function, ominous in ((50, "vault", "false"), (54, "ominous_vault", "true")):
        b.set(11, F, z, f"minecraft:vault[facing=east,ominous={ominous}]", f"{part}/宝库")
        b.bound(function, "block", (11, F, z), "minecraft:vault")
    b.set(11, F, 52, "minecraft:chiseled_stone_bricks", f"{part}/宝库")
    b.set(11, F + 1, 52, LANTERN, f"{part}/宝库")
    for z in (49, 55):
        b.set(15, mid - 1, z, HANGING, f"{part}/灯笼")
    b.plaque((x1 + 1, F + 3, 52), "east", ["", "藏 珍 阁", "试炼与不祥宝库", ""], f"{part}/匾额")
    for z in (50, 54):
        stone_lamp(b, x1 + 2, z)


def herb_hut(b):
    """百草庐：池东女巫的药铺，朝西敞开。"""
    part = "庭院/百草庐"
    x0, x1, z0, z1, top = 79, 87, 48, 56, F + 4
    frame(b, x0, z0, x1, z1, top, [(x0, z) for z in (48, 50, 54, 56)] + [(x1, z0), (x1, z1)], part, floor="minecraft:spruce_planks")
    panel(b, [(x1, z) for z in range(z0 + 1, z1)], top, f"{part}/墙", block="minecraft:spruce_planks")
    for z in (z0, z1):
        panel(b, [(x, z) for x in range(x0 + 1, x1)], top, f"{part}/墙", block=WHITE)
        for x in (82, 83, 84):
            for y in (F + 2, F + 3):
                b.set(x, y, z, lattice("south"), f"{part}/墙")
    b.set(84, F, 52, "minecraft:chiseled_stone_bricks", f"{part}/女巫石座")
    b.plaque((83, F, 52), "west", ["女巫商人", "右键交易"], f"{part}/女巫石座")
    b.standing("merchant", "model", (84, F + 1, 52), 90)
    for z in (49, 55):
        b.set(85, F, z, "minecraft:water_cauldron[level=3]", f"{part}/药锅")
    pots = ["minecraft:potted_red_mushroom", "minecraft:potted_fern", "minecraft:potted_allium", "minecraft:potted_brown_mushroom",
            "minecraft:potted_dead_bush", "minecraft:potted_azure_bluet", "minecraft:potted_bamboo"]
    for i, z in enumerate(range(z0 + 1, z1)):           # 后墙的药架
        b.set(x1 - 1, F + 1, z, "minecraft:spruce_slab[type=top]", f"{part}/药架")
        b.set(x1 - 1, F + 2, z, pots[i % len(pots)], f"{part}/药架")
    for z in (49, 55):
        b.set(x0, top - 1, z, HANGING, f"{part}/灯笼")
    b.board(x0, top - 1, 52, "west", ["", "百 草 庐", "女巫的药铺", ""], f"{part}/匾额")
    roof(b, x0 - 2, z0 - 2, x1 + 2, z1 + 2, top, f"{part}/屋顶")


def rockery(b, rng, rough):
    """假山：池西北角水里的一座太湖石山，顶上一株矮松。"""
    part = "水景/假山"
    cx, cz = 32, 46
    stones = ["minecraft:stone", "minecraft:andesite", "minecraft:andesite", "minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:tuff"]
    peak = None
    for x in range(cx - 6, cx + 7):
        for z in range(cz - 5, cz + 6):
            d = math.hypot((x - cx) / 5.5, (z - cz) / 4.5)
            if d >= 1 or (x, z) not in b.water:
                continue
            height = int(7.5 * (1 - d) ** 0.8 * (0.55 + 0.9 * rough.at(x * 3, z * 3)))
            if height <= 0:
                continue
            for y in range(G - 2, G + height):
                if y > G and rng.random() < 0.1 and height > 3:
                    continue                                # 太湖石的孔洞
                b.set(x, y, z, rng.choice(stones), part)
            if peak is None or height > peak[0]:
                peak = (height, x, z)
    height, x, z = peak
    b.set(x, G + height - 1, z, "minecraft:stone", part)
    pine(b, rng, x, G + height, z, "水景/假山/矮松", height=3)


def courtyard(b, rng, rough):
    arch_bridge(b)
    zigzag_bridge(b)
    role_hall(b)
    gallery(b)
    treasure_tower(b)
    herb_hut(b)
    rockery(b, rng, rough)
    for x in (47, 49):
        b.standing("landing_monster", "point", (x, F, 69), 180)
    for x, z in ((43, 49), (53, 49), (24, 39), (72, 39), (20, 66), (76, 66)):
        stone_lamp(b, x, z)
    # 庭院区域：两道院墙之间的整座池园。
    b.marker("courtyard", "zone", (AXIS, F, 52), shape="box", box=[42, 22, 19])


# ---------------------------------------------------------------- 集结区与祭坛

def stage(b):
    """戏台：集结区的背景。台前那片红毡就是集结区。"""
    part = "集结区/戏台"
    x0, x1, z0, z1, top = 40, 56, 7, 13, F + 5
    b.fill(x0, G, z0, x1, G, z1, BASE, f"{part}/台基")
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            rim = x in (x0, x1) or z in (z0, z1)
            b.set(x, F, z, BASE if rim else PLANK, f"{part}/台基")
    b.reserve(x0 - 2, z0 - 2, x1 + 2, z1 + 3)
    posts = [(x, z) for x in (40, 45, 51, 56) for z in (z0, z1)]
    for x, z in posts:
        b.fill(x, F + 1, z, x, top - 1, z, POST, f"{part}/立柱")
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if x in (x0, x1) or z in (z0, z1):
                b.set(x, top, z, BEAM, f"{part}/额枋")
    for x in range(x0 + 1, x1):                         # 后台的屏风和红幕
        for y in range(F + 1, top):
            curtain = 44 <= x <= 52 and y >= F + 2
            b.set(x, y, z0, "minecraft:red_terracotta" if curtain else PLANK, f"{part}/屏风")
    for x in (44, 52):
        b.fill(x, F + 1, z0, x, top - 1, z0, "minecraft:dark_oak_planks", f"{part}/屏风")
    for x in (42, 54):
        b.set(x, top - 1, z1, HANGING, f"{part}/灯笼")
    for x, facing in ((x0 - 1, "east"), (x1 + 1, "west")):      # 两侧上台的石阶
        for z in (10, 11):
            b.set(x, F, z, stone_stairs(facing), f"{part}/石阶")
    b.board(48, top - 1, z1, "south", ["", "集 结", "站上红毡即准备", ""], f"{part}/匾额")
    roof(b, x0 - 2, z0 - 2, x1 + 2, z1 + 2, top, f"{part}/屋顶")


def assembly(b):
    part = "集结区/红毡"
    x0, x1, z0, z1 = 39, 57, 17, 26
    b.fill(x0 - 1, G, z0 - 1, x1 + 1, G, z1 + 1, "minecraft:smooth_stone", f"{part}/石边")
    red, yellow = ASSEMBLY_FLOOR
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            motif = abs(x - AXIS) + abs(z - 21.5) * 1.0 <= 2.5 or (x in (x0 + 1, x1 - 1) and z in (z0 + 1, z1 - 1))
            b.set(x, G, z, yellow if motif else red, part)
    b.reserve(x0 - 2, z0 - 2, x1 + 2, z1 + 2)
    b.marker("assembly", "zone", (x0, G, z0), shape="flood", materials=list(ASSEMBLY_FLOOR))
    for x in (x0 - 2, x1 + 2):
        stone_lamp(b, x, z0 - 1)
        stone_lamp(b, x, z1 + 1)


def altar(b):
    """复生石坛：竹林里的圆形石坛，坛心的遮光玻璃是光柱。"""
    part = "祭坛/石坛"
    cx, cz = 77, 22
    for x in range(cx - 5, cx + 6):
        for z in range(cz - 5, cz + 6):
            d = math.hypot(x - cx, z - cz)
            if d <= 5.2:
                b.set(x, G, z, "minecraft:polished_andesite", f"{part}/坛基")
                b.keep.add((x, z))
            if d <= 4.3:
                b.set(x, F, z, "minecraft:smooth_stone_slab[type=bottom]", f"{part}/下层")
            if d <= 2.9:
                b.set(x, F, z, "minecraft:smooth_stone", f"{part}/上层")
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            b.set(cx + dx, F, cz + dz, ALTAR_FLOOR if dx or dz else "minecraft:polished_blackstone", f"{part}/踏板")
    for i in (1, 2, 3):
        b.set(cx, F + i, cz, "minecraft:tinted_glass", f"{part}/光柱")
        b.bound("altar_light", "block", (cx, F + i, cz), "minecraft:tinted_glass")
    b.set(cx, F + 4, cz, "minecraft:soul_lantern[hanging=false]", f"{part}/光柱")
    for dx, dz in ((3, 3), (-3, 3), (3, -3), (-3, -3)):
        b.set(cx + dx, F, cz + dz, "minecraft:polished_blackstone_wall", f"{part}/魂灯")
        b.set(cx + dx, F + 1, cz + dz, "minecraft:soul_lantern[hanging=false]", f"{part}/魂灯")
    b.marker("altar_pad", "zone", (cx - 1, F, cz), shape="flood", materials=[ALTAR_FLOOR])
    b.standing("landing_altar", "point", (cx - 8, F, cz), -90)
    b.set(cx - 6, F, cz - 2, "minecraft:mangrove_sign[rotation=4]", f"{part}/告示")
    b.text((cx - 6, F, cz - 2), ["复生石坛", "站上坛心", "为同伴复生"])


# ---------------------------------------------------------------- 植物

def free(b, x, z):
    return (x, z) not in b.keep and (x, z) not in b.water and b.at(x, G, z) in SOILS and not b.solid(x, F, z)


def pine(b, rng, x, y, z, part, height=6):
    """迎客松：斜出的几层平顶松枝。"""
    b.fill(x, y, z, x, y + height - 1, z, "minecraft:spruce_log[axis=y]", part)
    layers = [(height - 1, 0, 0, 2.3), (height - 3, rng.choice((-2, 2)), rng.choice((-1, 1)), 1.9)]
    if height >= 6:
        layers.append((height - 5, rng.choice((-2, 2)), rng.choice((-2, 2)), 1.6))
    for dy, ox, oz, r in layers:
        for dx in range(-3, 4):
            for dz in range(-3, 4):
                if math.hypot(dx, dz) <= r + 0.3 * rng.random():
                    b.put(x + ox + dx, y + dy, z + oz + dz, leaves("spruce"), part)
                    if math.hypot(dx, dz) <= r - 1.2:
                        b.put(x + ox + dx, y + dy + 1, z + oz + dz, leaves("spruce"), part)
        if ox or oz:                                    # 伸出去的枝干
            b.put(x + (ox > 0) - (ox < 0), y + dy, z, "minecraft:spruce_log[axis=x]", part)


def willow(b, rng, x, z, part):
    """垂柳：树冠边缘垂下长长的枝条。"""
    height = rng.randint(5, 6)
    b.fill(x, F, z, x, F + height - 1, z, "minecraft:oak_log[axis=y]", part)
    top = F + height
    for dx in range(-4, 5):
        for dz in range(-4, 5):
            d = math.hypot(dx, dz)
            for dy, radius in ((0, 3.5), (1, 2.7), (2, 1.6)):      # 圆顶的树冠
                if d <= radius + 0.5 * rng.random() - 0.25:
                    b.put(x + dx, top + dy, z + dz, leaves("oak"), part)
            if 2.5 < d <= 3.9 and rng.random() < 0.45:      # 垂枝
                for dy in range(1, 1 + rng.randint(1, 4)):
                    b.put(x + dx, top - dy, z + dz, leaves("oak"), part)


def blossom(b, rng, x, z, part):
    """花树：矮而开张的一株樱（当梅花和海棠看）。"""
    height = rng.randint(3, 4)
    b.fill(x, F, z, x, F + height - 1, z, "minecraft:cherry_log[axis=y]", part)
    top = F + height
    for dx in range(-3, 4):
        for dz in range(-3, 4):
            for dy in range(-1, 2):
                if math.hypot(dx, dz, dy * 1.6) <= 2.7 + 0.5 * rng.random():
                    b.put(x + dx, top + dy, z + dz, leaves("cherry"), part)
    for dx, dz in ((2, 0), (-2, 0), (0, 2), (0, -2)):
        if rng.random() < 0.5 and free(b, x + dx, z + dz):
            b.put(x + dx, F, z + dz, "minecraft:pink_petals[facing=north,flower_amount=3]", part)


def bamboo(b, rng, x, z, part):
    if b.at(x, G, z) == GRASS:
        b.set(x, G, z, PODZOL, "地形/园地")
    height = rng.randint(5, 10)
    age = rng.randint(0, 1)
    while any(b.solid(x, F + i, z) for i in range(height)) and height > 0:     # 竹子不能断节：头顶有东西就矮一截
        height -= 1
    for i in range(height):
        kind = "large" if i >= height - 2 else "small" if i >= height - 4 else "none"
        b.set(x, F + i, z, f"minecraft:bamboo[age={age},leaves={kind},stage=1]", part)


def plants(b, rng, rough):
    trees = {
        willow: [(24, 63), (73, 63), (19, 44), (77, 45), (34, 70), (62, 70)],
        pine: [(13, 11), (23, 20), (10, 25), (30, 10), (64, 87), (12, 66), (86, 64), (29, 77)],
        blossom: [(84, 76), (68, 87), (66, 75), (27, 88), (82, 40), (10, 40), (20, 27), (63, 12), (33, 20), (62, 27), (8, 60), (88, 60)],
    }
    names = {willow: "垂柳", pine: "松", blossom: "花树"}
    for grow, spots in trees.items():
        for i, (x, z) in enumerate(spots):
            spot = next(((x + dx, z + dz) for r in range(5) for dx in range(-r, r + 1) for dz in range(-r, r + 1)
                         if free(b, x + dx, z + dz)), None)
            if not spot:
                print(f"跳过{names[grow]} {(x, z)}：附近没有空地")
                continue
            x, z = spot
            part = f"自然/{names[grow]}/{i + 1}"
            if grow is pine:
                pine(b, rng, x, F, z, part, height=rng.randint(6, 8))
            else:
                grow(b, rng, x, z, part)
            b.reserve(x - 1, z - 1, x + 1, z + 1)
    groves = {"石坛竹林": (62, 6, 90, 31, 0.4), "东南角竹丛": (81, 83, 90, 90, 0.4), "庭院东北竹丛": (77, 34, 90, 43, 0.35),
              "庭院西南竹丛": (5, 61, 13, 71, 0.35), "书斋后竹丛": (5, 73, 8, 90, 0.4), "西北角竹丛": (5, 5, 9, 16, 0.35),
              "庭院东南竹丛": (80, 66, 90, 71, 0.35), "庭院西北竹丛": (5, 34, 12, 42, 0.3)}
    for name, (x0, z0, x1, z1, density) in groves.items():
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if free(b, x, z) and rng.random() < density * (0.4 + rough.at(x * 2, z * 2)):
                    bamboo(b, rng, x, z, f"自然/竹/{name}")
    rocks = [(18, 14), (26, 12), (16, 22), (70, 76), (86, 80), (30, 86), (64, 68), (33, 70), (84, 45), (8, 46), (68, 14), (28, 24)]
    stones = ["minecraft:andesite", "minecraft:stone", "minecraft:mossy_cobblestone", "minecraft:cobblestone"]
    for i, (x, z) in enumerate(rocks):                   # 点景的石头
        for dx, dz, h in ((0, 0, 2), (1, 0, 1), (0, 1, 1), (-1, 0, rng.randint(0, 1)), (1, 1, rng.randint(0, 2))):
            if free(b, x + dx, z + dz):
                for y in range(F, F + h + (dx == dz == 0 and rng.random() < 0.5)):
                    b.set(x + dx, y, z + dz, rng.choice(stones), f"自然/景石/{i + 1}")
                b.keep.add((x + dx, z + dz))
    flowers = ["minecraft:azure_bluet", "minecraft:oxeye_daisy", "minecraft:allium", "minecraft:pink_petals[facing=east,flower_amount=4]",
               "minecraft:pink_petals[facing=south,flower_amount=2]", "minecraft:lily_of_the_valley"]
    for x in range(WALL_LO + 1, WALL_HI):
        for z in range(WALL_LO + 1, WALL_HI):
            if not free(b, x, z):
                continue
            roll = rng.random()
            if roll < 0.10:
                b.put(x, F, z, "minecraft:short_grass", "自然/花草")
            elif roll < 0.125:
                b.put(x, F, z, "minecraft:fern", "自然/花草")
            elif roll < 0.155:
                b.put(x, F, z, rng.choice(flowers), "自然/花草")
            elif roll < 0.17:
                b.put(x, F, z, rng.choice(["minecraft:azalea", "minecraft:flowering_azalea"]), "自然/花草")
    under = lambda x, z: any(b.solid(x, y, z) for y in range(G, G + 4))
    for x, z in sorted(b.water):                         # 睡莲和荷叶
        if under(x, z):
            continue
        roll = rng.random()
        if roll < 0.07:
            b.set(x, G, z, "minecraft:lily_pad", "水景/睡莲")
        elif roll < 0.11 and rough.at(x * 2, z * 2) > 0.5:
            b.set(x, G - 2, z, "minecraft:big_dripleaf_stem[facing=north,waterlogged=true]", "水景/荷叶")
            b.set(x, G - 1, z, "minecraft:big_dripleaf_stem[facing=north,waterlogged=true]", "水景/荷叶")
            b.set(x, G, z, "minecraft:big_dripleaf[facing=north,tilt=none,waterlogged=false]", "水景/荷叶")


# ---------------------------------------------------------------- 输出

def runs(b):
    palette, parts, out = {}, {}, []
    previous = None
    for (z, y, x), (block, part) in sorted(((z, y, x), v) for (x, y, z), v in b.blocks.items()):
        key = (palette.setdefault(block, len(palette)), parts.setdefault(part, len(parts)))
        if previous and previous[0] == (z, y, x - 1) and previous[1] == key:
            out[-1][3] += 1
        else:
            out.append([x, y, z, 1, key[0], key[1]])
        previous = ((z, y, x), key)
    return {"format": "gmv1", "palette": list(palette), "parts": list(parts), "runs": out}


def main():
    rng = random.Random(SEED)
    shape, rough = Noise(rng, 7), Noise(rng, 4)
    b = Build()
    terrain(b, rng, rough)
    pond(b, rng, shape)
    paths(b, rng)
    walls(b)
    arrival(b)
    courtyard(b, rng, rough)
    stage(b)
    assembly(b)
    altar(b)
    plants(b, rng, rough)
    b.verify()

    gmv1 = runs(b)
    out = HERE / "out"
    out.mkdir(exist_ok=True)
    (out / "build.gmv1.json").write_text(json.dumps(gmv1, ensure_ascii=False, separators=(",", ":")))
    manifest_path = HERE / "build.hardrush.json"
    manifest = {"format": 1, "update_shapes": True, "markers": b.markers, "signs": b.signs}
    origin = json.loads(manifest_path.read_text()).get("origin") if manifest_path.exists() else None
    lines = ["{"] + [f'  "{k}": {json.dumps(v)},' for k, v in manifest.items() if k not in ("markers", "signs")]
    for key in ("markers", "signs"):
        rows = ",\n".join("    " + json.dumps(row, ensure_ascii=False) for row in manifest[key])
        lines.append(f'  "{key}": [\n{rows}\n  ]' + ("," if key == "markers" or origin else ""))
    if origin:      # 由 scripts/genmc.py export 填在末尾，重新生成时原样留着
        lines.append(f'  "origin": {json.dumps(origin)}')
    manifest_path.write_text("\n".join(lines + ["}"]) + "\n")
    ys = [y for _, y, _ in b.blocks]
    print(f"方块 {len(b.blocks)}，高度 {min(ys)}–{max(ys)}，run {len(gmv1['runs'])}，部件 {len(gmv1['parts'])}，"
          f"标记 {len(b.markers)}，告示牌 {len(b.signs)}")


if __name__ == "__main__":
    main()
