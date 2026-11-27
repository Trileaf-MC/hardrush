package dev.hardrush.lobby.pack.imports;

import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.BuildManifest;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.ZoneShape;
import dev.hardrush.lobby.pack.MarkerStands;
import dev.hardrush.lobby.pack.PackPlacer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
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

    /** What {@link #apply} did, for the completion notice. */
    public record Applied(int markers, int signs, List<String> notes) {
        public Applied { notes = List.copyOf(notes); }
    }

    /**
     * Recomputes the shape of every non-air block in {@code region} from its neighbours, as
     * {@code StructureTemplate.placeInWorld} does without {@code knownShape}: {@link Block#updateFromNeighbourShapes},
     * written back only when it differs, with that method's flags (the placing flags without neighbour updates, which
     * equal {@link PackPlacer#FLAGS}). Run after every tile is placed, a door or bed half always finds its partner.
     * Unlike vanilla it sends no neighbour block updates ({@code updateNeighborsAt}), so redstone keeps the power the
     * file stored and nothing starts falling. Returns how many blocks lost their support and became air.
     */
    public static int updateShapes(ServerLevel level, Box region) {
        int removed = 0;
        var pos = new BlockPos.MutableBlockPos();
        for (int y = Math.max(region.min().y(), level.getMinY()); y <= Math.min(region.max().y(), level.getMaxY()); y++)
            for (int z = region.min().z(); z <= region.max().z(); z++)
                for (int x = region.min().x(); x <= region.max().x(); x++) {
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
        var notes = new ArrayList<String>();
        int written = 0;
        for (int i = 0; i < manifest.signs().size(); i++) {
            var sign = manifest.signs().get(i);
            var at = pos(manifest.toWorld(sign.at(), placedMin));
            if (!(level.getBlockEntity(at) instanceof SignBlockEntity entity)) {
                notes.add("建筑清单" + BuildManifest.describeSign(i) + " " + BuildManifest.cell(sign.at()) + " 处不是告示牌，已跳过");
                continue;
            }
            write(entity, SignTextSlot.FRONT, sign.front());
            write(entity, SignTextSlot.BACK, sign.back());
            written++;
        }
        return new Applied(manifest.markers().size(), written, notes);
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
