package dev.hardrush.gametest;

import dev.hardrush.p0.disguise.DisguiseProbe;
import dev.hardrush.p0.world.WorldProbe;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Real outbound listener/mixins with simulated transport, not visual acceptance. */
public final class MonsterAppearanceGameTest {
    @GameTest(maxTicks = 1200)
    public void twentyApplyClearCyclesPerKindLeaveNoServerState(GameTestHelper test) {
        var player = test.makeMockServerPlayerInLevel();
        var observer = test.makeMockServerPlayerInLevel();
        var server = test.getLevel().getServer();
        var profile = player.getGameProfile();
        var team = player.getTeam();
        var permission = server.getProfilePermissions(new NameAndId(profile));
        var attributes = player.getAttributes().getSyncableAttributes().stream()
            .collect(java.util.stream.Collectors.toMap(a -> a.getAttribute(), a -> a.getValue()));
        try (var owner = new Capture(player)) {
            for (String kind : List.of("zombie", "skeleton", "creeper")) {
                for (int cycle = 0; cycle < 20; cycle++) {
                    owner.packets.clear();
                    DisguiseProbe.apply(player, kind);
                    assertSelected(test, player, observer, kind);
                    DisguiseProbe.clear(player);
                    DisguiseProbe.tick(server);
                    assertCleared(test, player, observer);
                    test.assertTrue(player.getGameProfile() == profile && player.getTeam() == team,
                        "Appearance cycles preserve profile and existing team");
                    test.assertValueEqual(server.getProfilePermissions(new NameAndId(profile)), permission,
                        "Appearance cycles preserve permissions");
                    attributes.forEach((attribute, value) -> test.assertValueEqual(player.getAttributeValue(attribute), value,
                        "Appearance cycles preserve every authoritative attribute"));
                    test.assertValueEqual(owner.packets.stream().filter(p -> p instanceof ClientboundRespawnPacket).count(), 2L,
                        "One native cosmetic refresh for apply and one for clear");
                }
            }
            System.out.println("HardRush appearance lifecycle: apply/clear=60 (20 per kind); no server entity/attribute/team/permission/invisibility leak");
        } finally {
            DisguiseProbe.clear(player);
            server.getPlayerList().remove(player);
            server.getPlayerList().remove(observer);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void twentyNativeDeathRespawnsRebindWithoutOldIds(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var player = test.makeMockServerPlayerInLevel();
        var observer = test.makeMockServerPlayerInLevel();
        try {
            for (int cycle = 0; cycle < 20; cycle++) {
                String kind = List.of("zombie", "skeleton", "creeper").get(cycle % 3);
                DisguiseProbe.apply(player, kind);
                var stale = spawn(player);
                player.setHealth(0);
                player.die(player.damageSources().generic());
                player = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
                DisguiseProbe.tick(server);
                assertSelected(test, player, observer, kind);
                test.assertTrue(player.isAlive() && !player.isInvisible(), "Native respawn leaves one live visible player");
                if (stale.getId() != player.getId()) test.assertTrue(DisguiseProbe.rewrite(observer, stale) == stale,
                    "Every replaced authoritative ID is unbound");
                DisguiseProbe.clear(player);
                assertCleared(test, player, observer);
            }
            System.out.println("HardRush appearance lifecycle: death/native respawn=20; old entity IDs unbound");
        } finally {
            DisguiseProbe.clear(player);
            server.getPlayerList().remove(player);
            server.getPlayerList().remove(observer);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void twentyRoundDimensionCyclesRetainOnlyCurrentBinding(GameTestHelper test) {
        var server = test.getLevel().getServer();
        WorldProbe.create(server, 12345L);
        var player = test.makeMockServerPlayerInLevel();
        var observer = test.makeMockServerPlayerInLevel();
        try {
            for (int cycle = 0; cycle < 20; cycle++) {
                String kind = List.of("zombie", "skeleton", "creeper").get(cycle % 3);
                DisguiseProbe.apply(player, kind);
                for (var semantic : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                    net.minecraft.server.level.ServerLevel level = null;
                    for (var candidate : server.getAllLevels()) {
                        if (WorldProbe.owns(candidate) && WorldProbe.semanticDimension(candidate).equals(semantic)) level = candidate;
                    }
                    test.assertTrue(level != null && WorldProbe.owns(level), "Round dimension exists");
                    player.teleport(new TeleportTransition(level, new Vec3(128.5, semantic.equals(Level.NETHER) ? 100 : 200, 128.5),
                        Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
                    DisguiseProbe.tick(server);
                    assertSelected(test, player, observer, kind);
                    test.assertTrue(player.level() == level, "Appearance follows the native dimension transfer");
                }
                DisguiseProbe.clear(player);
                assertCleared(test, player, observer);
            }
            System.out.println("HardRush appearance lifecycle: round dimension cycles=20; native transfers=60");
        } finally {
            DisguiseProbe.clear(player);
            server.getPlayerList().remove(player);
            server.getPlayerList().remove(observer);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void appearancePreservesNativePlayerPoseDimensions(GameTestHelper test) {
        var player = test.makeMockServerPlayerInLevel();
        try {
            for (var kind : List.of("zombie", "skeleton", "creeper")) {
                DisguiseProbe.apply(player, kind);
                for (var pose : List.of(net.minecraft.world.entity.Pose.STANDING, net.minecraft.world.entity.Pose.CROUCHING,
                        net.minecraft.world.entity.Pose.SWIMMING)) {
                    player.setPose(pose);
                    player.refreshDimensions();
                    var dimensions = player.getDimensions(pose);
                    var box = player.getBoundingBox();
                    float height = switch (pose) { case CROUCHING -> 1.5f; case SWIMMING -> 0.6f; default -> 1.8f; };
                    test.assertTrue(Math.abs(dimensions.width() - 0.6f) < 0.0001 && Math.abs(dimensions.height() - height) < 0.0001,
                        "Monster appearance preserves vanilla pose dimensions");
                    test.assertTrue(Math.abs(box.getXsize() - 0.6) < 0.0001 && Math.abs(box.getYsize() - height) < 0.0001,
                        "Actual server collision box matches the native player pose");
                }
            }
            for (var type : List.of(EntityTypes.PLAYER, EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.CREEPER)) {
                System.out.println("HardRush appearance dimensions: " + type + "=" + type.getWidth() + "x" + type.getHeight());
            }
        } finally {
            DisguiseProbe.clear(player);
            player.level().getServer().getPlayerList().remove(player);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 2400)
    public void twentySameUuidNativeLoginLoadsRetainSelectionAndClear(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var player = new ServerPlayer[] { test.makeMockServerPlayerInLevel() };
        var observer = test.makeMockServerPlayerInLevel();
        var profile = player[0].getGameProfile();
        var preparation = new PrepareSpawnTask[1];
        var channel = new EmbeddedChannel[1];
        var previousSpawn = new ClientboundAddEntityPacket[1];
        var sequence = test.startSequence();
        for (int cycle = 0; cycle < 20; cycle++) {
            String kind = List.of("zombie", "skeleton", "creeper").get(cycle % 3);
            sequence.thenExecute(() -> {
                DisguiseProbe.apply(player[0], kind);
                previousSpawn[0] = spawn(player[0]);
                DisguiseProbe.onDisconnect(player[0]);
                test.assertTrue(DisguiseProbe.rewrite(observer, previousSpawn[0]) == previousSpawn[0], "Disconnect unbinds the old entity");
                server.getPlayerList().remove(player[0]);
                if (channel[0] != null) channel[0].finishAndReleaseAll();
                preparation[0] = new PrepareSpawnTask(server, new NameAndId(profile));
                preparation[0].start(packet -> {});
            }).thenWaitUntil(() -> test.assertTrue(preparation[0].tick(), "Native login player-data load is ready"))
                .thenExecute(() -> {
                    var connection = new RecordingConnection();
                    channel[0] = new EmbeddedChannel(connection);
                    player[0] = preparation[0].spawnPlayer(connection, CommonListenerCookie.createInitial(profile, false));
                    preparation[0].close(); preparation[0] = null;
                    DisguiseProbe.onJoin(player[0]);
                    DisguiseProbe.tick(server);
                    test.assertValueEqual(player[0].getUUID(), profile.id(), "Native reconnect retains UUID");
                    assertSelected(test, player[0], observer, kind);
                    if (previousSpawn[0].getId() != player[0].getId()) test.assertTrue(DisguiseProbe.rewrite(observer, previousSpawn[0]) == previousSpawn[0],
                        "Reconnect does not revive the old entity mapping");
                    DisguiseProbe.clear(player[0]);
                    assertCleared(test, player[0], observer);
                });
        }
        sequence.thenExecute(() -> {
            server.getPlayerList().remove(player[0]);
            server.getPlayerList().remove(observer);
            channel[0].finishAndReleaseAll();
            System.out.println("HardRush appearance lifecycle: same UUID native save/login loads=20; disconnected bindings removed");
        }).thenSucceed();
    }

    private static void assertSelected(GameTestHelper test, ServerPlayer player, ServerPlayer observer, String kind) {
        var type = switch (kind) { case "zombie" -> EntityTypes.ZOMBIE; case "skeleton" -> EntityTypes.SKELETON; default -> EntityTypes.CREEPER; };
        test.assertValueEqual(((ClientboundAddEntityPacket) DisguiseProbe.rewrite(observer, spawn(player))).getType(), type,
            "Observer gets the current selected mob");
        test.assertTrue(!player.isInvisible() && player.getCamera() == player, "Owner remains visible with native camera");
        for (var level : player.level().getServer().getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity.getUUID().equals(player.getUUID())) test.assertTrue(entity == player,
                    "No duplicate entity with the player's UUID exists in any level");
            }
        }
    }

    private static void assertCleared(GameTestHelper test, ServerPlayer player, ServerPlayer observer) {
        test.assertTrue(DisguiseProbe.status(player).startsWith("disguise=off"), "Role appearance selection removed");
        test.assertValueEqual(((ClientboundAddEntityPacket) DisguiseProbe.rewrite(observer, spawn(player))).getType(), EntityTypes.PLAYER,
            "Clear restores native player tracking");
        test.assertTrue(!player.isInvisible() && player.getCamera() == player, "Clear leaves native visibility and camera");
    }

    @GameTest(maxTicks = 200)
    public void observerOnlyPacketsAndStevePreserveAuthoritativeState(GameTestHelper test) {
        var owner = test.makeMockServerPlayerInLevel();
        var observer = test.makeMockServerPlayerInLevel();
        try (var owned = new Capture(owner); var watched = new Capture(observer)) {
            owner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
            owner.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            owner.setHealth(17);
            owner.getFoodData().setFoodLevel(13);
            owner.experienceLevel = 7;
            owner.totalExperience = 91;
            owner.addEffect(new MobEffectInstance(MobEffects.SPEED, 1200));
            owner.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.13);
            var profile = owner.getGameProfile();
            var position = owner.position();
            var id = owner.getId();
            for (String kind : List.of("zombie", "skeleton", "creeper")) {
                owned.packets.clear();
                DisguiseProbe.apply(owner, kind);
                test.assertTrue(owner.getGameProfile() == profile, "Authenticated profile object is unchanged");
                test.assertValueEqual(owner.getId(), id, "No server-side respawn or replacement player");
                test.assertValueEqual(owner.position(), position, "Skin refresh preserves position");
                test.assertValueEqual(owner.getHealth(), 17f, "Health is preserved");
                test.assertValueEqual(owner.getFoodData().getFoodLevel(), 13, "Food is preserved");
                test.assertValueEqual(owner.experienceLevel, 7, "Experience is preserved");
                test.assertTrue(owner.hasEffect(MobEffects.SPEED), "Effects survive cosmetic refresh");
                test.assertTrue(owner.getMainHandItem().is(Items.DIAMOND_SWORD)
                    && owner.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "Equipment remains owned");
                test.assertTrue(!owner.isInvisible(), "No authoritative invisibility");
                test.assertTrue(owned.packets.stream().noneMatch(p -> p instanceof ClientboundAddEntityPacket
                    || p instanceof ClientboundSetPlayerTeamPacket || p instanceof ClientboundSetCameraPacket),
                    "Owner receives no self mob, private team or camera replacement");
                if (kind.equals("zombie")) {
                    var selfInfo = owned.packets.stream().filter(p -> p instanceof ClientboundPlayerInfoUpdatePacket)
                        .map(p -> (ClientboundPlayerInfoUpdatePacket) p).findFirst().orElseThrow();
                    var selfEntry = selfInfo.entries().getFirst();
                    test.assertValueEqual(selfEntry.profileId(), profile.id(), "Cosmetic profile preserves UUID");
                    test.assertValueEqual(selfEntry.profile().name(), profile.name(), "Cosmetic profile preserves name");
                    test.assertTrue(!selfEntry.profile().properties().get("textures").isEmpty(), "Steve property sent only to owner");
                    test.assertTrue(owned.packets.stream().anyMatch(p -> p instanceof ClientboundRespawnPacket r
                        && r.dataToKeep() == ClientboundRespawnPacket.KEEP_ALL_DATA
                        && r.commonPlayerSpawnInfo().dimension().equals(owner.level().dimension())), "Native same-dimension client refresh");
                } else {
                    test.assertTrue(owned.packets.stream().noneMatch(p -> p instanceof ClientboundRespawnPacket),
                        "Changing monster kind does not reset the owner client again");
                }

                var spawn = spawn(owner);
                owner.connection.send(spawn);
                test.assertTrue(owned.packets.getLast() == spawn, "Owner's native player packet passes through");
                observer.connection.send(spawn);
                var transformed = (ClientboundAddEntityPacket) watched.packets.getLast();
                test.assertValueEqual(transformed.getType(), switch (kind) {
                    case "zombie" -> EntityTypes.ZOMBIE;
                    case "skeleton" -> EntityTypes.SKELETON;
                    default -> EntityTypes.CREEPER;
                }, "Other observer sees selected mob");
                test.assertValueEqual(transformed.getId(), owner.getId(), "One authoritative combat ID");
                test.assertValueEqual(transformed.getUUID(), owner.getUUID(), "Visible mob retains player identity");
                test.assertValueEqual(spawn.getType(), EntityTypes.PLAYER, "Original shared spawn packet not mutated");

                var metadata = new ClientboundSetEntityDataPacket(owner.getId(), owner.getEntityData().getNonDefaultValues());
                test.assertTrue(DisguiseProbe.rewrite(owner, metadata) == metadata, "Own metadata is never hidden or remapped");
                var nested = new ClientboundBundlePacket(List.of(spawn, metadata));
                var copy = (ClientboundBundlePacket) DisguiseProbe.rewrite(observer, nested);
                test.assertTrue(copy != nested, "Bundled tracking packets get an independent copy");
                var originalInfo = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(owner, observer));
                test.assertTrue(DisguiseProbe.rewrite(observer, originalInfo) == originalInfo, "Steve does not leak to another observer");
                var attributes = new ClientboundUpdateAttributesPacket(owner.getId(), owner.getAttributes().getSyncableAttributes());
                var displayed = (ClientboundUpdateAttributesPacket) DisguiseProbe.rewrite(observer, attributes);
                var speed = displayed.getValues().stream().filter(a -> a.attribute().equals(Attributes.MOVEMENT_SPEED)).findFirst().orElseThrow();
                test.assertValueEqual(speed.base(), 0.13, "Mob display uses authoritative player speed");
                int sent = owned.packets.size();
                DisguiseProbe.apply(owner, kind);
                test.assertValueEqual(owned.packets.size(), sent, "Repeated apply is idempotent");
            }
            DisguiseProbe.clear(owner);
            test.assertTrue(DisguiseProbe.rewrite(observer, spawn(owner)).getClass() == ClientboundAddEntityPacket.class,
                "Clear removes observer mapping");
            test.assertValueEqual(((ClientboundAddEntityPacket) DisguiseProbe.rewrite(observer, spawn(owner))).getType(), EntityTypes.PLAYER,
                "Clear restores native player appearance");
            var restored = owned.packets.stream().filter(p -> p instanceof ClientboundPlayerInfoUpdatePacket)
                .map(p -> (ClientboundPlayerInfoUpdatePacket) p).reduce((a, b) -> b).orElseThrow();
            test.assertValueEqual(restored.entries().getFirst().profile(), profile, "Clear restores original cosmetic profile");
        } finally {
            DisguiseProbe.clear(owner);
            owner.level().getServer().getPlayerList().remove(owner);
            observer.level().getServer().getPlayerList().remove(observer);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void deathRespawnAndReconnectRebindTheSelectedAppearance(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var player = test.makeMockServerPlayerInLevel();
        var observer = test.makeMockServerPlayerInLevel();
        try {
            DisguiseProbe.apply(player, "skeleton");
            int oldId = player.getId();
            player.setHealth(0);
            player.die(player.damageSources().generic());
            DisguiseProbe.tick(server);
            test.assertTrue(DisguiseProbe.status(player).contains("disguise=skeleton"), "Death does not erase selected role appearance");
            player = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            DisguiseProbe.tick(server);
            test.assertValueEqual(((ClientboundAddEntityPacket) DisguiseProbe.rewrite(observer, spawn(player))).getType(), EntityTypes.SKELETON,
                "Vanilla respawn rebinds new authoritative player");
            var stale = new ClientboundAddEntityPacket(oldId, player.getUUID(), 0, 0, 0, 0, 0, EntityTypes.PLAYER, 0, Vec3.ZERO, 0);
            if (oldId != player.getId()) test.assertTrue(DisguiseProbe.rewrite(observer, stale) == stale, "Old ID mapping is gone");
            DisguiseProbe.onDisconnect(player);
            var disconnectedSpawn = spawn(player);
            test.assertTrue(DisguiseProbe.rewrite(observer, disconnectedSpawn) == disconnectedSpawn, "Disconnected binding removed");
            DisguiseProbe.onJoin(player);
            DisguiseProbe.tick(server);
            test.assertValueEqual(((ClientboundAddEntityPacket) DisguiseProbe.rewrite(observer, spawn(player))).getType(), EntityTypes.SKELETON,
                "Same UUID reconnect resumes selection");
        } finally {
            DisguiseProbe.clear(player);
            server.getPlayerList().remove(player);
            server.getPlayerList().remove(observer);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void ridingDefersCosmeticRefreshWithoutClearingAppearance(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var player = test.makeMockServerPlayerInLevel();
        var vehicle = EntityTypes.PIG.create(player.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        try (var capture = new Capture(player)) {
            vehicle.snapTo(player.position());
            player.level().addFreshEntity(vehicle);
            test.assertTrue(player.startRiding(vehicle, true, false), "Player can mount test vehicle");
            capture.packets.clear();
            DisguiseProbe.apply(player, "creeper");
            test.assertTrue(player.isPassenger() && DisguiseProbe.status(player).contains("disguise=creeper"), "Riding keeps monster appearance");
            test.assertTrue(capture.packets.stream().noneMatch(p -> p instanceof ClientboundRespawnPacket), "No disruptive refresh while riding");
            player.stopRiding();
            DisguiseProbe.tick(server);
            test.assertTrue(capture.packets.stream().anyMatch(p -> p instanceof ClientboundRespawnPacket), "Steve applied at safe point");
        } finally {
            player.stopRiding();
            DisguiseProbe.clear(player);
            server.getPlayerList().remove(player);
            if (vehicle != null) vehicle.discard();
        }
        test.succeed();
    }

    private static ClientboundAddEntityPacket spawn(ServerPlayer player) {
        return new ClientboundAddEntityPacket(player.getId(), player.getUUID(), player.getX(), player.getY(), player.getZ(),
            player.getXRot(), player.getYRot(), EntityTypes.PLAYER, 0, player.getDeltaMovement(), player.getYHeadRot());
    }

    private static final class Capture implements AutoCloseable {
        final ServerPlayer player;
        final ServerGamePacketListenerImpl original;
        final ArrayList<Packet<?>> packets;
        final EmbeddedChannel channel;
        Capture(ServerPlayer player) {
            this.player = player; original = player.connection;
            var connection = new RecordingConnection();
            packets = connection.packets; channel = new EmbeddedChannel(connection);
            player.connection = new ServerGamePacketListenerImpl(player.level().getServer(), connection, player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }
        @Override public void close() { player.connection = original; channel.finishAndReleaseAll(); }
    }

    private static final class RecordingConnection extends Connection {
        final ArrayList<Packet<?>> packets = new ArrayList<>();
        RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        private void record(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(this::record);
            else packets.add(packet);
        }
        @Override public void send(Packet<?> packet) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { record(packet); }
    }
}
