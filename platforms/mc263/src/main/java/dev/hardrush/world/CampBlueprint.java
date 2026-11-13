package dev.hardrush.world;

import dev.hardrush.core.lobby.LobbyLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Exact owned footprint. Coordinates are relative to the lobby's immutable anchor. */
public final class CampBlueprint {
    public static Map<BlockPos, BlockState> blocks(BlockPos anchor) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (int x = LobbyLayout.MIN_X; x <= LobbyLayout.MAX_X; x++) {
            for (int z = LobbyLayout.MIN_Z; z <= LobbyLayout.MAX_Z; z++) {
                boolean border = x == LobbyLayout.MIN_X || x == LobbyLayout.MAX_X || z == LobbyLayout.MIN_Z || z == LobbyLayout.MAX_Z;
                double distance = Math.hypot(x, z + 16);
                var floor = border ? Blocks.STONE_BRICKS : distance <= 7 ? distance >= 6 ? Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.YELLOW) : Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.LIME)
                        : Math.abs(x) <= 2 || Math.abs(z) <= 2 ? Blocks.STONE_BRICKS : Blocks.GRASS_BLOCK;
                blocks.put(anchor.offset(x, -1, z), floor.defaultBlockState());
            }
        }
        // Book house and monster camp: open fronts and broad, unobstructed paths.
        for (int x : new int[]{-27, -12, 12, 30}) for (int z : new int[]{-6, 5}) {
            for (int y = 0; y <= 3; y++) blocks.put(anchor.offset(x, y, z), Blocks.OAK_LOG.defaultBlockState());
        }
        for (int x = -27; x <= -12; x++) for (int z = -6; z <= 5; z++) blocks.put(anchor.offset(x, 4, z), Blocks.OAK_PLANKS.defaultBlockState());
        for (int x = 12; x <= 30; x++) for (int z = -6; z <= 5; z++) blocks.put(anchor.offset(x, 4, z), Blocks.OAK_PLANKS.defaultBlockState());
        for (var facility : LobbyLayout.Facility.values()) {
            if (facility == LobbyLayout.Facility.ALTAR) continue;
            var p = LobbyLayout.facility(facility);
            blocks.put(anchor.offset((int)p.x(), 0, (int)p.z()), facilityBlock(facility));
        }
        blocks.putAll(altar(anchor));
        // Nonfunctional areas are visibly marked closed until their delivery round.
        for (var p : LobbyLayout.displaySlots()) blocks.put(anchor.offset((int)p.x(), -1, (int)p.z()), Blocks.POLISHED_ANDESITE.defaultBlockState());
        for (var pos : labels(anchor).keySet()) blocks.put(pos, Blocks.OAK_SIGN.defaultBlockState());
        return java.util.Collections.unmodifiableMap(blocks);
    }
    public static Map<BlockPos, String[]> labels(BlockPos anchor) {
        var labels = new LinkedHashMap<BlockPos, String[]>();
        labels.put(anchor.offset(-4, 0, -8), new String[]{"集结区", "开局前走进即准备", "挑战中站4秒加入", "走出即取消"});
        labels.put(anchor.offset(4, 0, 1), new String[]{"HardRush 营地", "正在准备", "前方：集结区", ""});
        for (var facility : LobbyLayout.Facility.values()) {
            var p = LobbyLayout.facility(facility);
            String name = switch (facility) {
                case RULES -> "规则讲台"; case HISTORY -> "战绩书屋";
                case ZOMBIE -> "僵尸展位"; case SKELETON -> "骷髅展位"; case CREEPER -> "苦力怕展位";
                case PREVIOUS -> "庭院上一页"; case NEXT -> "庭院下一页"; case ALTAR -> "复生祭坛";
                case MERCHANT -> "女巫商人"; case VAULT -> "试炼宝库"; case OMINOUS_VAULT -> "不祥宝库";
            };
            if (facility == LobbyLayout.Facility.ALTAR) { labels.put(sign(anchor, facility), ALTAR_SIGN); continue; }
            labels.put(sign(anchor, facility), new String[]{name,
                    switch (facility) {
                        case RULES -> "右键阅读"; case HISTORY -> "右键查战绩领书";
                        case MERCHANT -> "怪物币换出击增强";
                        case VAULT -> "手持试炼钥匙右键"; case OMINOUS_VAULT -> "手持不祥钥匙右键";
                        default -> "极限挑战时开放";
                    },
                    switch (facility) {
                        case MERCHANT -> "先选角色再右键";
                        case VAULT, OMINOUS_VAULT -> "开出一次性装备"; default -> "";
                    }, ""});
        }
        return labels;
    }
    static final String[] ALTAR_SIGN = {"复生祭坛", "持复生图腾", "站上金块4秒", "空手回到世界"};
    /** Each facility's sign stands two blocks south of it; the altar's beside its south steps, clear of the path. */
    public static BlockPos sign(BlockPos anchor, LobbyLayout.Facility facility) {
        var p = LobbyLayout.facility(facility);
        return facility == LobbyLayout.Facility.ALTAR ? anchor.offset((int)p.x() - 2, 0, (int)p.z() + 3)
                : anchor.offset((int)p.x(), 0, (int)p.z() + 2);
    }
    /** The beacon's beam glass: yellow while the altar answers, tinted (which stops a beam) while it sleeps. */
    public static BlockPos altarGlass(BlockPos anchor) {
        var p = LobbyLayout.facility(LobbyLayout.Facility.ALTAR);
        return anchor.offset((int)p.x(), 2, (int)p.z());
    }
    /**
     * A quartz plinth one step high: stairs climb from every side onto a 3x3 gold core, which is a
     * level-1 beacon base. Lanterns mark the corners; a stone-brick path leads up from the monster hall.
     * Built dormant: the glass above the beacon is tinted until a hardcore challenge lights it.
     */
    static Map<BlockPos, BlockState> altar(BlockPos anchor) {
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        var p = LobbyLayout.facility(LobbyLayout.Facility.ALTAR);
        int cx = (int)p.x(), cz = (int)p.z();
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz + 3; z <= -7; z++)
            blocks.put(anchor.offset(x, -1, z), Blocks.STONE_BRICKS.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            // Grass under an opaque block decays to dirt, which the camp would read as someone else's edit.
            blocks.put(anchor.offset(cx + dx, -1, cz + dz), Blocks.STONE_BRICKS.defaultBlockState());
            var pos = anchor.offset(cx + dx, 0, cz + dz);
            boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
            if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) blocks.put(pos, Blocks.GOLD_BLOCK.defaultBlockState());
            else if (corner) {
                blocks.put(pos, Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());
                blocks.put(pos.above(), Blocks.LANTERN.defaultBlockState());
            } else {
                // A stair's FACING side is its full-height back: toward the core, so it climbs inward.
                var facing = dz == 2 ? net.minecraft.core.Direction.NORTH : dz == -2 ? net.minecraft.core.Direction.SOUTH
                        : dx == 2 ? net.minecraft.core.Direction.WEST : net.minecraft.core.Direction.EAST;
                blocks.put(pos, Blocks.QUARTZ_STAIRS.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.StairBlock.FACING, facing));
            }
        }
        blocks.put(anchor.offset(cx, 1, cz), Blocks.BEACON.defaultBlockState());
        blocks.put(altarGlass(anchor), Blocks.TINTED_GLASS.defaultBlockState());
        return blocks;
    }
    /** What stands at a facility point: packet models need air, vaults face the camp entrance. */
    public static BlockState facilityBlock(LobbyLayout.Facility facility) {
        return switch (facility) {
            case ZOMBIE, SKELETON, CREEPER, MERCHANT -> Blocks.AIR.defaultBlockState();
            // The altar's plinth stands at the point; the beacon sits one block up (see altar).
            case ALTAR -> Blocks.GOLD_BLOCK.defaultBlockState();
            case VAULT, OMINOUS_VAULT -> Blocks.VAULT.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.VaultBlock.FACING, net.minecraft.core.Direction.WEST)
                    .setValue(net.minecraft.world.level.block.VaultBlock.OMINOUS, facility == LobbyLayout.Facility.OMINOUS_VAULT);
            default -> Blocks.LECTERN.defaultBlockState();
        };
    }

    /** Blocks added with the monster shop; an existing camp receives them only where the ground is still untouched. */
    public static Map<BlockPos, BlockState> shopAdditions(BlockPos anchor) {
        var additions = new LinkedHashMap<BlockPos, BlockState>();
        for (var facility : LobbyLayout.SHOP_FACILITIES) {
            var p = LobbyLayout.facility(facility);
            var pos = anchor.offset((int)p.x(), 0, (int)p.z());
            if (!facilityBlock(facility).isAir()) additions.put(pos, facilityBlock(facility));
            additions.put(anchor.offset((int)p.x(), 0, (int)p.z() + 2), Blocks.OAK_SIGN.defaultBlockState());
        }
        return additions;
    }

    /** The altar's glass is owned in both states; only its dormant (tinted) one is in the blueprint. */
    public static boolean litAltarGlass(BlockPos anchor, BlockPos pos, BlockState state) {
        return pos.equals(altarGlass(anchor)) && state.is(Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.YELLOW));
    }
    /** The three owned v1 lecterns may be removed when opening an existing camp. */
    public static boolean legacyMonsterLectern(BlockPos anchor, BlockPos pos, BlockState state) {
        return state.is(Blocks.LECTERN) && LobbyLayout.MONSTER_FACILITIES.stream()
                .map(LobbyLayout::facility).anyMatch(p -> pos.equals(anchor.offset((int)p.x(), 0, (int)p.z())));
    }
    private CampBlueprint() {}
}
