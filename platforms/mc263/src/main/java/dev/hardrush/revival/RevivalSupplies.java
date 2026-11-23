package dev.hardrush.revival;

import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ShopOffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Delivers the revival supplies bought from the witch (docs/plan/复生补给与女巫说明.md 2): the carried goods go into the
 * body the profile adapter builds for the revived life, the bonus chest is set down where that body lands.
 */
public final class RevivalSupplies {
    /** How far from the landing spot the chest may stand, horizontally. */
    private static final int CHEST_REACH = 2;

    private RevivalSupplies() {}

    /** Fills the fresh body of {@code life}'s revived human; called once, before its first resume snapshot. */
    public static void fill(ServerPlayer player, Participant life) {
        var supply = life.witch().supplyOf(life.lifeSequence());
        for (var entry : supply.entrySet()) {
            int count = entry.getValue();
            switch (entry.getKey()) {
                case SHIELD -> player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                case IRON_BUCKET -> give(player, new ItemStack(Items.BUCKET, count));
                case IRON_PICKAXE -> give(player, new ItemStack(Items.IRON_PICKAXE, count));
                case OAK_LOGS -> give(player, new ItemStack(Items.OAK_LOG, 5 * count));
                case BREAD -> give(player, new ItemStack(Items.BREAD, 8 * count));
                default -> {} // Landing supplies wait for the landing.
            }
        }
    }

    /**
     * Sets down a vanilla bonus chest next to the landed player, torches around it as vanilla's feature does. Where no
     * spot within reach takes it (a cliff edge, open water), its loot goes straight into the backpack.
     */
    public static void bonusChest(ServerPlayer player) {
        var level = player.level();
        var origin = player.blockPosition();
        BlockPos spot = null;
        search:
        for (int radius = 1; radius <= CHEST_REACH; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                var top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, origin.offset(dx, 0, dz));
                if (Math.abs(top.getY() - origin.getY()) > 2 || !standable(level, top)) continue;
                spot = top; break search;
            }
        }
        if (spot == null || !level.setBlock(spot, Blocks.CHEST.defaultBlockState(), 3)) { unpack(player); return; }
        RandomizableContainer.setBlockEntityLootTable(level, level.getRandom(), spot, BuiltInLootTables.SPAWN_BONUS_CHEST);
        var torch = Blocks.TORCH.defaultBlockState();
        for (var direction : Direction.Plane.HORIZONTAL) {
            var at = spot.relative(direction);
            // Unlike world generation, only air or a replaceable plant such as short grass gives way, never a solid or built block.
            if (level.getBlockState(at).canBeReplaced() && level.getFluidState(at).isEmpty() && torch.canSurvive(level, at)
                    && !at.equals(origin) && !at.equals(origin.above())) level.setBlock(at, torch, 3);
        }
    }

    /** An empty, dry cell on sturdy ground that neither the player nor anything solid occupies. */
    private static boolean standable(ServerLevel level, BlockPos at) {
        var state = level.getBlockState(at);
        return (state.isAir() || state.canBeReplaced() && state.getCollisionShape(level, at).isEmpty())
                && level.getFluidState(at).isEmpty()
                && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)
                && level.getFluidState(at.above()).isEmpty();
    }

    private static void unpack(ServerPlayer player) {
        var level = player.level();
        var table = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.SPAWN_BONUS_CHEST);
        var chest = new SimpleContainer(27);
        table.fill(chest, new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(player.blockPosition()))
                .withLuck(player.getLuck()).withParameter(LootContextParams.THIS_ENTITY, player)
                .create(LootContextParamSets.CHEST), level.getRandom().nextLong());
        for (var stack : chest.removeAllItems()) give(player, stack);
    }

    /** Into the backpack; whatever does not fit falls at the player's feet. */
    private static void give(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        // A partial fit still answers true and leaves the rest in the stack.
        player.getInventory().add(stack);
        if (!stack.isEmpty()) player.spawnAtLocation(player.level(), stack);
    }

    /** Whether {@code life}'s supplies hold anything bought at all. */
    public static boolean any(Participant life) { return !life.witch().supplyOf(life.lifeSequence()).isEmpty(); }

    public static int count(Participant life, ShopOffer offer) { return life.witch().count(life.lifeSequence(), offer); }
}
