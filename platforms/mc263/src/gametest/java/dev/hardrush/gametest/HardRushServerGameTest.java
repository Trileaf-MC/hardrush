package dev.hardrush.gametest;

import dev.hardrush.p0.disguise.DisguiseProbe;
import dev.hardrush.p0.world.WorldProbe;
import java.util.ArrayList;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;

public final class HardRushServerGameTest {
    @GameTest(maxTicks = 1200)
    public void persistentRoundDimensions(GameTestHelper test) {
        var server = test.getLevel().getServer();
        String first = WorldProbe.create(server, 12345);
        var round = new ArrayList<net.minecraft.server.level.ServerLevel>();
        for (var level : server.getAllLevels()) {
            if (WorldProbe.owns(level)) round.add(level);
        }
        test.assertValueEqual(round.size(), 3, "Three runtime round dimensions");
        for (var level : round) {
            test.assertValueEqual(level.getSeed(), 12345L, "Runtime seed");
            for (var destination : java.util.List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                var target = WorldProbe.portalDestination(level, destination);
                test.assertTrue(target != null && WorldProbe.owns(target), "Portal stays within round");
                test.assertValueEqual(WorldProbe.semanticDimension(target), destination, "Semantic portal target");
            }
            if (WorldProbe.semanticDimension(level).equals(Level.END)) {
                test.assertTrue(level.getDragonFight() != null, "Vanilla dragon fight initialized");
            }
        }
        test.assertValueEqual(WorldProbe.create(server, 12345), first, "Same seed is idempotent");
        boolean rejected = false;
        try { WorldProbe.create(server, 54321); }
        catch (IllegalArgumentException expected) { rejected = true; }
        test.assertTrue(rejected, "Different seed must be rejected");
        test.assertValueEqual(WorldProbe.status(), first, "Rejected seed preserves original round");
        test.succeed();
    }

    /** Covers vanilla EndPortalBlock and its routing mixin, not player credits, beds/anchors or collisions. */
    @GameTest(maxTicks = 1200)
    public void vanillaEndPortalRoutesWithinRound(GameTestHelper test) {
        var server = test.getLevel().getServer();
        WorldProbe.create(server, 12345);
        ServerLevel overworld = null;
        ServerLevel end = null;
        for (var level : server.getAllLevels()) {
            if (!WorldProbe.owns(level)) continue;
            if (WorldProbe.semanticDimension(level).equals(Level.OVERWORLD)) overworld = level;
            if (WorldProbe.semanticDimension(level).equals(Level.END)) end = level;
        }
        test.assertTrue(overworld != null && end != null, "Owned overworld and End are available");

        // These real entities belong to their source levels but are never added or ticked.
        var enteringEntity = EntityTypes.PIG.create(overworld, EntitySpawnReason.COMMAND);
        var returningEntity = EntityTypes.PIG.create(end, EntitySpawnReason.COMMAND);
        test.assertTrue(enteringEntity != null && returningEntity != null, "Portal test entities created");
        Portal portal = (Portal) Blocks.END_PORTAL;

        var entering = portal.getPortalDestination(overworld, enteringEntity, overworld.getRespawnData().pos());
        test.assertTrue(entering != null, "Vanilla End portal provides an entry destination");
        test.assertTrue(entering.newLevel() == end, "Vanilla End portal enters the same round's End");

        var returning = portal.getPortalDestination(end, returningEntity, ServerLevel.END_SPAWN_POINT);
        test.assertTrue(returning != null, "Vanilla End portal provides a return destination");
        test.assertTrue(returning.newLevel() == overworld, "Vanilla End portal returns to the same round's overworld");
        test.succeed();
    }

    @GameTest(maxTicks = 100)
    public void disguisePreservesEquipment(GameTestHelper test) {
        var player = test.makeMockServerPlayerInLevel();
        try {
            for (String kind : new String[]{"zombie", "skeleton", "creeper"}) {
                DisguiseProbe.apply(player, kind);
                test.assertTrue(DisguiseProbe.status(player).contains("disguise=" + kind), "Disguise applied: " + kind);
                test.assertTrue(DisguiseProbe.status(player).contains("selfView=player; selfSpawned=false"), "Native owner model without self mob");
                String before = DisguiseProbe.status(player);
                DisguiseProbe.apply(player, "unknown");
                test.assertValueEqual(DisguiseProbe.status(player), before, "Invalid mode preserves disguise");
                DisguiseProbe.clear(player);
                test.assertTrue(DisguiseProbe.status(player).startsWith("disguise=off"), "Disguise cleared");
            }
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
            DisguiseProbe.apply(player, "zombie");
            test.assertTrue(DisguiseProbe.status(player).contains("disguise=zombie"), "Equipment permits disguise");
            test.assertTrue(player.getMainHandItem().is(Items.DIAMOND_SWORD), "Disguise preserves item");
        } finally {
            DisguiseProbe.clear(player);
            test.getLevel().getServer().getPlayerList().remove(player);
        }
        test.succeed();
    }
}
