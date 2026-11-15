package dev.hardrush.gametest;

import dev.hardrush.core.lobby.plan.*;
import dev.hardrush.world.LobbyWorld;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * Lobby places for tests, read from the installed lobby's plan so no test hardcodes camp coordinates.
 * Exact landings (no scatter) are the plan's first candidates. Not a production API.
 */
final class LobbyPlanFixtures {
    static LobbyPlan plan(MinecraftServer server) { return LobbyWorld.open(server).plan(); }

    /** The purpose's first exact landing. */
    static Vec3 landing(MinecraftServer server, Landing purpose) {
        return LobbyWorld.position(LandingRules.candidates(plan(server), purpose).getFirst());
    }
    /** The assembly zone's most central standing point. */
    static Vec3 inAssembly(MinecraftServer server) { return landing(server, Landing.RESTORE_READY); }
    /** A standing point well clear of the assembly zone. */
    static Vec3 outsideAssembly(MinecraftServer server) { return landing(server, Landing.CENTRAL); }

    /** The block of the function's first block trigger. */
    static BlockPos block(MinecraftServer server, LobbyFunction function) {
        return plan(server).triggers(function).stream().filter(t -> t.block() != null).findFirst()
                .map(t -> LobbyWorld.pos(t.block())).orElseThrow(() -> new AssertionError("No block trigger: " + function.id()));
    }
    /** A spot two blocks south of a facility block, within reach and clear of it. */
    static Vec3 before(BlockPos facility) { return Vec3.atBottomCenterOf(facility.south(2)); }
    /** Where the function's first model stands. */
    static Vec3 model(MinecraftServer server, LobbyFunction function) {
        return LobbyWorld.position(plan(server).models(function).getFirst().point());
    }

    /** The first altar pad's north-west floor cell. */
    static Cell altarCell(MinecraftServer server) {
        var pad = (Zone.Floor) plan(server).zones(LobbyFunction.ALTAR_PAD).getFirst();
        return pad.floor().stream().min(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::z).thenComparingInt(Cell::x)).orElseThrow();
    }
    /** Standing on {@link #altarCell}. */
    static Vec3 onAltar(MinecraftServer server) {
        var cell = altarCell(server);
        return new Vec3(cell.x() + .5, cell.y() + 1, cell.z() + .5);
    }
    /** At the foot of the altar, off its pad. */
    static Vec3 offAltar(MinecraftServer server) { return landing(server, Landing.ALTAR); }
    static BlockPos altarGlass(MinecraftServer server) { return LobbyWorld.pos(plan(server).altarLights().getFirst()); }

    /**
     * An air block for a terrain sentinel: above the lobby's middle, one block over its bounds, so it never
     * touches a facility, a walkway or a landing, and stays in the lobby's loaded chunks.
     */
    static BlockPos sentinel(MinecraftServer server) {
        var bounds = plan(server).bounds();
        return new BlockPos((bounds.min().x() + bounds.max().x()) / 2, bounds.max().y() + 1, (bounds.min().z() + bounds.max().z()) / 2);
    }
    /**
     * Free floor for a fixture block beside the central landing: one block east, where nothing in the plan
     * stands (checked), so a test can place and register its own facility.
     */
    static BlockPos fixtureSpot(MinecraftServer server, int east) {
        var plan = plan(server);
        var landing = LandingRules.candidates(plan, Landing.CENTRAL).getFirst();
        var cell = landing.cell().offset(east, 0, 0);
        if (plan.triggers().stream().anyMatch(t -> cell.equals(t.block())) || plan.models().stream().anyMatch(m -> m.point().cell().equals(cell)))
            throw new AssertionError("Fixture spot is taken: " + cell);
        return LobbyWorld.pos(cell);
    }
    private LobbyPlanFixtures() {}
}
