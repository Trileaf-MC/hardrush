package dev.hardrush.gametest;

import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.protection.ProtectionRuntime;
import dev.hardrush.protection.ProtectionOriginHolder;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Native, headless S01/S02 protection fixtures; no client or computer use. */
public final class ProtectionGameTest {
    @GameTest(maxTicks=200)
    public void nativeDamageEffectsIgnitionKnockbackAndInventoryAreProtected(GameTestHelper test) {
        var fixture = new Fixture(test);
        var protectedPlayer = fixture.player(0, false, 1);
        var outside = fixture.player(35, true, 1);
        try (var ignored = fixture.install()) {
            float before = protectedPlayer.getHealth();
            test.assertTrue(!protectedPlayer.hurtServer(fixture.level, fixture.level.damageSources().playerAttack(outside), 5), "Melee into protected player is rejected before damage");
            test.assertValueEqual(protectedPlayer.getHealth(), before, "Protected health is unchanged");
            test.assertTrue(!outside.hurtServer(fixture.level, fixture.level.damageSources().playerAttack(protectedPlayer), 5), "Melee out of protection is rejected too");
            protectedPlayer.setRemainingFireTicks(200);
            test.assertTrue(protectedPlayer.getRemainingFireTicks() <= 0, "Flaming arrows and ignition cannot leave fire on protected entities");
            test.assertTrue(!protectedPlayer.addEffect(new MobEffectInstance(MobEffects.POISON, 200), outside), "Harmful splash and source-free effects cannot be applied in protection");
            test.assertTrue(!outside.addEffect(new MobEffectInstance(MobEffects.POISON, 200), protectedPlayer), "Harmful effects cannot originate in protection");
            protectedPlayer.setDeltaMovement(Vec3.ZERO);
            protectedPlayer.knockback(3, 1, 0, fixture.level.damageSources().playerAttack(outside), 1, false);
            test.assertValueEqual(protectedPlayer.getDeltaMovement(), Vec3.ZERO, "Native knockback is rejected");
            test.assertTrue(protectedPlayer.ignoreExplosion(new ServerExplosion(fixture.level, null, null, null,
                    fixture.center.add(33, 0, 0), 4, false, Explosion.BlockInteraction.DESTROY)), "Native explosion admission rejects protected targets before blast impulse");
            protectedPlayer.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            protectedPlayer.drop(false);
            test.assertValueEqual(protectedPlayer.getInventory().getItem(0).getCount(), 3, "Q drop cannot remove a protected inventory stack");
            click(protectedPlayer, 36, 0, net.minecraft.world.inventory.ContainerInput.THROW);
            test.assertValueEqual(protectedPlayer.getInventory().getItem(0).getCount(), 3, "Native inventory THROW packet cannot discard a protected stack");
            click(protectedPlayer, 36, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);
            test.assertTrue(protectedPlayer.getInventory().getItem(0).isEmpty(), "Personal inventory normal PICKUP remains available");
            test.assertValueEqual(protectedPlayer.inventoryMenu.getCarried().getCount(), 3, "Normal pickup moves the stack to the native cursor");
            click(protectedPlayer, -999, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);
            test.assertValueEqual(protectedPlayer.inventoryMenu.getCarried().getCount(), 3, "Clicking outside cannot discard the carried stack");
            for (int header : new int[]{0, 1, 2}) {
                click(protectedPlayer, header == 1 ? 37 : -999,
                        net.minecraft.world.inventory.AbstractContainerMenu.getQuickcraftMask(header, 0),
                        net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT);
            }
            test.assertTrue(protectedPlayer.inventoryMenu.getCarried().isEmpty(), "Native drag begin/end control packets remain available");
            test.assertValueEqual(protectedPlayer.getInventory().getItem(1).getCount(), 3, "Native drag sorts the owned stack into another personal slot");
            click(protectedPlayer, 37, 2, net.minecraft.world.inventory.ContainerInput.SWAP);
            test.assertValueEqual(protectedPlayer.getInventory().getItem(2).getCount(), 3, "Personal hotbar SWAP remains available");
            var external = net.minecraft.world.inventory.ChestMenu.threeRows(71, protectedPlayer.getInventory(), new net.minecraft.world.SimpleContainer(27));
            external.getSlot(0).set(new ItemStack(Items.EMERALD));
            protectedPlayer.containerMenu = external;
            click(protectedPlayer, 0, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);
            test.assertTrue(external.getSlot(0).getItem().is(Items.EMERALD), "A stale external loot-container click remains sealed in protection");
            protectedPlayer.containerMenu = protectedPlayer.inventoryMenu;
            var item = new ItemEntity(fixture.level, fixture.center.x, fixture.center.y, fixture.center.z, new ItemStack(Items.EMERALD));
            item.setNoPickUpDelay(); item.playerTouch(protectedPlayer);
            test.assertTrue(!item.getItem().isEmpty(), "Native collision cannot pick up loot in protection"); item.discard();
            outside.setInvulnerableTime(0);
            test.assertTrue(outside.hurtServer(fixture.level, fixture.level.damageSources().generic(), 1), "Outside environmental damage keeps its native behavior");
        } finally { fixture.close(); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void projectileLaunchSnapshotAndNativeImpactsCannotCrossProtection(GameTestHelper test) {
        var fixture = new Fixture(test);
        var inside = fixture.player(0, false, 1);
        var outside = fixture.player(35, true, 1);
        try (var ignored = fixture.install()) {
            var protectedArrow = new Arrow(fixture.level, inside, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            var output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
            protectedArrow.saveWithoutId(output);
            var reloaded = new Arrow(EntityTypes.ARROW, fixture.level);
            reloaded.load(TagValueInput.create(ProblemReporter.DISCARDING, fixture.level.registryAccess(), output.buildResult()));
            reloaded.setPosRaw(fixture.center.x + 35, fixture.center.y, fixture.center.z);
            inside.setPosRaw(fixture.center.x + 36, fixture.center.y, fixture.center.z);
            test.assertTrue(((ProtectionOriginHolder)reloaded).hardrush$protectedOrigin(), "Protected launch snapshot survives native NBT reload");
            test.assertTrue(!outside.hurtServer(fixture.level, fixture.level.damageSources().arrow(reloaded, inside), 5), "Leaving or disconnecting after a protected launch cannot legitimize the old arrow");
            var incoming = new TestArrow(fixture.level, outside);
            incoming.setPosRaw(fixture.center.x + 35, fixture.center.y, fixture.center.z);
            inside.setPosRaw(fixture.center.x + 31, fixture.center.y, fixture.center.z);
            incoming.impact(new EntityHitResult(inside));
            test.assertTrue(incoming.isRemoved(), "Native projectile impact is discarded at a protected crossing");
            test.assertValueEqual(inside.getHealth(), 20f, "The impact applies no damage");
            var through = new TestArrow(fixture.level, outside);
            through.setPosRaw(fixture.center.x + 35, fixture.center.y, fixture.center.z);
            test.assertTrue(ProtectionRuntime.rejectProjectilePath(through, fixture.center.add(-35, 0, 0)), "An outside-to-outside shot cannot tunnel through the full zone");
            protectedArrow.discard(); reloaded.discard(); through.discard();
        } finally { fixture.close(); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void fullHeightBlocksFluidExplosionAndLobbyAreSealed(GameTestHelper test) {
        var fixture = new Fixture(test);
        var source = BlockPos.containing(fixture.center.add(33, 0, 0));
        // Setup belongs to an explicit unprotected test scope, never a production bypass.
        try (var setup = fixture.empty()) { fixture.level.setBlock(source, Blocks.WATER.defaultBlockState(), 3); }
        try (var ignored = fixture.install()) {
            var protectedBlock = BlockPos.containing(fixture.center.add(32, 0, 0));
            test.assertTrue(!fixture.level.setBlock(protectedBlock, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3), "Player and native block mutations cannot place at the boundary");
            test.assertTrue(!fixture.level.destroyBlock(protectedBlock.below(), true), "Breaking cannot remove protection's floor or drop its loot");
            test.assertTrue(!fixture.level.setBlock(protectedBlock.atY(fixture.level.getMaxY() - 2), Blocks.STONE.defaultBlockState(), 3), "Protection extends to maximum build height");
            fixture.level.getFluidState(source).tick(fixture.level, source, fixture.level.getBlockState(source));
            test.assertTrue(fixture.level.getFluidState(protectedBlock).isEmpty(), "Native outside water cannot spread across the boundary");
            var explosion = new ServerExplosion(fixture.level, null, null, null, fixture.center, 5, true, Explosion.BlockInteraction.DESTROY);
            test.assertValueEqual(explosion.explode(), 0, "A protected explosion cannot damage or modify the outside");
            test.assertTrue(fixture.level.getBlockState(BlockPos.containing(fixture.center).below()).is(Blocks.STONE), "Spawn floor survives native explosion processing");
        }
        fixture.lobby = true;
        try (var ignored = fixture.install()) {
            test.assertTrue(ProtectionRuntime.protectedAt(fixture.level, fixture.center.add(10000, -10000, 10000)), "Lobby protection covers its entire world and all heights");
            test.assertTrue(!fixture.level.setBlock(source, Blocks.STONE.defaultBlockState(), 3), "Lobby native mutation gate is active");
        } finally { fixture.close(); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void exitCommitReentryTeleportVehicleAndNewLifeUseOnePermission(GameTestHelper test) {
        var fixture = new Fixture(test);
        var player = fixture.player(31, false, 1);
        try (var ignored = fixture.install()) {
            ProtectionRuntime.tick(fixture.level.getServer());
            var outside = fixture.center.add(35, 0, 0);
            test.assertTrue(!player.teleportTo(fixture.level, outside.x, outside.y, outside.z, Set.of(), 0, 0, true), "First exit waits for durable admission");
            test.assertValueEqual(fixture.requests, 1, "One authoritative leave request is accepted");
            test.assertTrue(!fixture.participants.get(player.getUUID()).hasLeftProtection(), "No in-memory movement manufactures a committed permission");
            fixture.commitExit(player);
            test.assertTrue(player.teleportTo(fixture.level, outside.x, outside.y, outside.z, Set.of(), 0, 0, true), "Committed exit can leave the protection once");
            // No server tick may intervene: repeated input in the departure tick must not correct
            // the player back to the protected-side position remembered before the exit.
            var inside = fixture.center.add(31, 0, 0);
            test.assertTrue(!player.teleportTo(fixture.level, inside.x, inside.y, inside.z, Set.of(), 0, 0, true), "Direct/pearl teleport cannot reenter in the departure tick");
            test.assertTrue(!ProtectionRuntime.protectedEntity(player), "Correction cannot use the old inside anchor after same-tick exit");
            ProtectionRuntime.tick(fixture.level.getServer());
            var transition = new TeleportTransition(fixture.level, inside, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING);
            test.assertTrue(player.teleport(transition) == null, "Native portal transition uses the same reentry gate");
            var boat = EntityTypes.OAK_BOAT.create(fixture.level, EntitySpawnReason.COMMAND);
            boat.setPosRaw(outside.x, outside.y, outside.z);
            test.assertTrue(player.startRiding(boat, true, true), "Native passenger fixture mounts its vehicle");
            test.assertTrue(ProtectionRuntime.rejectMove(boat, fixture.level, inside), "A vehicle cannot bypass the passenger's one-way boundary");
            player.stopRiding(); boat.discard();
            fixture.participants.put(player.getUUID(), human(player, false, 2));
            // Native respawn places the replacement before the new life is admitted, not a player teleport.
            player.setPosRaw(inside.x, inside.y, inside.z);
            ProtectionRuntime.tick(fixture.level.getServer());
            test.assertTrue(ProtectionRuntime.protectedEntity(player), "A legal committed new life has fresh protection without old-life exile");
            test.assertTrue(!player.teleportTo(fixture.level, outside.x, outside.y, outside.z, Set.of(), 0, 0, true), "The new life needs its own durable exit");
            test.assertValueEqual(fixture.requests, 2, "One new permission request is made for the new life");
        } finally { fixture.close(); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void outsideRespawnReconnectAndMonsterEntryAreSafelyGuarded(GameTestHelper test) {
        var fixture = new Fixture(test);
        var player = fixture.player(35, false, 2);
        try (var ignored = fixture.install()) {
            var inside = fixture.center.add(31, 0, 0);
            test.assertTrue(!player.teleportTo(fixture.level, inside.x, inside.y, inside.z, Set.of(), 0, 0, true), "An outside bed/anchor respawn cannot enter while its flag commit is pending");
            test.assertValueEqual(fixture.requests, 1, "Outside respawn is durably marked as already outside");
            fixture.commitExit(player);
            ProtectionRuntime.tick(fixture.level.getServer());
            player.setPosRaw(inside.x, inside.y, inside.z);
            player.setRemainingFireTicks(80); player.setDeltaMovement(new Vec3(0, -3, 0));
            ProtectionRuntime.tick(fixture.level.getServer());
            test.assertTrue(!ProtectionRuntime.protectedEntity(player), "Raw load or reconnect inside cannot bypass the persisted leave flag");
            test.assertTrue(standingPositionSafe(player), "Illegal entry returns to a real safe loaded standing position");
            test.assertValueEqual(player.getDeltaMovement(), Vec3.ZERO, "Safe return removes residual fall/knockback velocity");
            fixture.participants.put(player.getUUID(), new Participant(player.getUUID(), player.getScoreboardName(), ParticipantRole.MONSTER_DEPLOYED,
                    true, false, 0, 0, 2, false, true));
            test.assertTrue(!player.teleportTo(fixture.level, inside.x, inside.y, inside.z, Set.of(), 0, 0, true), "A player monster can never enter spawn protection");
        } finally { fixture.close(); }
        test.succeed();
    }

    private static void click(ServerPlayer player, int slot, int button, net.minecraft.world.inventory.ContainerInput input) {
        var menu = player.containerMenu;
        player.connection.handleContainerClick(new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
                menu.containerId, menu.getStateId(), (short)slot, (byte)button, input,
                new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), net.minecraft.network.HashedStack.EMPTY));
    }
    private static boolean standingPositionSafe(ServerPlayer player) {
        return dev.hardrush.world.RoundWorldBundle.safeStanding(player.level(), player.blockPosition());
    }
    private static Participant human(ServerPlayer player, boolean exited, int life) {
        return new Participant(player.getUUID(), player.getScoreboardName(), ParticipantRole.HUMAN_ACTIVE,
                true, false, 0, 0, life, false, exited);
    }
    private static final class TestArrow extends Arrow {
        TestArrow(ServerLevel level, ServerPlayer owner) { super(level, owner, new ItemStack(Items.ARROW), new ItemStack(Items.BOW)); }
        void impact(EntityHitResult hit) { hitTargetOrDeflectSelf(hit); }
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper test;
        final ServerLevel level;
        final Vec3 center;
        final UUID roundId = UUID.randomUUID();
        final Map<UUID, Participant> participants = new HashMap<>();
        final List<ServerPlayer> players = new ArrayList<>();
        private static final java.util.concurrent.atomic.AtomicInteger NEXT_ARENA = new java.util.concurrent.atomic.AtomicInteger();
        int requests;
        boolean lobby;
        Fixture(GameTestHelper test) {
            this.test = test; level = test.getLevel();
            center = new Vec3(20000.5 + NEXT_ARENA.getAndIncrement() * 128, test.absoluteVec(new Vec3(0, 6, 0)).y, 20000.5);
            try (var ignored = empty()) {
                var feet = BlockPos.containing(center);
                for (int x=-42; x<=42; x++) for (int z=-2; z<=2; z++) {
                    var pos = feet.offset(x,0,z);
                    level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3); level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        ServerPlayer player(int x, boolean exited, int life) {
            var player = test.makeMockServerPlayerInLevel(); players.add(player);
            player.setGameMode(GameType.SURVIVAL); player.getAbilities().invulnerable = false; player.setHealth(20);
            player.setPosRaw(center.x+x, center.y, center.z);
            player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
            participants.put(player.getUUID(), human(player, exited, life)); return player;
        }
        dev.hardrush.gametest.RuntimeFixtures.Scope empty() {
            return dev.hardrush.gametest.RuntimeFixtures.protection(level.getServer(), access(false));
        }
        dev.hardrush.gametest.RuntimeFixtures.Scope install() {
            return dev.hardrush.gametest.RuntimeFixtures.protection(level.getServer(), access(true));
        }
        ProtectionRuntime.Access access(boolean enabled) {
            return new ProtectionRuntime.Access() {
                @Override public Optional<ProtectionRuntime.Zone> zone(ServerLevel candidate) {
                    return enabled && candidate == level ? Optional.of(new ProtectionRuntime.Zone(roundId, center, 32, lobby)) : Optional.empty();
                }
                @Override public Optional<Participant> participant(UUID id) { return Optional.ofNullable(participants.get(id)); }
                @Override public boolean leaveProtection(UUID round, UUID id, int life) {
                    test.assertValueEqual(round, roundId, "Durable leave carries the exact round tag");
                    test.assertValueEqual(participants.get(id).lifeSequence(), life, "Durable leave carries the exact life tag"); requests++; return true;
                }
                @Override public void quarantine(ServerPlayer player) { throw new IllegalStateException("Fixture should have a loaded safe standing point"); }
            };
        }
        void commitExit(ServerPlayer player) {
            var prior = participants.get(player.getUUID());
            participants.put(player.getUUID(), human(player, true, prior.lifeSequence()));
        }
        @Override public void close() { for (var player : players) level.getServer().getPlayerList().remove(player); }
    }
}
