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
            var p = LobbyLayout.facility(facility);
            blocks.put(anchor.offset((int)p.x(), 0, (int)p.z()), facilityBlock(facility));
        }
        // Nonfunctional areas are visibly marked closed until their delivery round.
        for (var p : LobbyLayout.displaySlots()) blocks.put(anchor.offset((int)p.x(), -1, (int)p.z()), Blocks.POLISHED_ANDESITE.defaultBlockState());
        for (int x : new int[]{10, 18}) for (int y = 0; y <= 3; y++) blocks.put(anchor.offset(x, y, -18), Blocks.OAK_LOG.defaultBlockState());
        for (int x = 10; x <= 18; x++) blocks.put(anchor.offset(x, 4, -18), Blocks.OAK_PLANKS.defaultBlockState());
        for (var pos : labels(anchor).keySet()) blocks.put(pos, Blocks.OAK_SIGN.defaultBlockState());
        return java.util.Collections.unmodifiableMap(blocks);
    }
    public static Map<BlockPos, String[]> labels(BlockPos anchor) {
        var labels = new LinkedHashMap<BlockPos, String[]>();
        labels.put(anchor.offset(-4, 0, -8), new String[]{"人类集结区", "站入即准备", "离开取消", "一人即可出发"});
        labels.put(anchor.offset(4, 0, 1), new String[]{"HardRush 营地", "正在准备", "前方：人类集结", "右侧：中途门"});
        for (var facility : LobbyLayout.Facility.values()) {
            var p = LobbyLayout.facility(facility);
            String name = switch (facility) {
                case RULES -> "规则讲台"; case HISTORY -> "战绩书屋"; case MIDROUND -> "中途入场门";
                case ZOMBIE -> "僵尸展位"; case SKELETON -> "骷髅展位"; case CREEPER -> "苦力怕展位";
                case PREVIOUS -> "庭院上一页"; case NEXT -> "庭院下一页"; case ALTAR -> "复生祭坛";
                case MERCHANT -> "女巫商人"; case VAULT -> "试炼宝库"; case OMINOUS_VAULT -> "不祥宝库";
            };
            labels.put(anchor.offset((int)p.x(), 0, (int)p.z() + 2), new String[]{name,
                    switch (facility) {
                        case RULES -> "右键阅读"; case MIDROUND -> "右键确认加入"; case HISTORY -> "右键查战绩领书";
                        case ALTAR -> "右键查看复生资格"; case MERCHANT -> "怪物币换出场增强";
                        case VAULT -> "手持试炼钥匙右键"; case OMINOUS_VAULT -> "手持不祥钥匙右键";
                        default -> "极限挑战时开放";
                    },
                    switch (facility) {
                        case MIDROUND -> "挑战／救场时开放"; case MERCHANT -> "先选角色再右键";
                        case VAULT, OMINOUS_VAULT -> "抽取一次性装备"; default -> "";
                    }, ""});
        }
        return labels;
    }
    /** What stands at a facility point: packet models need air, vaults face the camp entrance. */
    public static BlockState facilityBlock(LobbyLayout.Facility facility) {
        return switch (facility) {
            case ZOMBIE, SKELETON, CREEPER, MERCHANT -> Blocks.AIR.defaultBlockState();
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

    /** The three owned v1 lecterns may be removed when opening an existing camp. */
    public static boolean legacyMonsterLectern(BlockPos anchor, BlockPos pos, BlockState state) {
        return state.is(Blocks.LECTERN) && LobbyLayout.MONSTER_FACILITIES.stream()
                .map(LobbyLayout::facility).anyMatch(p -> pos.equals(anchor.offset((int)p.x(), 0, (int)p.z())));
    }
    private CampBlueprint() {}
}
