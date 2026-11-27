#!/usr/bin/env python3
"""末地浮岛大厅包的生成器：建筑和功能位的唯一来源。

    python3 lobby-packs/end-islands/generate.py

写出 out/build.gmv1.json（上传到 genMC）和 build.hardrush.json（建筑清单，origin 由 scripts/genmc.py export 填）。
只用标准库，随机种子固定，同样的生成器产出同样的文件。坐标 x 东、y 上、z 南。

布局（南 → 北逐级升高）：抵达岛 → 栈桥 → 庭院岛 → 栈桥 → 集结岛 —— 祭坛小岛。
"""
import json
import math
import random
from pathlib import Path

HERE = Path(__file__).resolve().parent
SEED = 20261127
SIZE = 96

# 地面方块所在的高度；玩家的脚在它上面一格。
ARRIVAL_Y, COURT_Y, ASSEMBLY_Y = 24, 28, 32
AXIS = 48  # 栈桥和庭院大道的中线

ARRIVAL = dict(cx=46, cz=83, rx=9, rz=9, top=ARRIVAL_Y, depth=13)
COURT = dict(cx=48, cz=48, rx=22, rz=16, top=COURT_Y, depth=20)
ASSEMBLY = dict(cx=48, cz=14, rx=11, rz=11, top=ASSEMBLY_Y, depth=15)
ALTAR = dict(cx=68, cz=14, rx=5, rz=5, top=ASSEMBLY_Y, depth=9)

END_STONE, BRICKS, WALL = "minecraft:end_stone", "minecraft:end_stone_bricks", "minecraft:end_stone_brick_wall"
PURPUR, OBSIDIAN, BEDROCK = "minecraft:purpur_block", "minecraft:obsidian", "minecraft:bedrock"
ASSEMBLY_FLOOR = "minecraft:black_concrete"   # 只用于集结区
ALTAR_FLOOR = "minecraft:crying_obsidian"     # 只用于祭坛踏板


def pillar(axis="y"):
    return f"minecraft:purpur_pillar[axis={axis}]"


def rod(facing="up"):
    return f"minecraft:end_rod[facing={facing}]"


def stairs(facing, half="bottom"):
    return f"minecraft:purpur_stairs[facing={facing},half={half}]"


def slab(kind="bottom"):
    return f"minecraft:purpur_slab[type={kind}]"


def sign(facing):
    return f"minecraft:crimson_wall_sign[facing={facing}]"


class Noise:
    """平滑的二维值噪声，0–1。"""

    def __init__(self, rng, cell):
        self.cell = cell
        n = (SIZE + 48) // cell + 3
        self.grid = [[rng.random() for _ in range(n)] for _ in range(n)]

    def at(self, x, z):
        fx, fz = x / self.cell, z / self.cell
        ix, iz = int(fx), int(fz)
        tx, tz = fx - ix, fz - iz
        tx, tz = tx * tx * (3 - 2 * tx), tz * tz * (3 - 2 * tz)
        g = self.grid
        top = g[ix][iz] * (1 - tx) + g[ix + 1][iz] * tx
        bottom = g[ix][iz + 1] * (1 - tx) + g[ix + 1][iz + 1] * tx
        return top * (1 - tz) + bottom * tz


class Build:
    def __init__(self):
        self.blocks = {}   # (x, y, z) -> (方块, 部件)
        self.walk = {}     # (x, z) -> 可行走地面方块的 y
        self.markers = []
        self.signs = []

    def set(self, x, y, z, block, part):
        if not (0 <= x < SIZE and 0 <= z < SIZE and y >= 0):
            raise ValueError(f"{part} 的 {block} 放到了范围外：{(x, y, z)}")
        self.blocks[(x, y, z)] = (block, part)

    def fill(self, x0, y0, z0, x1, y1, z1, block, part):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    self.set(x, y, z, block, part)

    def clear(self, x, y, z):
        self.blocks.pop((x, y, z), None)

    def solid(self, x, y, z):
        return (x, y, z) in self.blocks

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
        block = self.blocks.get(tuple(at), ("", ""))[0]
        if not block.startswith(block_prefix):
            raise ValueError(f"{function} 在 {at} 应当是 {block_prefix}，实际是 {block or '空气'}")
        self.marker(function, kind, at)

    def text(self, at, front):
        if "sign" not in self.blocks.get(tuple(at), ("", ""))[0]:
            raise ValueError(f"{at} 不是告示牌")
        self.signs.append({"at": list(at), "front": front, "back": []})


def inside(island, x, z, grow=0.0):
    dx, dz = (x - island["cx"]) / (island["rx"] + grow), (z - island["cz"]) / (island["rz"] + grow)
    return dx * dx + dz * dz


def island(b, spec, part, shape, rough):
    """一座岛：中间是平整的可行走地面，外面一圈高低不平的末地石岛缘，底部向下收尖。"""
    reach = max(spec["rx"], spec["rz"]) + 4
    for x in range(spec["cx"] - reach, spec["cx"] + reach + 1):
        for z in range(spec["cz"] - reach, spec["cz"] + reach + 1):
            if not (0 <= x < SIZE and 0 <= z < SIZE):
                continue
            skirt = 1.6 + 1.4 * shape.at(x, z)
            d = inside(spec, x, z, skirt)
            if d > 1:
                continue
            walkable = inside(spec, x, z) <= 1
            top = spec["top"] if walkable else spec["top"] - int(2.6 * rough.at(x, z))
            depth = spec["depth"] * (1 - d) ** 0.75 * (0.55 + 0.9 * rough.at(x + 40, z)) + 1
            if rough.at(x, z + 40) > 0.72:      # 零星的石笋
                depth += 4 * (1 - d)
            for y in range(max(0, top - int(depth)), top + 1):
                b.set(x, y, z, END_STONE, f"{part}/岛体")
            if walkable:
                ring = inside(spec, x, z, -2.5)
                worn = ring > 1 and rough.at(x + 20, z + 20) > 0.45
                b.set(x, top, z, END_STONE if worn else PURPUR if 0.8 < ring <= 1 and spec["rx"] > 6 else BRICKS, f"{part}/地面")
                b.walk[(x, z)] = top


def bridge(b, part, cells):
    """栈桥：cells 是 [(x, z, 地面 y, 楼梯朝向或 None)]，岛上已有的地面保持不动。"""
    for x, z, y, facing in cells:
        if (x, z) in b.walk:
            continue
        b.set(x, y, z, stairs(facing) if facing else PURPUR, f"{part}/桥面")
        if facing:
            b.set(x, y - 1, z, PURPUR, f"{part}/桥面")
        b.walk[(x, z)] = y


def north_south_bridge(b, part, z_low, z_high, y_south, y_north, first_step):
    """沿 z 的栈桥，向北上行。first_step 是最南一级台阶的 z，每级之间隔 gap 格平台。"""
    steps = {}
    z, y = first_step, y_south
    gap = 2 if (first_step - z_low) >= 2 * (y_north - y_south) else 1
    while y < y_north:
        y += 1
        steps[z] = y
        z -= gap
    cells = []
    for z in range(z_high, z_low - 1, -1):
        level = y_south + sum(1 for s in steps if s >= z)
        for x in range(AXIS - 1, AXIS + 2):
            cells.append((x, z, level, "north" if z in steps else None))
    bridge(b, part, cells)


def barriers(b, part):
    """所有可行走格的外侧都立一圈末地石砖墙（碰撞高 1.5 格，跳不过去）。"""
    edge = {}
    for (x, z), y in b.walk.items():
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                cell = (x + dx, z + dz)
                if cell not in b.walk:
                    edge[cell] = max(edge.get(cell, -1), y)
    for (x, z), y in edge.items():
        if not b.solid(x, y, z):
            b.set(x, y, z, BRICKS, part)
        b.set(x, y + 1, z, WALL, part)
        b.clear(x, y + 2, z)
    return edge


def gate(b, part, z, y):
    """桥头两根紫珀立柱，顶上末地烛。"""
    for x in (AXIS - 2, AXIS + 2):
        b.fill(x, y + 1, z, x, y + 3, z, pillar(), part)
        b.set(x, y + 4, z, rod(), part)


def chorus(b, rng, x, y, z, part):
    """一株紫颂：树干只在顶端分叉，符合原版的存活规则，形状更新时不会掉。"""
    plant, flower = "minecraft:chorus_plant", "minecraft:chorus_flower[age=0]"
    height = rng.randint(2, 4)
    for i in range(height):
        b.set(x, y + i, z, plant, part)
    top = y + height - 1
    arms = [d for d in ((1, 0), (-1, 0), (0, 1), (0, -1)) if rng.random() < 0.4]
    arms = [(dx, dz) for dx, dz in arms if 0 <= x + dx < SIZE and 0 <= z + dz < SIZE
            and not any(b.solid(x + dx, top + i, z + dz) for i in range(-1, 4))]
    if not arms:
        b.set(x, top + 1, z, flower, part)
        return
    for dx, dz in arms[:2]:
        rise = rng.randint(1, 3)
        for i in range(rise):
            b.set(x + dx, top + i, z + dz, plant, part)
        b.set(x + dx, top + rise, z + dz, flower, part)


def arrival(b):
    part, y, cx, cz = "抵达区", ARRIVAL_Y, ARRIVAL["cx"], ARRIVAL["cz"]
    # 原版进入末地时脚下的 5×5 黑曜石平台。
    b.fill(cx - 2, y, cz - 2, cx + 2, y, cz + 2, OBSIDIAN, f"{part}/黑曜石平台")
    for x, z in ((cx - 1, cz - 1), (cx + 1, cz - 1), (cx - 1, cz + 1), (cx + 1, cz + 1)):
        b.standing("landing_central", "point", (x, y + 1, z), 180)
    b.fill(AXIS - 1, y, 75, AXIS + 1, y, cz - 3, PURPUR, f"{part}/甬道")
    for name, function, x, facing, back in (("规则亭", "rules", cx - 5, "east", -1), ("战绩亭", "history", cx + 5, "west", 1)):
        b.set(x, y + 1, cz, f"minecraft:lectern[facing={facing}]", f"{part}/{name}")
        b.fill(x + back, y + 1, cz, x + back, y + 3, cz, pillar(), f"{part}/{name}")
        # 檐口压在讲台上方只留一格空隙：讲台站不上去，也就没法从它起跳翻墙。
        b.fill(min(x, x + back), y + 3, cz - 1, max(x, x + back), y + 3, cz + 1, slab(), f"{part}/{name}")
        b.set(x + back, y + 4, cz, rod(), f"{part}/{name}")
        b.set(x, y + 2, cz, sign(facing), f"{part}/{name}")
        b.bound(function, "click", (x, y + 1, cz), "minecraft:lectern")
        b.text((x, y + 2, cz), ["规则" if function == "rules" else "战绩", "右键翻阅"])
    gate(b, f"{part}/桥头", 76, y)
    for x, function in ((AXIS - 2, "status"), (AXIS + 2, "credits")):
        b.set(x, y + 2, 77, sign("south"), f"{part}/桥头")
        b.bound(function, "text", (x, y + 2, 77), "minecraft:crimson_wall_sign")


def lamp(b, x, y, z, part):
    b.fill(x, y, z, x, y + 1, z, pillar(), part)
    b.set(x, y + 2, z, rod(), part)


def courtyard(b):
    part, y = "庭院", COURT_Y
    feet = y + 1
    # 大道：从南门进来迎面是选角拱廊，路在拱廊前分成两股绕过去，在后面合成一条通向北门。
    road = f"{part}/大道"
    b.fill(AXIS - 1, y, 61, AXIS + 1, y, 63, PURPUR, road)
    for x in range(39, 58):
        for z in range(53, 61):
            if x in (39, 40, 56, 57) or z in (53, 54, 59, 60):
                b.set(x, y, z, PURPUR, road)
    for z in range(33, 53):
        b.fill(AXIS - 1, y, z, AXIS + 1, y, z, PURPUR, road)
        for x in (AXIS - 2, AXIS + 2):
            b.set(x, y, z, pillar("z"), road)
    gate(b, f"{part}/南门", 63, y)
    gate(b, f"{part}/北门", 33, y)

    # 选角拱廊：三个拱龛正对南门，模型站在基座上面朝来路。
    arcade = f"{part}/选角拱廊"
    wall_z, bay_z = 56, 57
    b.fill(42, feet, wall_z, 54, feet + 4, wall_z, PURPUR, f"{arcade}/后墙")
    b.fill(42, feet, wall_z, 54, feet, wall_z, BRICKS, f"{arcade}/后墙")
    b.fill(42, feet + 4, bay_z, 54, feet + 4, bay_z, PURPUR, f"{arcade}/拱券")
    b.fill(42, feet + 5, wall_z, 54, feet + 5, bay_z + 1, slab(), f"{arcade}/檐口")
    for x in (42, 46, 50, 54):
        b.fill(x, feet, bay_z, x, feet + 3, bay_z, pillar(), f"{arcade}/立柱")
        b.set(x, feet + 3, bay_z + 1, rod("south"), f"{arcade}/立柱")
        b.set(x, feet + 6, wall_z, rod(), f"{arcade}/檐口")
    for x in (44, 48, 52):
        b.set(x, feet + 3, wall_z - 1, "minecraft:magenta_wall_banner[facing=north]", f"{arcade}/后墙")
    for x, function, name in ((44, "role_zombie", "僵尸"), (48, "role_skeleton", "骷髅"), (52, "role_creeper", "苦力怕")):
        b.set(x - 1, feet + 3, bay_z, stairs("west", "top"), f"{arcade}/拱券")
        b.set(x + 1, feet + 3, bay_z, stairs("east", "top"), f"{arcade}/拱券")
        b.set(x, feet, bay_z, "minecraft:chiseled_quartz_block", f"{arcade}/{name}基座")
        b.set(x, feet, bay_z + 1, sign("south"), f"{arcade}/{name}基座")
        b.standing(function, "model", (x, feet + 1, bay_z), 0)
        b.text((x, feet, bay_z + 1), [name, "右键模型选角", "挑战时右键", "目标展位追猎"])

    # 追猎目标展位：大道两侧各六根缩小的黑曜石柱，对应主岛那圈水晶柱。
    slots = f"{part}/目标展位"
    for z in (34, 37, 40, 43, 46, 49):
        for x in (AXIS - 6, AXIS + 6):
            b.fill(x, feet, z, x, feet + 1, z, OBSIDIAN, f"{slots}/黑曜石柱")
            b.set(x, y, z, BEDROCK, f"{slots}/黑曜石柱")
            b.standing("display_slot", "point", (x, feet + 2, z), -90 if x < AXIS else 90)
    # 翻页讲台离黑曜石柱三格以上，免得拿讲台当台阶跳上柱顶。
    for x, function, label in ((AXIS - 3, "page_prev", "上一页"), (AXIS + 3, "page_next", "下一页")):
        b.set(x, feet, 52, "minecraft:lectern[facing=south]", f"{slots}/翻页讲台")
        b.set(x, feet, 53, sign("south"), f"{slots}/翻页讲台")
        b.bound(function, "click", (x, feet, 52), "minecraft:lectern")
        b.text((x, feet, 53), [label, "右键翻页", "只对自己生效"])

    for x in (AXIS - 1, AXIS + 1):
        b.standing("landing_monster", "point", (x, feet, 61), 180)

    # 女巫的小摊：东侧塔楼南边，炼药锅和紫珀棚子。
    stall = f"{part}/女巫小摊"
    b.fill(62, feet, 49, 62, feet + 3, 53, PURPUR, stall)
    for z in (49, 53):
        b.fill(62, feet, z, 62, feet + 3, z, pillar(), stall)
        b.set(61, feet, z, "minecraft:cauldron", stall)
    b.fill(60, feet + 3, 49, 61, feet + 3, 53, slab("top"), stall)
    b.set(60, feet, 51, "minecraft:chiseled_quartz_block", stall)
    b.set(59, feet, 51, sign("west"), stall)
    b.set(61, feet + 2, 51, rod("west"), stall)
    b.set(61, feet + 2, 50, "minecraft:magenta_wall_banner[facing=west]", stall)
    b.set(61, feet + 2, 52, "minecraft:magenta_wall_banner[facing=west]", stall)
    b.standing("merchant", "model", (60, feet + 1, 51), 90)
    b.text((59, feet, 51), ["女巫商人", "右键交易"])

    # 西侧的歇脚处：几张紫珀长椅围着一盏灯。
    rest = f"{part}/歇脚处"
    lamp(b, 36, feet, 46, rest)
    for z in (44, 45, 47, 48):
        b.set(34, feet, z, stairs("west"), rest)
    for x in (35, 36, 37):
        b.set(x, feet, 42, stairs("north"), rest)
        b.set(x, feet, 50, stairs("south"), rest)
    for x, z in ((36, 38), (60, 37), (35, 56), (60, 57)):
        lamp(b, x, feet, z, f"{part}/灯柱")

    tower(b, feet)
    b.marker("courtyard", "zone", (COURT["cx"], feet, COURT["cz"]), shape="box", box=[COURT["rx"], 12, COURT["rz"] + 1])


def tower(b, feet):
    """末地城小塔：底层是放两种宝库的宝物间，上面是外挑的塔楼。"""
    part = "庭院/末地城小塔"
    x0, x1, z0, z1 = 59, 65, 41, 47
    top = feet + 11
    for y in range(feet, top + 1):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                corner = x in (x0, x1) and z in (z0, z1)
                if x in (x0, x1) or z in (z0, z1):
                    band = y in (feet + 4, feet + 10)
                    block = pillar() if corner else BRICKS if y <= feet + 1 else pillar("x" if z in (z0, z1) else "z") if band else PURPUR
                    b.set(x, y, z, block, f"{part}/塔身")
    b.fill(x0 + 1, feet + 4, z0 + 1, x1 - 1, feet + 4, z1 - 1, PURPUR, f"{part}/塔身")
    for y in (feet, feet + 1):
        b.clear(x0, y, 44)
    b.set(x0, feet + 2, 44, stairs("east", "top"), f"{part}/门")
    for z in (43, 45):
        b.set(x0 - 1, feet + 2, z, rod("west"), f"{part}/门")
    for y in (feet + 7, feet + 8):
        for x, z in ((x0, 44), (x1, 44), (62, z0), (62, z1)):
            b.set(x, y, z, "minecraft:magenta_stained_glass", f"{part}/塔身")
    for x, z, facing in ((x0 - 1, 42, "west"), (x0 - 1, 46, "west"), (61, z0 - 1, "north"), (63, z0 - 1, "north"),
                         (61, z1 + 1, "south"), (63, z1 + 1, "south")):
        b.set(x, feet + 8, z, f"minecraft:magenta_wall_banner[facing={facing}]", f"{part}/旗帜")
    # 宝物间
    b.set(x1 - 1, feet, 43, "minecraft:vault[facing=west,ominous=false]", f"{part}/宝物间")
    b.set(x1 - 1, feet, 45, "minecraft:vault[facing=west,ominous=true]", f"{part}/宝物间")
    b.bound("vault", "block", (x1 - 1, feet, 43), "minecraft:vault")
    b.bound("ominous_vault", "block", (x1 - 1, feet, 45), "minecraft:vault")
    for z in (42, 46):
        b.set(62, feet + 3, z, rod("down"), f"{part}/宝物间")
    b.fill(x0 + 1, feet - 1, z0 + 1, x1 - 1, feet - 1, z1 - 1, PURPUR, f"{part}/宝物间")
    # 外挑的塔楼
    head = f"{part}/塔楼"
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            rim = x in (x0 - 1, x1 + 1) or z in (z0 - 1, z1 + 1)
            b.set(x, top + 1, z, PURPUR, head)
            b.set(x, top + 6, z, slab() if rim else PURPUR, head)
            if rim:
                corner = x in (x0 - 1, x1 + 1) and z in (z0 - 1, z1 + 1)
                window = not corner and (x in (61, 62, 63) or z in (43, 44, 45))
                for y in range(top + 2, top + 6):
                    glass = window and y in (top + 3, top + 4)
                    b.set(x, y, z, "minecraft:magenta_stained_glass" if glass else pillar() if corner else PURPUR, head)
    for x, z in ((x0 - 1, z0 - 1), (x1 + 1, z0 - 1), (x0 - 1, z1 + 1), (x1 + 1, z1 + 1)):
        b.set(x, top + 7, z, rod(), head)
    for x in range(x0 - 1, x1 + 2, 2):       # 外挑下面的一圈倒置楼梯
        b.set(x, top, z0 - 1, stairs("south", "top"), head)
        b.set(x, top, z1 + 1, stairs("north", "top"), head)
    for z in range(z0 + 1, z1, 2):
        b.set(x0 - 1, top, z, stairs("east", "top"), head)
        b.set(x1 + 1, top, z, stairs("west", "top"), head)
    b.fill(62, top + 7, 44, 62, top + 9, 44, pillar(), head)
    b.set(62, top + 10, 44, rod(), head)


def assembly(b):
    """集结区做成原版的返回传送门：基岩围边的浅池，中央基岩柱、火把和龙首。"""
    part, y, cx, cz = "集结区/返回传送门", ASSEMBLY_Y, ASSEMBLY["cx"], ASSEMBLY["cz"]
    for x in range(cx - 8, cx + 9):
        for z in range(cz - 8, cz + 9):
            d = math.hypot(x - cx, z - cz)
            if d <= 5.5:
                b.set(x, y - 1, z, ASSEMBLY_FLOOR, f"{part}/池底")
                b.clear(x, y, z)
            elif d <= 6.9:
                b.set(x, y, z, BEDROCK, f"{part}/基岩围边")
    b.fill(cx, y, cz, cx, y + 3, cz, BEDROCK, f"{part}/中央柱")
    for dx, dz, facing in ((1, 0, "east"), (-1, 0, "west"), (0, 1, "south"), (0, -1, "north")):
        b.set(cx + dx, y + 2, cz + dz, f"minecraft:wall_torch[facing={facing}]", f"{part}/中央柱")
    b.set(cx, y + 4, cz, "minecraft:dragon_head[rotation=0]", f"{part}/中央柱")
    for dx, dz in ((7, 4), (-7, 4), (7, -4), (-7, -4), (4, 7), (-4, 7), (4, -7), (-4, -7)):
        b.fill(cx + dx, y + 1, cz + dz, cx + dx, y + 2, cz + dz, pillar(), "集结区/灯柱")
        b.set(cx + dx, y + 3, cz + dz, rod(), "集结区/灯柱")
    b.fill(AXIS - 1, y, 21, AXIS + 1, y, 25, PURPUR, "集结区/甬道")
    gate(b, "集结区/桥头", 24, y)
    b.marker("assembly", "zone", (cx, y - 1, cz + 3), shape="flood", materials=[ASSEMBLY_FLOOR])


def altar(b):
    """复生祭坛：被铁栏杆笼住的水晶柱顶，水晶的位置是遮光玻璃光柱。"""
    part, y, cx, cz = "祭坛", ASSEMBLY_Y, ALTAR["cx"], ALTAR["cz"]
    b.fill(cx - 1, y, cz - 1, cx + 1, y, cz + 1, ALTAR_FLOOR, f"{part}/踏板")
    b.set(cx, y, cz, BEDROCK, f"{part}/光柱")
    for i in range(1, 5):
        b.set(cx, y + i, cz, "minecraft:tinted_glass", f"{part}/光柱")
        b.bound("altar_light", "block", (cx, y + i, cz), "minecraft:tinted_glass")
    for x in range(cx - 2, cx + 3):
        for z in range(cz - 2, cz + 3):
            b.set(x, y + 5, z, "minecraft:iron_bars", f"{part}/铁笼")
            if abs(x - cx) == 2 and abs(z - cz) == 2:
                b.fill(x, y + 1, z, x, y + 4, z, "minecraft:iron_bars", f"{part}/铁笼")
    b.set(cx, y + 5, cz, OBSIDIAN, f"{part}/铁笼")
    b.set(cx, y + 6, cz, rod(), f"{part}/铁笼")
    b.fill(60, y, cz - 1, cx - 2, y, cz + 1, PURPUR, f"{part}/甬道")
    b.standing("landing_altar", "point", (cx - 4, y + 1, cz), -90)
    b.marker("altar_pad", "zone", (cx - 1, y, cz), shape="flood", materials=[ALTAR_FLOOR])


def scenery(b, rng, shape, rough, edge):
    """无功能的碎岛和岛缘上的紫颂植物：都在围墙外面，看得见、走不到。"""
    shards = [dict(cx=12, cz=58, rx=4, rz=5, top=22, depth=9), dict(cx=83, cz=70, rx=5, rz=4, top=19, depth=10),
              dict(cx=84, cz=36, rx=4, rz=4, top=35, depth=8), dict(cx=14, cz=24, rx=5, rz=4, top=36, depth=10),
              dict(cx=22, cz=88, rx=3, rz=3, top=17, depth=6), dict(cx=80, cz=90, rx=3, rz=3, top=28, depth=6)]
    walk_before = dict(b.walk)
    for i, spec in enumerate(shards):
        island(b, spec, f"自然/碎岛{i + 1}", shape, rough)
        for x in range(spec["cx"] - 8, spec["cx"] + 9):       # 碎岛没有铺地，也不算可行走
            for z in range(spec["cz"] - 8, spec["cz"] + 9):
                if (x, z) in b.walk and (x, z) not in walk_before:
                    del b.walk[(x, z)]
                    b.set(x, spec["top"], z, END_STONE, f"自然/碎岛{i + 1}/岛体")
    tops = {}
    for (x, y, z), (block, _) in b.blocks.items():
        if block == END_STONE and not b.solid(x, y + 1, z):
            tops[(x, z)] = max(tops.get((x, z), -1), y)
    near = lambda x, z: any((x + dx, z + dz) in b.walk or (x + dx, z + dz) in edge
                            for dx in (-1, 0, 1) for dz in (-1, 0, 1))
    planted = []
    for (x, z), y in sorted(tops.items()):
        if near(x, z) or any(abs(x - px) < 4 and abs(z - pz) < 4 for px, pz in planted):
            continue
        if any(b.solid(x, y + i, z) for i in range(1, 9)) or rng.random() > 0.22:
            continue
        chorus(b, rng, x, y + 1, z, f"自然/紫颂/{len(planted) + 1}")
        planted.append((x, z))


SOFT = ("sign", "torch", "banner", "chorus", "end_rod", "_wall[", "brick_wall")


def perches(b, edge):
    """围墙只有 1.5 格高，从一格高的东西上起跳就能翻过去。返回离墙太近的垫脚物 [(格, 高度, 离墙距离, 方块)]：
    一格高的要离墙 5 格以上；两格高的只有旁边有一格高的台阶时才上得去，那种要离墙 7 格以上。"""
    def body(x, y, z):
        block = b.blocks.get((x, y, z), ("",))[0]
        return bool(block) and not any(word in block for word in SOFT)

    def standable(x, z, height):
        y = b.walk[(x, z)] + height
        return body(x, y, z) and not body(x, y + 1, z) and not body(x, y + 2, z)

    indoors = lambda x, z: 60 <= x <= 64 and 42 <= z <= 46     # 塔里的宝物间四面是墙
    low = {cell for cell in b.walk if standable(*cell, 1) and not indoors(*cell)}
    high = {(x, z) for (x, z) in b.walk if standable(x, z, 2)
            and any(max(abs(x - lx), abs(z - lz)) <= 3 for lx, lz in low)}
    found = []
    for cells, height, limit in ((low, 1, 5), (high, 2, 7)):
        for x, z in cells:
            distance = min(max(abs(x - ex), abs(z - ez)) for ex, ez in edge)
            if distance < limit:
                found.append(((x, b.walk[(x, z)] + height, z), height, distance, b.blocks[(x, b.walk[(x, z)] + height, z)][0]))
    return sorted(found)


def lamps(b, edge):
    """围墙顶上每隔几格一支末地烛。"""
    for (x, z), y in sorted(edge.items()):
        if (x * 7 + z * 13) % 11 == 0 and b.blocks.get((x, y + 1, z), ("",))[0] == WALL:
            b.set(x, y + 2, z, rod(), "照明/围墙末地烛")


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
    island(b, ARRIVAL, "抵达区/浮岛", shape, rough)
    island(b, COURT, "庭院/浮岛", shape, rough)
    island(b, ASSEMBLY, "集结区/浮岛", shape, rough)
    island(b, ALTAR, "祭坛/浮岛", shape, rough)
    north_south_bridge(b, "栈桥/抵达—庭院", 62, 76, ARRIVAL_Y, COURT_Y, 73)
    north_south_bridge(b, "栈桥/庭院—集结", 23, 34, COURT_Y, ASSEMBLY_Y, 30)
    bridge(b, "栈桥/集结—祭坛", [(x, z, ASSEMBLY_Y, None) for x in range(58, 65) for z in range(13, 16)])
    edge = barriers(b, "围挡/末地石砖墙")
    arrival(b)
    courtyard(b)
    assembly(b)
    altar(b)
    scenery(b, rng, shape, rough, edge)
    lamps(b, edge)

    problems = perches(b, edge)
    if problems:
        raise SystemExit("垫脚物离围墙太近，能从上面跳出去：\n" + "\n".join(map(str, problems)))

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
    print(f"方块 {len(b.blocks)}，高度 {min(ys)}–{max(ys)}，run {len(gmv1['runs'])}，"
          f"标记 {len(b.markers)}，告示牌 {len(b.signs)}，可行走格 {len(b.walk)}")


if __name__ == "__main__":
    main()
