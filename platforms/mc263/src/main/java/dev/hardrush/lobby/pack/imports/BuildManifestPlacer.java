package dev.hardrush.lobby.pack.imports;

import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.BuildManifest;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.ZoneShape;
import dev.hardrush.lobby.pack.MarkerStands;
import dev.hardrush.lobby.pack.PackPlacer;
import dev.hardrush.lobby.pack.TileKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;

/**
 * What a building manifest does to the world once the building's tiles are placed (design “建筑清单与大厅包构建” 1.2–1.4).
 * Chunks must be loaded; the import keeps every manifest position over the building so they are.
 */
public final class BuildManifestPlacer {
    private BuildManifestPlacer() {}

    /** What {@link #apply} did, for the completion notice; {@code issues} are the sign entries it skipped. */
    public record Applied(int markers, int signs, List<ImportIssue> issues) {
        public Applied { issues = List.copyOf(issues); }
    }

    /**
     * Recomputes the shape of every block {@code tile} placed (its {@code blocks} list, inside {@code bounds}) from its
     * neighbours, as {@code StructureTemplate.placeInWorld} does without {@code knownShape}:
     * {@link Block#updateFromNeighbourShapes}, written back only when it differs, with that method's flags (the placing
     * flags without neighbour updates, which equal {@link PackPlacer#FLAGS}). Run after every tile is placed, a door or bed
     * half always finds its partner. Blocks already in the world are never touched, even between the placed ones: a
     * paste must not rewrite a builder's deliberate states. Unlike vanilla it sends no neighbour block updates
     * ({@code updateNeighborsAt}), so redstone keeps the power the file stored and nothing starts falling. Returns how
     * many placed blocks lost their support and became air.
     */
    public static int updateShapes(ServerLevel level, TileKey key, CompoundTag tile, Box bounds) {
        int removed = 0;
        var pos = new BlockPos.MutableBlockPos();
        for (var tag : tile.getListOrEmpty("blocks")) {
            if (!(tag instanceof CompoundTag block)) continue;
            var local = block.getListOrEmpty("pos");
            int x = key.originX() + local.getIntOr(0, 0), y = key.originY() + local.getIntOr(1, 0), z = key.originZ() + local.getIntOr(2, 0);
            if (!bounds.contains(new Cell(x, y, z)) || y < level.getMinY() || y > level.getMaxY()) continue;
            pos.set(x, y, z);
            var state = level.getBlockState(pos);
            if (state.isAir()) continue;
            var updated = Block.updateFromNeighbourShapes(state, level, pos);
            if (updated == state) continue;
            level.setBlock(pos, updated, PackPlacer.FLAGS);
            if (updated.isAir()) removed++;
        }
        return removed;
    }

    /** Spawns the manifest's markers and writes its signs, the building's minimum corner at {@code placedMin}. */
    public static Applied apply(ServerLevel level, BuildManifest manifest, Cell placedMin) {
        for (var marker : manifest.markers()) {
            var at = manifest.toWorld(marker.at(), placedMin);
            var spec = marker.spec();
            switch (spec.binding()) {
                case POINT, MODEL -> MarkerStands.spawn(level, spec, at.x() + .5, at.y(), at.z() + .5, marker.yaw());
                // A box's marker stands on the bottom centre; a flood's on the floor block it names.
                case ZONE -> MarkerStands.spawn(level, spec, at.x() + .5, at.y() + (spec.shape() == ZoneShape.FLOOD ? 1 : 0), at.z() + .5, marker.yaw());
                case CLICK, BLOCK, TEXT -> MarkerStands.spawnOn(level, spec, pos(at), marker.yaw());
            }
        }
        var issues = new ArrayList<ImportIssue>();
        int written = 0;
        for (int i = 0; i < manifest.signs().size(); i++) {
            var sign = manifest.signs().get(i);
            var world = manifest.toWorld(sign.at(), placedMin);
            if (!(level.getBlockEntity(pos(world)) instanceof SignBlockEntity entity)) {
                issues.add(new ImportIssue(ImportIssue.Kind.NOT_A_SIGN,
                        "建筑清单" + BuildManifest.describeSign(i) + " " + BuildManifest.cell(sign.at()) + " 处不是告示牌，已跳过", world, sign.at()));
                continue;
            }
            write(entity, SignTextSlot.FRONT, sign.front());
            write(entity, SignTextSlot.BACK, sign.back());
            written++;
        }
        return new Applied(manifest.markers().size(), written, issues);
    }

    /**
     * Plain literal lines (no JSON, no click events), the side's colour and glow kept; the sign is not waxed.
     * {@code setText} marks the block entity changed and sends it to clients.
     */
    private static void write(SignBlockEntity sign, SignTextSlot slot, List<String> lines) {
        if (lines == null) return;
        var old = sign.getText(slot);
        var messages = new ArrayList<Component>();
        for (int i = 0; i < SignText.LINES; i++) messages.add(Component.literal(i < lines.size() ? lines.get(i) : ""));
        sign.setText(new SignText(messages, messages, old.getColor(), old.hasGlowingText()), slot);
    }

    private static BlockPos pos(Cell c) { return new BlockPos(c.x(), c.y(), c.z()); }
}
