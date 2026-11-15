package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;

/**
 * Stand-ins for required functions a pack lacks (design 10.4), placed beside the first central landing of a lobby
 * that is still being installed: the dimension is not the protected lobby yet, so blocks are written directly.
 * Clicks get a lectern, paging a written oak sign, roles and the merchant only a model point, vaults a vault block.
 */
public final class FallbackFacilities {
    /** What was added, for the effective plan and the operators' notice. */
    public record Placed(List<Trigger> triggers, List<Model> models, List<LobbyFunction> functions) {
        public static final Placed NONE = new Placed(List.of(), List.of(), List.of());
        public Placed { triggers = List.copyOf(triggers); models = List.copyOf(models); functions = List.copyOf(functions); }
    }

    private FallbackFacilities() {}

    /** Places a stand-in for each of {@code missing}, in order; throws when one finds no room. */
    public static Placed place(ServerLevel level, LobbyPlan plan, List<LobbyFunction> missing) {
        if (missing.isEmpty()) return Placed.NONE;
        var central = LandingRules.candidates(plan, Landing.CENTRAL);
        if (central.isEmpty()) throw new IllegalStateException("大厅缺少中央落点，无法放置临时设施");
        var center = central.getFirst();
        var taken = new ArrayList<Cell>();
        var triggers = new ArrayList<Trigger>();
        var models = new ArrayList<Model>();
        for (var f : missing) {
            var cell = FallbackSites.find(plan, center, taken, c -> free(level, c))
                    .orElseThrow(() -> new IllegalStateException("中央落点旁找不到放【" + f.displayName() + "】临时设施的空地"));
            taken.add(cell);
            float yaw = FallbackSites.facing(cell, center);
            var pos = new BlockPos(cell.x(), cell.y(), cell.z());
            var anchor = new Point(cell.x() + .5, cell.y() + 1, cell.z() + .5, yaw);
            switch (f.fallback()) {
                case LECTERN -> {
                    put(level, pos, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.fromYRot(yaw)));
                    triggers.add(new Trigger(f, cell, "minecraft:lectern", anchor));
                }
                case SIGN -> {
                    put(level, pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(yaw)));
                    write(level, pos, f.displayName());
                    triggers.add(new Trigger(f, cell, "minecraft:oak_sign", anchor));
                }
                case VAULT -> {
                    put(level, pos, Blocks.VAULT.defaultBlockState().setValue(VaultBlock.FACING, Direction.fromYRot(yaw))
                            .setValue(VaultBlock.OMINOUS, f == LobbyFunction.OMINOUS_VAULT));
                    triggers.add(new Trigger(f, cell, "minecraft:vault", anchor));
                }
                case MODEL -> models.add(new Model(f, new Point(cell.x() + .5, cell.y(), cell.z() + .5, yaw)));
                case NONE -> throw new IllegalArgumentException("【" + f.displayName() + "】没有临时设施");
            }
        }
        return new Placed(triggers, models, missing);
    }

    /** The stand-in's own block and the one above are air, and it rests on a full block. */
    private static boolean free(ServerLevel level, Cell c) {
        var pos = new BlockPos(c.x(), c.y(), c.z());
        return level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.below()).isCollisionShapeFullBlock(level, pos.below());
    }

    private static void put(ServerLevel level, BlockPos pos, BlockState state) {
        if (!level.setBlock(pos, state, Block.UPDATE_ALL)) throw new IllegalStateException("临时设施放置失败：" + pos.toShortString());
    }

    /** A sign facing the landing, naming its function on the front; waxed, so nobody edits it. */
    private static void write(ServerLevel level, BlockPos pos, String name) {
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) throw new IllegalStateException("临时告示牌缺少方块实体：" + pos.toShortString());
        List<Component> lines = List.of(Component.empty(), Component.literal(name), Component.literal("右键翻页"), Component.empty());
        sign.setText(new SignText(lines, lines, DyeColor.BLACK, false), SignTextSlot.FRONT);
        sign.setWaxed(true);
    }
}
