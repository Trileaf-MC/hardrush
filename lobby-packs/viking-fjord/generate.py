#!/usr/bin/env python3
"""北欧维京大厅包的生成器：建筑和功能位的唯一来源。

    python3 lobby-packs/viking-fjord/generate.py

写出 out/build.gmv1.json（上传到 genMC）和 build.hardrush.json（建筑清单，origin 由 scripts/genmc.py export 填）。
只用标准库，随机种子固定，同样的生成器产出同样的文件。坐标 x 东、y 上、z 南。
生成器内部的 z 从 ZMIN（-8）起算，写文件时整体加 8：报告里 `manifest` 坐标的 z 减 8 才是这里的 z。

布局（南 → 北逐级升高，主轴 x = 46）：
  峡湾（南）：龙首长船靠在木栈码头边，玩家落在船甲板上（中央落点），走跳板上码头。
  岸边：卢恩石和两座讲台（规则、战绩），石上挂状态牌，东边小卢恩石挂署名牌。
  长屋大厅（中）：草皮屋顶的长屋整座是怪物庭院。西侧长凳上是三座选角神龛和女巫（völva）商人，
    东侧长凳是一排挂着圆盾的追猎目标展位，两端讲台翻页；北端高台上两只宝库守着后门。
  篝火广场（北）：中间是篝火，围着篝火的一圈干草地面是集结区。
  山坡（东北）：石阵围着一圈紫水晶踏板，中间的信标顶着遮光玻璃，这是复生祭坛和它的光柱。
四周：东边是带雪线的高山，峡湾对岸是雪顶石壁，西边和北边是云杉林，边缘敞开（掉下去会被送回）。
"""
import json
import math
import random
from pathlib import Path

HERE = Path(__file__).resolve().parent
SEED = 20261128
SIZE = 96            # 东西宽度
ZMIN, ZMAX = -8, 104  # 南北范围（生成器内部坐标；输出时整体平移到 z ≥ 0）
AXIS = 46            # 主动线的中线 x

WATER_Y = 16         # 水面方块的 y
SHORE_Y = 18         # 码头甲板和岸边广场的地面方块
HALL_Y = 21          # 大厅地板方块
DAIS_Y = 22          # 大厅长凳、北端高台的顶面方块
PLAZA_Y = 23         # 篝火广场地面方块
ALTAR_Y = 27         # 祭坛台地地面方块
SNOW_LINE = 39

# 大厅：墙在 x = 38 / 54，z = 25 / 55；内部 x 39..53，z 26..54。
HALL_X0, HALL_X1, HALL_Z0, HALL_Z1 = 38, 54, 25, 55
PLAZA = (46, 12)     # 篝火广场中心
ALTAR = (70, 12)     # 石阵中心

ASSEMBLY_FLOOR = "minecraft:hay_block[axis=y]"   # 只用于集结区
ALTAR_FLOOR = "minecraft:amethyst_block"         # 只用于祭坛踏板

GRASS, DIRT, STONE = "minecraft:grass_block", "minecraft:dirt", "minecraft:stone"
SPRUCE_PLANKS, DARK_PLANKS = "minecraft:spruce_planks", "minecraft:dark_oak_planks"
COBBLE, MOSSY = "minecraft:cobblestone", "minecraft:mossy_cobblestone"
LEAVES = "minecraft:spruce_leaves[persistent=true]"
WATER = "minecraft:water"


def log(kind="spruce", axis="y"):
    return f"minecraft:{kind}_log[axis={axis}]"


def stairs(kind, facing, half="bottom"):
    return f"minecraft:{kind}_stairs[facing={facing},half={half}]"


def wall_sign(facing):
    return f"minecraft:spruce_wall_sign[facing={facing}]"


class Noise:
    """平滑的二维值噪声，0–1，格点按坐标取确定的随机数。"""

    def __init__(self, salt, cell):
        self.salt, self.cell, self.cache = salt, cell, {}

    def corner(self, ix, iz):
        key = (ix, iz)
        if key not in self.cache:
            self.cache[key] = random.Random(SEED * 1000003 + self.salt * 7919 + ix * 92821 + iz * 68917).random()
        return self.cache[key]

    def at(self, x, z):
        fx, fz = x / self.cell, z / self.cell
        ix, iz = math.floor(fx), math.floor(fz)
        tx, tz = fx - ix, fz - iz
        tx, tz = tx * tx * (3 - 2 * tx), tz * tz * (3 - 2 * tz)
        a, b = self.corner(ix, iz), self.corner(ix + 1, iz)
        c, d = self.corner(ix, iz + 1), self.corner(ix + 1, iz + 1)
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz


BROAD, DETAIL, SHORE_N, RIDGE_N, ROCK = Noise(1, 18), Noise(2, 6), Noise(3, 11), Noise(4, 9), Noise(5, 4)


class Build:
    def __init__(self):
        self.blocks = {}   # (x, y, z) -> (方块, 部件)
        self.markers = []
        self.signs = []
        self.ground = {}   # (x, z) -> 地形顶面方块的 y（无地形的列不在其中）
        self.reserved = set()  # 不长树、不放石头的列

    def set(self, x, y, z, block, part):
        if not (0 <= x < SIZE and ZMIN <= z < ZMAX and y >= 0):
            raise ValueError(f"{part} 的 {block} 放到了范围外：{(x, y, z)}")
        self.blocks[(x, y, z)] = (block, part)

    def fill(self, x0, y0, z0, x1, y1, z1, block, part):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.set(x, y, z, block, part)

    def clear(self, x, y, z):
        self.blocks.pop((x, y, z), None)

    def get(self, x, y, z):
        return self.blocks.get((x, y, z), ("", ""))[0]

    def solid(self, x, y, z):
        block = self.get(x, y, z)
        return bool(block) and block != WATER

    def reserve(self, x0, z0, x1, z1):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                self.reserved.add((x, z))

    def marker(self, function, kind, at, **extra):
        self.markers.append({"function": function, "as": kind, "at": list(at), **extra})

    def standing(self, function, kind, at, yaw):
        """落点、模型、展位：at 是脚所在的空气格。"""
        x, y, z = at
        if not self.solid(x, y - 1, z) or self.get(x, y, z) or self.get(x, y + 1, z):
            raise ValueError(f"{function} 在 {at} 站不住：脚下 {self.get(x, y - 1, z) or '空气'}，"
                             f"身体 {self.get(x, y, z) or '空气'} / {self.get(x, y + 1, z) or '空气'}")
        self.marker(function, kind, at, yaw=yaw)

    def bound(self, function, kind, at, block_prefix):
        """方块、点击、文字：at 是方块本身。"""
        block = self.get(*at)
        if not block.startswith(block_prefix):
            raise ValueError(f"{function} 在 {at} 应当是 {block_prefix}，实际是 {block or '空气'}")
        self.marker(function, kind, at)

    def text(self, at, front):
        if "sign" not in self.get(*at):
            raise ValueError(f"{at} 不是告示牌")
        self.signs.append({"at": list(at), "front": front, "back": []})


# ---------------------------------------------------------------- 地形

def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def shoreline(x):
    """岸线的 z：再往南是峡湾。"""
    return 63 + 4 * (SHORE_N.at(x, 0) - 0.5)


def raw_height(x, z):
    n, m = BROAD.at(x, z), DETAIL.at(x, z)
    shore = shoreline(x)
    inland = shore - z
    if inland >= 0:
        h = 17.2 + min(inland * 0.55, 3.2) + (n - 0.5) * 2.4 + max(0, 34 - z) * 0.06
    else:
        h = max(16.4 + inland * 0.9, 10.5 + 2 * m)
    # 峡湾对岸：陡起的石壁
    ridge = 98 + 3 * (RIDGE_N.at(x, 0) - 0.5)
    if z > ridge - 4:
        h = max(h, 15 + (z - (ridge - 4)) * 2.7 + 6 * (m - 0.5))
    # 东边的高山，北段更高
    east = x - 64 - 8 * (n - 0.5)
    if east > 0:
        rise = min(east * 1.35 * (0.75 + 0.5 * m) + max(0, 40 - z) * 0.12 * min(1, east / 8), 34)
        crag = (ROCK.at(x * 1.3, z * 1.3) - 0.5) * 7 * min(1, east / 10)
        h += (rise + crag) * (1 - 0.8 * smooth((x - 83) / 12))
    # 北边的山丘
    if z < 3:
        h += (3 - z) * 1.25 * (0.6 + 0.8 * m)
    # 峡湾西口的礁岸，把水围住
    if x < 5 and z > shore - 1:
        h = max(h, 16.6 + (5 - x) * 0.7 + 3 * m)
    if h > 50:
        h = 50 + (h - 50) * 0.35
    # 靠近地图边缘的高地往下收，边缘的断面不至于是一堵高墙
    e = min(x, z - ZMIN, SIZE - 1 - x, (ZMAX - 1 - z) * 1.6)
    if h > 22 and e < 11:
        h = 22 + (h - 22) * (0.3 + 0.7 * smooth(e / 11))
    return h


def flat(h, target, dist, blend):
    return target + (h - target) * smooth(dist / blend)


def rect_dist(x, z, x0, z0, x1, z1):
    dx = max(x0 - x, 0, x - x1)
    dz = max(z0 - z, 0, z - z1)
    return math.hypot(dx, dz)


def circle_dist(x, z, cx, cz, r):
    return max(0.0, math.hypot(x - cx, z - cz) - r)


FLATS = [
    # (距离函数, 目标高度, 过渡宽度)
    (lambda x, z: rect_dist(x, z, HALL_X0 - 3, HALL_Z0 - 3, HALL_X1 + 3, HALL_Z1 + 2), HALL_Y - 1, 6),
    (lambda x, z: circle_dist(x, z, *PLAZA, 9.6), PLAZA_Y, 5),
    (lambda x, z: rect_dist(x, z, 43, 21, 49, 24), DAIS_Y, 2),
    (lambda x, z: rect_dist(x, z, 37, 57, 55, 62), SHORE_Y, 3),
    (lambda x, z: circle_dist(x, z, *ALTAR, 6.5), ALTAR_Y, 4),
    (lambda x, z: rect_dist(x, z, 55, 10, 64, 14), 25, 2),
    (lambda x, z: rect_dist(x, z, 20, 29, 32, 45), 20, 4),     # 西屋
    (lambda x, z: rect_dist(x, z, 17, 49, 29, 57), 19, 3),     # 铁匠铺
    (lambda x, z: rect_dist(x, z, 25, 5, 35, 15), 23, 3),      # 仓房
    (lambda x, z: rect_dist(x, z, 58, 33, 68, 47), 22, 4),     # 东屋
    (lambda x, z: rect_dist(x, z, 58, 50, 70, 58), 21, 3),     # 麦田
]

# 主动线上的台阶：列 -> 地面方块 y；随后在 STEPS 处放楼梯
PATH_HEIGHTS = {}
STEPS = []   # (x, y, z, 朝向, 材质)


def plan_paths():
    for x in range(44, 49):
        for z, h in ((56, 20), (57, 20), (58, 19), (59, 19), (60, 18), (61, 18), (62, 18)):
            PATH_HEIGHTS[(x, z)] = h
        STEPS.append((x, HALL_Y, 56, "north", "spruce"))
        STEPS.append((x, 20, 58, "north", "cobblestone"))
        STEPS.append((x, 19, 60, "north", "cobblestone"))
        for z in (22, 23, 24):
            PATH_HEIGHTS[(x, z)] = DAIS_Y
        STEPS.append((x, PLAZA_Y, 22, "north", "stone_brick"))
    levels = [(56, 23), (57, 23), (58, 24), (59, 24), (60, 25), (61, 25), (62, 26), (63, 26), (64, 27)]
    for x, h in levels:
        for z in (11, 12, 13):
            PATH_HEIGHTS[(x, z)] = h
    for x, h in ((57, 24), (59, 25), (61, 26), (63, 27)):
        for z in (11, 12, 13):
            STEPS.append((x, h, z, "east", "mossy_stone_brick"))


def height(x, z):
    if (x, z) in PATH_HEIGHTS:
        return PATH_HEIGHTS[(x, z)]
    h = raw_height(x, z)
    for dist, target, blend in FLATS:
        d = dist(x, z)
        if d < blend:
            h = flat(h, target, d, blend)
    return int(round(h))


def edge_dropped(x, z, h):
    """北、西、东三面边缘参差掉进虚空；南边是对岸石壁，保持完整。"""
    e = min(x, z - ZMIN, SIZE - 1 - x)
    if e >= 3 or h <= WATER_Y:
        return False
    return ROCK.at(x * 1.7, z * 1.7) < (3 - e) / 3 * 0.75


def terrain(b):
    heights = {}
    for x in range(SIZE):
        for z in range(ZMIN, ZMAX):
            heights[(x, z)] = height(x, z)

    def steep(x, z):
        h = heights[(x, z)]
        return max(abs(h - heights.get((x + dx, z + dz), h)) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))

    for (x, z), h in heights.items():
        if edge_dropped(x, z, h):
            continue
        e = min(x, z - ZMIN, SIZE - 1 - x, ZMAX - 1 - z)
        bottom = 9 + int(2 * ROCK.at(x, z)) + max(0, 4 - e) * 2
        bottom = min(bottom, h - 2)
        r = DETAIL.at(x + 31, z + 17)
        s = steep(x, z)
        for y in range(bottom, h + 1):
            depth = h - y
            if h <= WATER_Y + 1 and depth <= 2:
                block = "minecraft:gravel" if r > 0.35 or h < WATER_Y else "minecraft:sand"
            elif depth == 0:
                if h >= SNOW_LINE + 2 + int(3 * r):
                    block = "minecraft:snow_block"
                elif s >= 3 or h >= SNOW_LINE + int(4 * r) - 2:
                    block = STONE if r > 0.4 else "minecraft:andesite"
                elif r > 0.78:
                    block = "minecraft:podzol"
                elif r < 0.12:
                    block = "minecraft:coarse_dirt"
                else:
                    block = GRASS
            elif depth <= 2 and s < 3 and h < SNOW_LINE:
                block = DIRT
            else:
                block = "minecraft:andesite" if ROCK.at(x + y * 3, z - y * 2) > 0.7 else STONE
            b.set(x, y, z, block, "自然/地形")
        b.ground[(x, z)] = h
        if h < WATER_Y:
            for y in range(h + 1, WATER_Y + 1):
                b.set(x, y, z, WATER, "自然/峡湾")
        # 雪线附近的积雪薄层
        if SNOW_LINE - 2 <= h and b.get(x, h, z) != "minecraft:snow_block" and s < 3:
            layers = 1 + min(3, max(0, h - SNOW_LINE + 2))
            b.set(x, h + 1, z, f"minecraft:snow[layers={layers}]", "自然/积雪")


def seal_water(b):
    """水旁边不能是空气，否则导入后会流进虚空。贴着空气的水改成礁石。"""
    for (x, y, z), (block, _) in list(b.blocks.items()):
        if block != WATER:
            continue
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, -1, 0)):
            nx, ny, nz = x + dx, y + dy, z + dz
            if not (0 <= nx < SIZE and ZMIN <= nz < ZMAX) or (nx, ny, nz) not in b.blocks:
                b.set(x, y, z, MOSSY if ROCK.at(x, z) > 0.5 else STONE, "自然/礁石")
                break


# ---------------------------------------------------------------- 长屋（通用）

def longhouse(b, x0, z0, x1, z1, floor_y, ridge, part, wall_h=3, doors=(), turf_rng=None, smoke=()):
    """草皮屋顶的长屋。墙在矩形边上；ridge 为 'z' 时屋脊沿 z。墙高 wall_h（地板之上），屋面 1:1 阶梯。"""
    rng = turf_rng or random.Random(SEED + x0 * 31 + z0)
    if ridge == "z":
        across0, across1, along0, along1 = x0, x1, z0, z1
    else:
        across0, across1, along0, along1 = z0, z1, x0, x1
    if (across1 - across0) % 2:
        raise ValueError(f"{part} 的宽度应为奇数")
    center = (across0 + across1) // 2
    half = (across1 - across0) // 2 + 1
    top = floor_y + wall_h

    def at(a, u, y, block, sub):
        x, z = (a, u) if ridge == "z" else (u, a)
        b.set(x, y, z, block, f"{part}/{sub}")

    # 地基和地板
    for a in range(across0, across1 + 1):
        for u in range(along0, along1 + 1):
            edge = a in (across0, across1) or u in (along0, along1)
            ground = b.ground.get((a, u) if ridge == "z" else (u, a), floor_y - 1)
            for y in range(min(ground, floor_y - 1), floor_y):
                at(a, u, y, COBBLE, "地基")
            at(a, u, floor_y, COBBLE if edge else SPRUCE_PLANKS, "地板")
    # 墙
    log_across = "x" if ridge == "z" else "z"
    for a in range(across0, across1 + 1):
        for u in range(along0, along1 + 1):
            if not (a in (across0, across1) or u in (along0, along1)):
                continue
            corner = a in (across0, across1) and u in (along0, along1)
            post = corner or (a in (across0, across1) and (u - along0) % 4 == 0)
            for y in range(floor_y + 1, top + 1):
                if post:
                    block = log("dark_oak")
                elif y == floor_y + 1:
                    block = MOSSY if rng.random() < 0.35 else COBBLE
                elif y == top and u in (along0, along1):
                    block = log("stripped_spruce", log_across)
                else:
                    block = SPRUCE_PLANKS
                at(a, u, y, block, "墙")
    # 屋面
    for u in range(along0 - 1, along1 + 2):
        for a in range(center - half, center + half + 1):
            d = abs(a - center)
            y = top + half - d
            if u == along0 - 1 or u == along1 + 1:
                at(a, u, y, DARK_PLANKS, "屋面/山墙板")
                continue
            if d == 0 and u in smoke:
                continue
            turf = "minecraft:moss_block" if rng.random() < 0.6 else GRASS
            at(a, u, y, turf, "屋面/草皮")
            if d < half:
                at(a, u, y - 1, SPRUCE_PLANKS, "屋面/望板")
            r = rng.random()
            if r < 0.10:
                at(a, u, y + 1, "minecraft:short_grass", "屋面/草")
            elif r < 0.14:
                at(a, u, y + 1, "minecraft:fern", "屋面/草")
            elif r < 0.155:
                at(a, u, y + 1, rng.choice(["minecraft:dandelion", "minecraft:oxeye_daisy", "minecraft:cornflower"]), "屋面/草")
    # 山墙
    for u in (along0, along1):
        for a in range(center - half + 2, center + half - 1):
            d = abs(a - center)
            for y in range(top + 1, top + half - d - 1):
                block = log("stripped_spruce", log_across) if (y - top) % 3 == 0 else SPRUCE_PLANKS
                if d == 0:
                    block = log("dark_oak")
                at(a, u, y, block, "山墙")
    # 山墙顶交叉的龙头板
    for u in (along0 - 1, along1 + 1):
        ridge_y = top + half
        for k in (1, 2):
            at(center - k, u, ridge_y + k - 1 + 1, "minecraft:dark_oak_fence", "屋面/龙头板")
            at(center + k, u, ridge_y + k - 1 + 1, "minecraft:dark_oak_fence", "屋面/龙头板")
        at(center, u, ridge_y + 1, DARK_PLANKS, "屋面/龙头板")
    # 门洞
    for (a, u, height_) in doors:
        for y in range(floor_y + 1, floor_y + 1 + height_):
            x, z = (a, u) if ridge == "z" else (u, a)
            b.clear(x, y, z)
    return top, half, center


def spruce_door(b, x, y, z, facing, part, hinge="left"):
    b.set(x, y, z, f"minecraft:spruce_door[facing={facing},half=lower,hinge={hinge},open=false]", part)
    b.set(x, y + 1, z, f"minecraft:spruce_door[facing={facing},half=upper,hinge={hinge},open=false]", part)


def lamp_post(b, x, y, z, part, height_=2):
    """云杉栅栏柱顶着灯笼。y 是柱子最低一格。"""
    for i in range(height_):
        b.set(x, y + i, z, "minecraft:spruce_fence", part)
    b.set(x, y + height_, z, "minecraft:lantern[hanging=false]", part)


# ---------------------------------------------------------------- 大厅 = 怪物庭院

def hall(b):
    part = "庭院/长屋大厅"
    top, half, center = longhouse(b, HALL_X0, HALL_Z0, HALL_X1, HALL_Z1, HALL_Y, "z", part, wall_h=4,
                                  doors=[(x, HALL_Z1, 3) for x in (45, 46, 47)], smoke=(36, 42))
    assert center == AXIS
    # 北门在高台上：门洞从高台顶面往上三格
    for x in (45, 46, 47):
        for y in (DAIS_Y + 1, DAIS_Y + 2, DAIS_Y + 3):
            b.clear(x, y, HALL_Z0)
        b.set(x, DAIS_Y, HALL_Z0, SPRUCE_PLANKS, f"{part}/门槛")
    for z in (HALL_Z0, HALL_Z1):
        for x in (44, 48):
            for y in range(HALL_Y + 1, HALL_Y + 6):
                b.set(x, y, z, log("dark_oak"), f"{part}/门框")
        for x in (45, 46, 47):
            b.set(x, HALL_Y + 4 if z == HALL_Z1 else DAIS_Y + 4, z, log("stripped_dark_oak", "x"), f"{part}/门框")
    # 地板：过道铺云杉木板，中间是长火塘
    for z in range(26, 55):
        for x in range(39, 54):
            b.set(x, HALL_Y, z, SPRUCE_PLANKS if (x + z) % 7 else "minecraft:stripped_spruce_wood[axis=y]", f"{part}/地板")
    for z in range(33, 48):
        for x in (45, 46, 47):
            b.set(x, HALL_Y, z, "minecraft:stone_bricks" if x != 46 else COBBLE, f"{part}/火塘")
    for z in (35, 38, 41, 44):
        b.set(46, HALL_Y + 1, z, "minecraft:campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]", f"{part}/火塘")
    # 北端高台（宝库）和两侧长凳
    b.fill(39, DAIS_Y, 26, 53, DAIS_Y, 28, "minecraft:stripped_spruce_wood[axis=y]", f"{part}/高台")
    for x in range(42, 51):
        b.set(x, DAIS_Y, 29, stairs("spruce", "north"), f"{part}/高台")
    for side in ((39, 40, 41), (51, 52, 53)):
        for x in side:
            for z in range(29, 55):
                b.set(x, DAIS_Y, z, "minecraft:stripped_spruce_wood[axis=y]" if x in (41, 51) else SPRUCE_PLANKS, f"{part}/长凳")
    # 内墙：齐肩高一圈横放的剥皮原木，长凳前的过道铺几块毛皮地毯
    for z in range(26, 55):
        for x in (HALL_X0, HALL_X1):
            if (z - HALL_Z0) % 4:
                b.set(x, DAIS_Y + 2, z, log("stripped_spruce", "z"), f"{part}/墙")
    for x in range(39, 54):
        if not 44 <= x <= 48:
            b.set(x, DAIS_Y + 2, HALL_Z1, log("stripped_spruce", "x"), f"{part}/墙")
    for x0, z0, color in ((42, 35, "brown"), (48, 47, "brown"), (42, 46, "white"), (48, 36, "light_gray")):
        for x in range(x0, x0 + 3):
            for z in range(z0, z0 + 3):
                b.set(x, HALL_Y + 1, z, f"minecraft:{color}_carpet", f"{part}/毛皮")
    # 立柱、横梁、吊灯
    for z in (34, 40, 46):
        for x in (41, 51):
            for y in range(DAIS_Y + 1, top + 1):
                b.set(x, y, z, log("dark_oak"), f"{part}/立柱")
        for x in range(39, 54):
            b.set(x, top + 1, z, log("stripped_dark_oak", "x"), f"{part}/横梁")
        for x in (43, 49):
            b.set(x, top, z, "minecraft:lantern[hanging=true]", f"{part}/吊灯")
    # 南门、北门外的灯
    for x in (43, 49):
        lamp_post(b, x, HALL_Y, HALL_Z1 + 1, f"{part}/门灯")

    # 选角神龛：西侧长凳上，石台上方站模型，墙上挂对应的头颅
    booths = [("role_zombie", "僵尸", 49, "zombie"), ("role_skeleton", "骷髅", 43, "skeleton"),
              ("role_creeper", "苦力怕", 37, "creeper")]
    for function, name, z, head in booths:
        b.set(40, DAIS_Y + 1, z, "minecraft:chiseled_stone_bricks", f"庭院/选角/{name}神龛")
        b.set(39, DAIS_Y + 1, z - 1, "minecraft:candle[candles=3,lit=true,waterlogged=false]", f"庭院/选角/{name}神龛")
        b.set(39, DAIS_Y + 1, z + 1, "minecraft:candle[candles=2,lit=true,waterlogged=false]", f"庭院/选角/{name}神龛")
        b.set(39, top, z, f"minecraft:{head}_wall_head[facing=east,powered=false]" if head != "skeleton"
              else "minecraft:skeleton_wall_skull[facing=east,powered=false]", f"庭院/选角/{name}神龛")
        b.standing(function, "model", (40, DAIS_Y + 2, z), -90)
        b.set(41, DAIS_Y + 1, z, wall_sign("east"), f"庭院/选角/{name}神龛")
        b.text((41, DAIS_Y + 1, z), [name, "右键模型选角", "挑战时右键", "目标展位追猎"])
    # 女巫（völva）商人
    z = 31
    b.set(40, DAIS_Y + 1, z, "minecraft:mossy_stone_bricks", "庭院/女巫商人")
    b.set(39, DAIS_Y + 1, z - 1, "minecraft:water_cauldron[level=3]", "庭院/女巫商人")
    b.set(39, DAIS_Y + 1, z + 1, "minecraft:potted_fern", "庭院/女巫商人")
    b.set(40, DAIS_Y + 1, z + 1, "minecraft:candle[candles=4,lit=true,waterlogged=false]", "庭院/女巫商人")
    b.standing("merchant", "model", (40, DAIS_Y + 2, z), -90)
    b.set(41, DAIS_Y + 1, z, wall_sign("east"), "庭院/女巫商人")
    b.text((41, DAIS_Y + 1, z), ["女巫商人", "右键交易"])

    # 追猎目标展位：东侧长凳，每个展位背后的墙上挂一面圆盾
    colors = ["red", "yellow", "blue", "white", "black", "green"]
    for i, z in enumerate(range(31, 52, 2)):
        b.set(52, DAIS_Y, z, "minecraft:polished_andesite", "庭院/展位/基座")
        b.set(53, DAIS_Y + 2, z, f"minecraft:{colors[i % len(colors)]}_wall_banner[facing=west]", "庭院/展位/圆盾")
        b.standing("display_slot", "point", (52, DAIS_Y + 1, z), 90)
    for function, label, z in (("page_prev", "上一页", 29), ("page_next", "下一页", 53)):
        b.set(52, DAIS_Y + 1, z, "minecraft:lectern[facing=west,has_book=false,powered=false]", "庭院/展位/翻页")
        b.bound(function, "click", (52, DAIS_Y + 1, z), "minecraft:lectern")
        b.set(53, DAIS_Y + 2, z, wall_sign("west"), "庭院/展位/翻页")
        b.text((53, DAIS_Y + 2, z), [label, "右键翻页", "只对自己生效"])

    # 北端高台上的两只宝库
    for function, x, ominous in (("vault", 42, "false"), ("ominous_vault", 50, "true")):
        b.set(x, DAIS_Y + 1, 26, f"minecraft:vault[facing=south,ominous={ominous},vault_state=inactive]", "庭院/宝库")
        b.bound(function, "block", (x, DAIS_Y + 1, 26), "minecraft:vault")
        for dx in (-1, 1):
            b.set(x + dx, DAIS_Y + 1, 26, "minecraft:lantern[hanging=false]", "庭院/宝库")
    for x in (44, 48):
        b.set(x, DAIS_Y + 3, 26, f"minecraft:{'red' if x == 44 else 'black'}_wall_banner[facing=south]", "庭院/宝库")

    # 庭院落点：高台台阶前的过道
    for x in (43, 49):
        b.standing("landing_monster", "point", (x, HALL_Y + 1, 31), 0)
    # 方框区域：整座长屋
    b.marker("courtyard", "zone", (AXIS, HALL_Y + 1, 40), shape="box", box=[8, 10, 15])
    b.reserve(HALL_X0 - 2, HALL_Z0 - 2, HALL_X1 + 2, HALL_Z1 + 2)


# ---------------------------------------------------------------- 抵达区：长船、码头、卢恩石

def pier(b):
    part = "抵达区/码头"
    for z in range(62, 81):
        for x in range(44, 49):
            edge = x in (44, 48)
            b.set(x, SHORE_Y, z, log("stripped_spruce", "z") if edge else SPRUCE_PLANKS, f"{part}/栈板")
        if z % 4 == 0:
            for x in (44, 48):
                ground = next(y for y in range(SHORE_Y - 1, 0, -1) if b.solid(x, y, z))
                for y in range(ground + 1, SHORE_Y + 2):
                    b.set(x, y, z, log("spruce"), f"{part}/桩")
    # 码头头部的灯
    for x in (44, 48):
        b.set(x, SHORE_Y + 2, 80, "minecraft:lantern[hanging=false]", f"{part}/灯")
    b.reserve(43, 56, 58, 90)


GANGPLANKS = (68, 69, 78, 79)


def longship(b):
    """龙首长船：船首朝南，船身沿 z。甲板在 SHORE_Y，与码头齐平。"""
    part = "抵达区/长船"
    cx, z0, z1 = 53, 64, 83
    zc, halflen = (z0 + z1) / 2, (z1 - z0) / 2
    deck = SHORE_Y
    cells = {}
    for z in range(z0, z1 + 1):
        t = (z - zc) / halflen
        hw = 3.45 * max(0.0, 1 - abs(t) ** 2.4) ** 0.55
        keel = 13 + 2.5 * abs(t) ** 2
        sheer = deck + 1 + 2.2 * abs(t) ** 5
        for x in range(cx - 4, cx + 5):
            dx = abs(x - cx)
            if dx <= hw + 0.25:
                cells[(x, z)] = (dx, hw, keel, sheer)
    for (x, z), (dx, hw, keel, sheer) in cells.items():
        edge = any((x + ex, z + ez) not in cells for ex, ez in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        bottom = int(round(keel + (dx / max(hw, 0.5)) ** 2 * (deck - keel)))
        top = int(round(sheer)) if edge else deck
        for y in range(bottom, top + 1):
            if edge:
                strake = DARK_PLANKS if (y - bottom) % 2 == 0 else SPRUCE_PLANKS
                block = log("stripped_dark_oak", "z") if y == top else strake
            else:
                block = SPRUCE_PLANKS if y == deck else DARK_PLANKS
            b.set(x, y, z, block, f"{part}/船身")
    # 舷外的圆盾（墙上旗帜只有底色）
    colors = ["red", "yellow", "white", "black"]
    i = 0
    for z in range(z0 + 3, z1 - 2, 2):
        if z in GANGPLANKS:
            continue
        for x, facing in ((cx - 4, "west"), (cx + 4, "east")):
            inner = x + (1 if facing == "west" else -1)
            if (inner, z) in cells and (x, z) not in cells and not b.get(x, deck + 1, z):
                b.set(x, deck + 1, z, f"minecraft:{colors[i % 4]}_wall_banner[facing={facing}]", f"{part}/圆盾")
        i += 1
    # 桅杆、横桁、红白条纹方帆（帆底在甲板上方三格，人能从下面走过）
    mast_z = 74
    for y in range(deck + 1, deck + 13):
        b.set(cx, y, mast_z, log("dark_oak"), f"{part}/桅杆")
    for x in range(cx - 4, cx + 5):
        b.set(x, deck + 12, mast_z, log("dark_oak", "x"), f"{part}/桅杆")
    for x in range(cx - 4, cx + 5):
        for y in range(deck + 3, deck + 12):
            if x == cx:
                continue
            bulge = 1 if abs(x - cx) <= 2 and deck + 5 <= y <= deck + 9 else 0
            wool = "minecraft:red_wool" if (x - cx) % 2 == 0 else "minecraft:white_wool"
            b.set(x, y, mast_z + bulge, wool, f"{part}/方帆")
    # 船首龙头、船尾卷尾
    prow = [(z1 + 1, deck + 2), (z1 + 1, deck + 3), (z1 + 2, deck + 4), (z1 + 2, deck + 5), (z1 + 2, deck + 6),
            (z1 + 3, deck + 6), (z1 + 3, deck + 7), (z1 + 4, deck + 7), (z1 + 2, deck + 7)]
    for z, y in prow:
        b.set(cx, y, z, DARK_PLANKS, f"{part}/龙头")
    b.set(cx, deck + 1, z1 + 1, DARK_PLANKS, f"{part}/龙头")
    b.set(cx, deck + 6, z1 + 4, stairs("dark_oak", "north", "top"), f"{part}/龙头")
    b.set(cx - 1, deck + 7, z1 + 3, "minecraft:red_stained_glass", f"{part}/龙头")
    b.set(cx + 1, deck + 7, z1 + 3, "minecraft:red_stained_glass", f"{part}/龙头")
    b.set(cx, deck + 8, z1 + 2, "minecraft:dark_oak_fence", f"{part}/龙头")
    b.set(cx, deck + 8, z1 + 3, "minecraft:dark_oak_fence", f"{part}/龙头")
    stern = [(z0 - 1, deck + 1), (z0 - 1, deck + 2), (z0 - 2, deck + 3), (z0 - 2, deck + 4), (z0 - 2, deck + 5),
             (z0 - 1, deck + 5), (z0 - 1, deck + 6)]
    for z, y in stern:
        b.set(cx, y, z, DARK_PLANKS, f"{part}/卷尾")
    # 划桨手的座板和舵
    for z in (67, 70, 78, 81):
        for x in (cx - 2, cx + 2):
            b.set(x, deck + 1, z, "minecraft:spruce_slab[type=bottom,waterlogged=false]", f"{part}/座板")
    for y in range(deck - 2, deck + 2):
        b.set(cx + 3, y, z0 + 2, log("dark_oak"), f"{part}/舵")
    # 跳板：甲板 ↔ 码头
    for z in GANGPLANKS:
        b.set(49, deck, z, SPRUCE_PLANKS, f"{part}/跳板")
        b.set(50, deck, z, SPRUCE_PLANKS, f"{part}/跳板")
        for y in range(deck + 1, deck + 4):
            b.clear(50, y, z)
    # 中央落点：甲板中线、桅杆两侧。桅杆北边的面向西北（码头和长屋），南边的面向西（跳板）
    for z, yaw in ((70, 135), (71, 135), (72, 135), (77, 90), (78, 90)):
        b.standing("landing_central", "point", (cx, deck + 1, z), yaw)


def runestones(b):
    part = "抵达区/卢恩石"
    y0 = SHORE_Y + 1
    # 主卢恩石：x = 40 的一面石板，五格宽、顶部圆拱；红色蛇纹沿轮廓绕一圈，中间是刻纹
    shape = {58: 3, 59: 5, 60: 6, 61: 5, 62: 3}
    for z, h in shape.items():
        for y in range(y0, y0 + h):
            outline = y == y0 + h - 1 or z in (58, 62)
            runes = (y - y0, z) in ((1, 60), (3, 60), (2, 59), (2, 61), (4, 60))
            block = "minecraft:red_terracotta" if outline else "minecraft:chiseled_stone_bricks" if runes else "minecraft:polished_andesite"
            b.set(40, y, z, block, f"{part}/主石")
            b.set(39, y, z, "minecraft:andesite" if y < y0 + h - 1 else "minecraft:stone", f"{part}/主石")
    for function, z, label in (("rules", 59, "规则"), ("history", 61, "战绩")):
        b.set(41, y0, z, "minecraft:lectern[facing=east,has_book=false,powered=false]", f"{part}/讲台")
        b.bound(function, "click", (41, y0, z), "minecraft:lectern")
        b.set(41, y0 + 1, z, wall_sign("east"), f"{part}/讲台")
        b.text((41, y0 + 1, z), [label, "右键翻阅"])
    b.set(41, y0 + 2, 60, wall_sign("east"), f"{part}/状态牌")
    b.bound("status", "text", (41, y0 + 2, 60), "minecraft:spruce_wall_sign")
    # 东边的小卢恩石：署名
    for y in range(y0, y0 + 3):
        b.set(52, y, 60, "minecraft:red_terracotta" if y == y0 + 1 else "minecraft:polished_andesite", f"{part}/署名石")
        b.set(53, y, 60, "minecraft:andesite", f"{part}/署名石")
    b.set(52, y0 + 3, 60, "minecraft:andesite_slab[type=bottom,waterlogged=false]", f"{part}/署名石")
    b.set(51, y0 + 1, 60, wall_sign("west"), f"{part}/署名牌")
    b.bound("credits", "text", (51, y0 + 1, 60), "minecraft:spruce_wall_sign")
    # 岸边广场铺地
    for x in range(37, 56):
        for z in range(57, 63):
            if b.get(x, SHORE_Y, z) and not (44 <= x <= 48 and z <= 62) and math.hypot(x - 46, (z - 60) * 1.5) < 9.5:
                r = DETAIL.at(x * 3, z * 3)
                b.set(x, SHORE_Y, z, "minecraft:gravel" if r < 0.35 else COBBLE if r < 0.7 else MOSSY, f"{part}/广场")
    for x in (43, 49):
        lamp_post(b, x, SHORE_Y + 1, 62, f"{part}/灯")
    b.reserve(36, 55, 56, 64)


def path_surface(b):
    """主路的路面和台阶。"""
    for (x, z), h in PATH_HEIGHTS.items():
        if 56 <= z <= 62:
            r = DETAIL.at(x * 2.3, z * 2.3)
            block = "minecraft:dirt_path" if r < 0.55 else "minecraft:coarse_dirt" if r < 0.8 else "minecraft:gravel"
            if h == SHORE_Y and z >= 61:
                block = SPRUCE_PLANKS
            b.set(x, h, z, block, "抵达区/小路")
        elif z <= 24:
            b.set(x, h, z, "minecraft:stone_bricks" if (x + z) % 3 else "minecraft:mossy_stone_bricks", "集结区/石阶")
        else:
            b.set(x, h, z, "minecraft:mossy_stone_bricks" if DETAIL.at(x * 3, z * 3) > 0.5 else "minecraft:stone_bricks", "祭坛/石阶")
        b.clear(x, h + 1, z)
        b.clear(x, h + 2, z)
    for x, y, z, facing, kind in STEPS:
        b.set(x, y, z, stairs(kind, facing), "动线/台阶")
    b.reserve(41, 21, 51, 25)
    b.reserve(41, 55, 51, 63)
    b.reserve(54, 7, 66, 17)


# ---------------------------------------------------------------- 集结区：篝火广场

def plaza(b):
    part = "集结区/篝火广场"
    cx, cz = PLAZA
    y = PLAZA_Y
    cells = []
    for x in range(cx - 10, cx + 11):
        for z in range(cz - 10, cz + 11):
            r = math.hypot(x - cx, z - cz)
            if r > 9.5:
                continue
            for yy in range(y + 1, y + 4):
                b.clear(x, yy, z)
            if r <= 2.5:
                b.set(x, y, z, "minecraft:stone_bricks", f"{part}/火坑")
            elif r <= 6.5:
                b.set(x, y, z, ASSEMBLY_FLOOR, "集结区/干草圈")
                cells.append((x, z))
            else:
                n = DETAIL.at(x * 2.7, z * 2.7)
                b.set(x, y, z, COBBLE if n < 0.45 else MOSSY if n < 0.7 else "minecraft:stone", f"{part}/铺地")
    # 篝火：五堆营火，四角是立起的云杉原木
    for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
        b.set(cx + dx, y + 1, cz + dz, "minecraft:campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]", f"{part}/篝火")
    for dx, dz in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
        b.set(cx + dx, y + 1, cz + dz, log("spruce"), f"{part}/篝火")
        b.set(cx + dx, y + 2, cz + dz, "minecraft:spruce_fence", f"{part}/篝火")
    b.set(cx, y + 2, cz, log("spruce"), f"{part}/篝火")
    b.set(cx, y + 3, cz, "minecraft:spruce_fence", f"{part}/篝火")
    # 外圈：原木长凳和旗杆，留出南、东两个口
    for k in range(12):
        a = k * math.tau / 12 + math.tau / 24
        x, z = cx + round(8.2 * math.sin(a)), cz + round(8.2 * math.cos(a))
        if (abs(x - cx) <= 2 and z > cz) or (abs(z - cz) <= 2 and x > cx):
            continue
        if k % 3 == 0:
            lamp_post(b, x, y + 1, z, f"{part}/灯", 3)
        else:
            axis = "x" if abs(math.cos(a)) > 0.7 else "z"
            b.set(x, y + 1, z, log("stripped_spruce", axis), f"{part}/长凳")
    # 集结区标牌
    b.set(49, y + 1, 21, "minecraft:spruce_sign[rotation=0,waterlogged=false]", f"{part}/标牌")
    b.text((49, y + 1, 21), ["篝火集结", "站上干草圈", "就是准备好了"])
    b.marker("assembly", "zone", (cx, y, cz + 4), shape="flood", materials=["minecraft:hay_block"])
    b.reserve(cx - 11, cz - 11, cx + 11, cz + 11)
    return cells


# ---------------------------------------------------------------- 祭坛：山坡上的石阵

def altar(b):
    part = "祭坛"
    cx, cz = ALTAR
    y = ALTAR_Y
    for x in range(cx - 7, cx + 8):
        for z in range(cz - 7, cz + 8):
            r = math.hypot(x - cx, z - cz)
            if r > 6.8:
                continue
            for yy in range(y + 1, y + 5):
                b.clear(x, yy, z)
            n = DETAIL.at(x * 3.1, z * 3.1)
            if r <= 2.3:
                b.set(x, y, z, ALTAR_FLOOR, f"{part}/踏板")
            else:
                b.set(x, y, z, "minecraft:moss_block" if n < 0.3 else "minecraft:gravel" if n < 0.45 else GRASS if n < 0.8 else "minecraft:mossy_cobblestone", f"{part}/台地")
    # 信标：下面一层 3×3 铁块，上面一块遮光玻璃。点亮时模组把玻璃换成黄色。
    b.fill(cx - 1, y - 1, cz - 1, cx + 1, y - 1, cz + 1, "minecraft:iron_block", f"{part}/信标基座")
    b.set(cx, y, cz, "minecraft:beacon", f"{part}/信标")
    b.set(cx, y + 1, cz, "minecraft:tinted_glass", f"{part}/光柱")
    b.bound("altar_light", "block", (cx, y + 1, cz), "minecraft:tinted_glass")
    # 立石一圈，西边留口
    stones = 9
    for k in range(stones):
        a = k * math.tau / stones
        if abs(math.degrees(a) - 270) < 25:
            continue
        x, z = cx + round(4.6 * math.sin(a)), cz + round(4.6 * math.cos(a))
        h = 3 + (k % 3 == 0) + (k % 4 == 1)
        for yy in range(y + 1, y + 1 + h):
            block = MOSSY if (yy + k) % 3 == 0 else "minecraft:stone" if k % 2 else "minecraft:andesite"
            b.set(x, yy, z, block, f"{part}/立石")
        if k % 2 == 0:
            b.set(x, y + 1 + h, z, "minecraft:moss_carpet", f"{part}/立石")
    # 东北角一块卢恩石
    for yy in range(y + 1, y + 4):
        b.set(cx + 5, yy, cz - 5, "minecraft:red_terracotta" if yy == y + 2 else "minecraft:polished_andesite", f"{part}/卢恩石")
    b.set(cx - 5, y + 1, cz - 3, "minecraft:spruce_sign[rotation=4,waterlogged=false]", f"{part}/标牌")
    b.text((cx - 5, y + 1, cz - 3), ["复生祭坛"])
    b.standing("landing_altar", "point", (cx - 5, y + 1, cz), -90)
    b.marker("altar_pad", "zone", (cx - 1, y, cz), shape="flood", materials=["minecraft:amethyst_block"])
    b.reserve(cx - 8, cz - 8, cx + 8, cz + 8)


# ---------------------------------------------------------------- 村落里的其他房子

def village(b):
    # 西屋：屋脊沿 z，东墙开门对着大厅
    longhouse(b, 21, 31, 31, 45, 21, "z", "村落/西屋", doors=[(31, 38, 2)])
    spruce_door(b, 31, 22, 38, "west", "村落/西屋")
    b.reserve(17, 27, 35, 49)
    # 铁匠铺：屋脊沿 x，南墙开门
    longhouse(b, 18, 50, 28, 56, 20, "x", "村落/铁匠铺", doors=[(56, 23, 2)])
    spruce_door(b, 23, 21, 56, "north", "村落/铁匠铺")
    for x, block in ((25, "minecraft:anvil[facing=east]"), (21, "minecraft:grindstone[face=floor,facing=north]"),
                     (22, "minecraft:campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]")):
        b.set(x, b.ground[(x, 58)] + 1, 58, block, "村落/铁匠铺")
    b.reserve(14, 46, 32, 61)
    # 仓房：在广场西北，屋脊沿 x
    longhouse(b, 26, 6, 34, 14, 24, "x", "村落/仓房", doors=[(10, 34, 2)])
    spruce_door(b, 34, 25, 10, "west", "村落/仓房")
    b.reserve(22, 2, 37, 18)
    # 东屋
    longhouse(b, 59, 34, 67, 46, 22, "z", "村落/东屋", doors=[(59, 40, 2)])
    spruce_door(b, 59, 23, 40, "east", "村落/东屋")
    b.reserve(56, 30, 71, 49)


def field(b):
    """东屋南边的一小块麦田，中间一条水渠。"""
    part = "村落/麦田"
    y = 21
    for x in range(59, 70):
        for z in range(51, 58):
            edge = x in (59, 69) or z in (51, 57)
            if edge:
                b.set(x, y, z, "minecraft:coarse_dirt", part)
                if z == 57 or x == 69:
                    b.set(x, y + 1, z, "minecraft:mossy_cobblestone_wall", part)
            elif z == 54:
                b.set(x, y, z, WATER, part)
            else:
                b.set(x, y, z, "minecraft:farmland[moisture=7]", part)
                b.set(x, y + 1, z, "minecraft:wheat[age=7]", part)
            b.set(x, y - 1, z, DIRT, part)
    b.reserve(57, 49, 71, 59)


def drying_racks(b):
    """岸边的晾鱼架：栅栏柱加一根横栏。"""
    part = "村落/晾鱼架"
    for x0, z in ((30, 59), (30, 62)):
        base = b.ground.get((x0, z), SHORE_Y)
        for x in range(x0, x0 + 5):
            ground = b.ground.get((x, z), base)
            if x in (x0, x0 + 4):
                for y in range(ground + 1, base + 4):
                    b.set(x, y, z, "minecraft:spruce_fence", part)
            b.set(x, base + 3, z, "minecraft:spruce_fence", part)
        b.reserve(x0 - 1, z - 1, x0 + 5, z + 1)


def faering(b):
    """拖上岸的小船。"""
    part = "村落/小船"
    cx, z0, z1 = 26, 60, 67
    base = b.ground.get((cx, (z0 + z1) // 2), 17)
    for z in range(z0, z1 + 1):
        t = (z - (z0 + z1) / 2) / ((z1 - z0) / 2)
        hw = 1.6 * max(0.0, 1 - abs(t) ** 2) ** 0.5
        for x in range(cx - 2, cx + 3):
            if abs(x - cx) <= hw + 0.2:
                edge = abs(x - cx) >= hw - 0.6 or z in (z0, z1)
                b.set(x, base + 1, z, DARK_PLANKS, part)
                if edge:
                    b.set(x, base + 2, z, SPRUCE_PLANKS, part)
    b.set(cx, base + 3, z0, DARK_PLANKS, part)
    b.set(cx, base + 3, z1, DARK_PLANKS, part)
    b.reserve(cx - 3, z0 - 1, cx + 3, z1 + 1)


def dykes(b):
    """大厅两侧的石垒矮墙：把从岸边绕开大厅的路挡一挡，引人从大厅里穿过去。"""
    part = "村落/石垒"
    for x0, x1 in ((33, HALL_X0 - 1), (HALL_X1 + 1, 58)):
        for x in range(x0, x1 + 1):
            for z in (55,):
                g = b.ground.get((x, z))
                if g is None or b.get(x, g + 1, z):
                    continue
                b.set(x, g + 1, z, "minecraft:mossy_cobblestone_wall" if DETAIL.at(x * 4, z) > 0.5 else "minecraft:cobblestone_wall", part)


# ---------------------------------------------------------------- 植被

def spruce(b, rng, x, y, z, h, part, snowy=False):
    for i in range(h):
        b.set(x, y + i, z, log("spruce"), part)
    top = y + h
    b.set(x, top, z, LEAVES, part)
    b.set(x, top + 1, z, LEAVES, part)
    pattern = [1, 1, 2, 1, 2, 3, 2, 3, 2, 3, 3, 2]
    for i in range(h - 2):
        rad = pattern[min(i, len(pattern) - 1)]
        yy = top - 1 - i
        for dx in range(-rad, rad + 1):
            for dz in range(-rad, rad + 1):
                if dx * dx + dz * dz > rad * rad + 0.6 or (dx == 0 and dz == 0):
                    continue
                if not (0 <= x + dx < SIZE and ZMIN <= z + dz < ZMAX):
                    continue
                if not b.get(x + dx, yy, z + dz):
                    b.set(x + dx, yy, z + dz, LEAVES, part)
                    if snowy and not b.get(x + dx, yy + 1, z + dz) and rng.random() < 0.6:
                        b.set(x + dx, yy + 1, z + dz, "minecraft:snow[layers=1]", part)
    if snowy:
        b.set(x, top + 2, z, "minecraft:snow[layers=2]", part)


def vegetation(b, rng):
    taken = []
    candidates = sorted(b.ground.items())
    rng.shuffle(candidates)
    for (x, z), h in candidates:
        if (x, z) in b.reserved or h <= WATER_Y + 1 or h >= SNOW_LINE + 3:
            continue
        top = b.get(x, h, z)
        if top not in (GRASS, "minecraft:podzol", "minecraft:coarse_dirt") or b.get(x, h + 1, z):
            continue
        density = BROAD.at(x + 50, z + 50)
        if rng.random() > density * 0.5:
            continue
        if any((x - tx) ** 2 + (z - tz) ** 2 < 10 for tx, tz in taken):
            continue
        if not (2 <= x < SIZE - 2 and ZMIN + 2 <= z < ZMAX - 2):
            continue
        taken.append((x, z))
        tall = 6 + int(rng.random() * 6) - max(0, (h - 30) // 3)
        spruce(b, rng, x, h + 1, z, max(4, tall), "自然/云杉", snowy=h >= SNOW_LINE - 6)
        b.set(x, h, z, "minecraft:podzol", "自然/地形")
    # 草、蕨、花，零星的石头
    for (x, z), h in candidates:
        if (x, z) in b.reserved or b.get(x, h + 1, z):
            continue
        top = b.get(x, h, z)
        r = rng.random()
        if top == GRASS:
            if r < 0.16:
                b.set(x, h + 1, z, "minecraft:short_grass", "自然/草")
            elif r < 0.22:
                b.set(x, h + 1, z, "minecraft:fern", "自然/草")
            elif r < 0.235 and not b.get(x, h + 2, z):
                b.set(x, h + 1, z, "minecraft:large_fern[half=lower]", "自然/草")
                b.set(x, h + 2, z, "minecraft:large_fern[half=upper]", "自然/草")
            elif r < 0.25:
                b.set(x, h + 1, z, rng.choice(["minecraft:cornflower", "minecraft:lily_of_the_valley",
                                               "minecraft:dandelion", "minecraft:oxeye_daisy"]), "自然/花")
            elif r < 0.257:
                b.set(x, h + 1, z, MOSSY, "自然/石头")
        elif top in ("minecraft:podzol",) and r < 0.2:
            b.set(x, h + 1, z, "minecraft:fern", "自然/草")
        elif top in (STONE, "minecraft:andesite", "minecraft:gravel") and r < 0.03 and h > WATER_Y:
            b.set(x, h + 1, z, "minecraft:andesite" if r < 0.015 else MOSSY, "自然/石头")


def frozen_falls(b):
    """东山上挂下来的冰瀑，落进峡湾。"""
    part = "自然/冰瀑"
    x0, z = 77, 66
    for x in (x0, x0 + 1):
        top = b.ground.get((x, z))
        if top is None:
            continue
        for y in range(WATER_Y, top + 1):
            if b.get(x, y, z) in (STONE, "minecraft:andesite", GRASS, DIRT, "minecraft:snow_block", "minecraft:gravel", WATER, ""):
                b.set(x, y, z, "minecraft:packed_ice" if (x + y) % 3 else "minecraft:blue_ice", part)
        b.clear(x, top + 1, z)


# ---------------------------------------------------------------- 输出

def runs(b):
    palette, parts, out = {}, {}, []
    rows = sorted(b.blocks.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0]))
    for (x, y, z), (block, part) in rows:
        z -= ZMIN
        key = (palette.setdefault(block, len(palette)), parts.setdefault(part, len(parts)))
        if out and out[-1][1] == y and out[-1][2] == z and out[-1][0] + out[-1][3] == x and tuple(out[-1][4:]) == key:
            out[-1][3] += 1
        else:
            out.append([x, y, z, 1, *key])
    return {"format": "gmv1", "palette": list(palette), "parts": list(parts), "runs": out}


def main():
    rng = random.Random(SEED)
    plan_paths()
    b = Build()
    terrain(b)
    frozen_falls(b)
    path_surface(b)
    pier(b)
    longship(b)
    runestones(b)
    hall(b)
    plaza(b)
    altar(b)
    village(b)
    field(b)
    drying_racks(b)
    faering(b)
    dykes(b)
    vegetation(b, rng)
    seal_water(b)

    out = HERE / "out"
    out.mkdir(exist_ok=True)
    gmv1 = runs(b)
    (out / "build.gmv1.json").write_text(json.dumps(gmv1, ensure_ascii=False, separators=(",", ":")))
    manifest_path = HERE / "build.hardrush.json"
    shift = lambda rows: [{**row, "at": [row["at"][0], row["at"][1], row["at"][2] - ZMIN]} for row in rows]
    manifest = {"format": 1, "update_shapes": True, "markers": shift(b.markers), "signs": shift(b.signs)}
    origin = json.loads(manifest_path.read_text()).get("origin") if manifest_path.exists() else None
    lines = ["{"] + [f'  "{k}": {json.dumps(v)},' for k, v in manifest.items() if k not in ("markers", "signs")]
    for key in ("markers", "signs"):
        rows = ",\n".join("    " + json.dumps(row, ensure_ascii=False) for row in manifest[key])
        lines.append(f'  "{key}": [\n{rows}\n  ]' + ("," if key == "markers" or origin else ""))
    if origin:
        lines.append(f'  "origin": {json.dumps(origin)}')
    manifest_path.write_text("\n".join(lines + ["}"]) + "\n")
    ys = [y for (_, y, _) in b.blocks]
    print(f"方块 {len(b.blocks)}，高度 {min(ys)}–{max(ys)}，run {len(gmv1['runs'])}，调色板 {len(gmv1['palette'])}，"
          f"标记 {len(b.markers)}，告示牌 {len(b.signs)}")


if __name__ == "__main__":
    main()
