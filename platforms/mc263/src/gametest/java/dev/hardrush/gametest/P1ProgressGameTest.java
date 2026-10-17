package dev.hardrush.gametest;

import dev.hardrush.core.record.ProgressWrite;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.player.ProfileTagged;
import dev.hardrush.p1.player.mixin.PlayerAdvancementProfileAccessor;
import dev.hardrush.p1.record.P1ProgressRuntime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Real server advancement/dimension hooks, without a game client or computer-use interaction. */
public final class P1ProgressGameTest {
    @GameTest(maxTicks = 100)
    public void onlyTheFinalNativeCriterionRecordsTheDisplayAdvancement(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var player = test.makeMockServerPlayerInLevel();
        var writes = new ArrayList<ProgressWrite>();
        var context = new P1ProgressRuntime.Context(UUID.randomUUID(), 10);
        try (var ignored = dev.hardrush.gametest.RuntimeFixtures.progress(server,
                candidate -> candidate == player ? Optional.of(context) : Optional.empty(), writes::add)) {
            P1ProgressRuntime.tick(server);
            test.assertValueEqual(writes.size(), 1, "Initial UUID/advancement-set observation is recorded once");
            var holder = server.getAdvancements().get(Identifier.parse("minecraft:adventure/adventuring_time"));
            var criteria = holder.value().criteria().keySet().stream().sorted().toList();
            test.assertTrue(criteria.size() > 1, "Native fixture has multiple required criteria");
            for (int index = 0; index < criteria.size() - 1; index++) player.getAdvancements().award(holder, criteria.get(index));
            test.assertTrue(!player.getAdvancements().getOrStartProgress(holder).isDone(), "Partial native advancement stays incomplete");
            test.assertValueEqual(writes.size(), 1, "Partial criteria do not count as completed advancements");
            player.getAdvancements().award(holder, criteria.getLast());
            test.assertValueEqual(writes.size(), 2, "Final required criterion reaches the award RETURN hook");
            test.assertTrue(writes.getLast().completedAdvancementIds().contains(holder.id().toString()), "Completed display ID is recorded");
            player.getAdvancements().award(holder, criteria.getLast());
            P1ProgressRuntime.tick(server);
            test.assertValueEqual(writes.size(), 2, "Repeated award and periodic sampling cannot add duplicate events");

            var root = server.getAdvancements().get(Identifier.parse("minecraft:story/root"));
            root.value().criteria().keySet().forEach(criterion -> player.getAdvancements().award(root, criterion));
            var recipe = server.getAdvancements().getAllAdvancements().stream()
                    .filter(candidate -> candidate.id().getPath().startsWith("recipes/")).findFirst().orElseThrow();
            recipe.value().criteria().keySet().forEach(criterion -> player.getAdvancements().award(recipe, criterion));
            P1ProgressRuntime.tick(server);
            test.assertValueEqual(writes.size(), 2, "Root and recipe completion are excluded from the statistical set");
            test.assertTrue(writes.stream().flatMap(write -> write.completedAdvancementIds().stream())
                    .noneMatch(id -> id.contains(":recipes/") || id.endsWith("/root")), "Excluded advancement IDs never reach the sink");
        } finally { server.getPlayerList().remove(player); }
        test.succeed();
    }

    @GameTest(maxTicks = 100)
    public void isolationAndProfileRestorationDoNotCreateProgressAndCelebrationCannotWrite(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var player = test.makeMockServerPlayerInLevel();
        var writes = new ArrayList<ProgressWrite>();
        var context = new P1ProgressRuntime.Context(UUID.randomUUID(), 20);
        var live = new boolean[]{true};
        try (var ignored = dev.hardrush.gametest.RuntimeFixtures.progress(server,
                candidate -> candidate == player && live[0] ? Optional.of(context) : Optional.empty(), writes::add)) {
            var holder = server.getAdvancements().get(Identifier.parse("minecraft:story/mine_stone"));
            var criterion = holder.value().criteria().keySet().iterator().next();
            ((ProfileTagged) player).hardrush$isolated(true);
            player.getAdvancements().award(holder, criterion);
            P1ProgressRuntime.tick(server);
            test.assertTrue(writes.isEmpty(), "A quarantined profile is excluded even if an adapter accidentally admits its UUID");
            test.assertTrue(!player.getAdvancements().getOrStartProgress(holder).isDone(), "Locked vanilla award is refused");
            ((ProfileTagged) player).hardrush$isolated(false);
            player.getAdvancements().award(holder, criterion);
            test.assertValueEqual(writes.size(), 1, "Unlocked final award records the original completed state");
            var progress = ((PlayerAdvancementProfileAccessor) player.getAdvancements()).hardrush$progress();
            var saved = progress.get(holder);
            progress.remove(holder);
            ((ProfileTagged) player).hardrush$isolated(true);
            P1ProgressRuntime.tick(server);
            progress.put(holder, saved);
            ((ProfileTagged) player).hardrush$isolated(false);
            P1ProgressRuntime.tick(server);
            test.assertValueEqual(writes.size(), 1, "Accessor-based same-round archive restoration cannot synthesize an award or duplicate a record");
            live[0] = false;
            var other = server.getAdvancements().get(Identifier.parse("minecraft:story/upgrade_tools"));
            other.value().criteria().keySet().forEach(key -> player.getAdvancements().award(other, key));
            P1ProgressRuntime.tick(server);
            test.assertValueEqual(writes.size(), 1, "A settled/celebrating eligibility barrier excludes both native awards and synchronization");
        } finally {
            ((ProfileTagged) player).hardrush$isolated(false);
            server.getPlayerList().remove(player);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 100)
    public void nativeEndTransitionCountsOneUuidAndKeepsItAfterLeavingAndDeath(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var player = new ServerPlayer[]{test.makeMockServerPlayerInLevel()};
        var identity = player[0].getUUID();
        var writes = new ArrayList<ProgressWrite>();
        var context = new P1ProgressRuntime.Context(UUID.randomUUID(), 30);
        try (var ignored = dev.hardrush.gametest.RuntimeFixtures.progress(server,
                candidate -> candidate.getUUID().equals(identity) ? Optional.of(context) : Optional.empty(), writes::add)) {
            P1ProgressRuntime.tick(server);
            var end = server.getLevel(Level.END);
            test.assertTrue(end != null, "Vanilla End fixture is available");
            player[0].teleport(new TeleportTransition(end, new Vec3(.5, 128, .5), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
            test.assertValueEqual(writes.size(), 2, "Native dimension-trigger tail captures first End presence immediately");
            test.assertTrue(writes.getLast().enteredEnd(), "The entrant's UUID carries the first End marker");
            player[0].teleport(new TeleportTransition(test.getLevel(), Vec3.atBottomCenterOf(test.absolutePos(net.minecraft.core.BlockPos.ZERO)),
                    Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
            var dead = player[0];
            dead.setHealth(0);
            dead.die(dead.damageSources().genericKill());
            player[0] = server.getPlayerList().respawn(dead, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
            test.assertTrue(player[0] != dead && player[0].getUUID().equals(identity), "Native death respawn replaces the object while retaining the UUID");
            P1ProgressRuntime.tick(server);
            player[0].teleport(new TeleportTransition(end, new Vec3(.5, 128, .5), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
            P1ProgressRuntime.tick(server);
            test.assertValueEqual(writes.size(), 2, "Leaving, native death/respawn and reentry cannot recount the same UUID");
            var profile = player[0].getGameProfile();
            server.getPlayerList().remove(player[0]);
            player[0] = new ServerPlayer(server, test.getLevel(), profile, ClientInformation.createDefault());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            new io.netty.channel.embedded.EmbeddedChannel(connection);
            dev.hardrush.gametest.UnmanagedProfileFixtures.run(() -> server.getPlayerList().placeNewPlayer(connection, player[0],
                    net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false)));
            P1ProgressRuntime.tick(server);
            test.assertValueEqual(writes.size(), 2, "Same-UUID disconnected/reconnected native player does not recount End entry or completed advancements");
            test.assertValueEqual(writes.getLast().playerId(), player[0].getUUID(), "Statistics key the UUID rather than a player object or name");
        } finally {
            player[0].setHealth(20);
            player[0].teleportTo(test.getLevel(), test.absolutePos(net.minecraft.core.BlockPos.ZERO).getX(),
                    test.absolutePos(net.minecraft.core.BlockPos.ZERO).getY(), test.absolutePos(net.minecraft.core.BlockPos.ZERO).getZ(), Set.of(), 0, 0, true);
            server.getPlayerList().remove(player[0]);
        }
        test.succeed();
    }
}
