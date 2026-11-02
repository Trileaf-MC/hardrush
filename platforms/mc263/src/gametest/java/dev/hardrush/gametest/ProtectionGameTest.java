package dev.hardrush.gametest;

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
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Native, headless protection fixtures for a sealed level; no client or computer use. */
public final class ProtectionGameTest {
    @GameTest(maxTicks=200)
    public void nativeDamageEffectsIgnitionKnockbackAndInventoryAreProtected(GameTestHelper test) {
        var fixture = new Fixture(test);
        var protectedPlayer = fixture.player(0);
        var outside = fixture.player(3);
        try (var ignored = fixture.install()) {
            float before = protectedPlayer.getHealth();
            test.assertTrue(!protectedPlayer.hurtServer(fixture.level, fixture.level.damageSources().playerAttack(outside), 5), "Melee into protected player is rejected before damage");
            test.assertValueEqual(protectedPlayer.getHealth(), before, "Protected health is unchanged");
            test.assertTrue(!outside.hurtServer(fixture.level, fixture.level.damageSources().playerAttack(protectedPlayer), 5), "Melee from a protected player is rejected too");
            protectedPlayer.setRemainingFireTicks(200);
            test.assertTrue(protectedPlayer.getRemainingFireTicks() <= 0, "Flaming arrows and ignition cannot leave fire on protected entities");
            test.assertTrue(!protectedPlayer.addEffect(new MobEffectInstance(MobEffects.POISON, 200), outside), "Harmful splash and source-free effects cannot be applied in protection");
            test.assertTrue(!outside.addEffect(new MobEffectInstance(MobEffects.POISON, 200), protectedPlayer), "Harmful effects cannot originate in protection");
            protectedPlayer.setDeltaMovement(Vec3.ZERO);
            protectedPlayer.knockback(3, 1, 0, fixture.level.damageSources().playerAttack(outside), 1, false);
            test.assertValueEqual(protectedPlayer.getDeltaMovement(), Vec3.ZERO, "Native knockback is rejected");
            test.assertTrue(protectedPlayer.ignoreExplosion(new ServerExplosion(fixture.level, null, null, null,
                    fixture.center.add(3, 0, 0), 4, false, Explosion.BlockInteraction.DESTROY)), "Native explosion admission rejects protected targets before blast impulse");
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
        }
        try (var ignored = fixture.empty()) {
            outside.setInvulnerableTime(0);
            test.assertTrue(outside.hurtServer(fixture.level, fixture.level.damageSources().generic(), 1), "Unprotected levels keep native environmental damage");
        } finally { fixture.close(); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void projectileLaunchSnapshotAndNativeImpactsAreSealed(GameTestHelper test) {
        var fixture = new Fixture(test);
        var inside = fixture.player(0);
        var other = fixture.player(3);
        Arrow reloaded;
        try (var ignored = fixture.install()) {
            var protectedArrow = new Arrow(fixture.level, inside, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            var output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
            protectedArrow.saveWithoutId(output);
            reloaded = new Arrow(EntityTypes.ARROW, fixture.level);
            reloaded.load(TagValueInput.create(ProblemReporter.DISCARDING, fixture.level.registryAccess(), output.buildResult()));
            test.assertTrue(((ProtectionOriginHolder)reloaded).hardrush$protectedOrigin(), "Protected launch snapshot survives native NBT reload");
            var incoming = new TestArrow(fixture.level, other);
            incoming.impact(new EntityHitResult(inside));
            test.assertTrue(incoming.isRemoved(), "Native projectile impact is discarded in a protected level");
            test.assertValueEqual(inside.getHealth(), 20f, "The impact applies no damage");
            test.assertTrue(ProtectionRuntime.rejectProjectilePath(incoming), "Projectile movement in a protected level is rejected");
            protectedArrow.discard();
        }
        try (var ignored = fixture.empty()) {
            test.assertTrue(!other.hurtServer(fixture.level, fixture.level.damageSources().arrow(reloaded, inside), 5), "Lifting protection after a protected launch cannot legitimize the old arrow");
            reloaded.discard();
        } finally { fixture.close(); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void fullHeightBlocksFluidAndExplosionAreSealed(GameTestHelper test) {
        var fixture = new Fixture(test);
        var source = BlockPos.containing(fixture.center.add(3, 0, 0));
        // Setup belongs to an explicit unprotected test scope, never a production bypass.
        try (var setup = fixture.empty()) { fixture.level.setBlock(source, Blocks.WATER.defaultBlockState(), 3); }
        try (var ignored = fixture.install()) {
            var protectedBlock = BlockPos.containing(fixture.center.add(4, 0, 0));
            test.assertTrue(ProtectionRuntime.protectedLevel(fixture.level), "Protection covers the entire level");
            test.assertTrue(!fixture.level.setBlock(protectedBlock, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3), "Player and native block mutations cannot place blocks");
            test.assertTrue(!fixture.level.destroyBlock(protectedBlock.below(), true), "Breaking cannot remove the floor or drop its loot");
            test.assertTrue(!fixture.level.setBlock(protectedBlock.atY(fixture.level.getMaxY() - 2), Blocks.STONE.defaultBlockState(), 3), "Protection extends to maximum build height");
            fixture.level.getFluidState(source).tick(fixture.level, source, fixture.level.getBlockState(source));
            test.assertTrue(fixture.level.getFluidState(protectedBlock).isEmpty(), "Native water cannot spread");
            var explosion = new ServerExplosion(fixture.level, null, null, null, fixture.center, 5, true, Explosion.BlockInteraction.DESTROY);
            test.assertValueEqual(explosion.explode(), 0, "A protected explosion cannot damage or modify anything");
            test.assertTrue(fixture.level.getBlockState(BlockPos.containing(fixture.center).below()).is(Blocks.STONE), "The floor survives native explosion processing");
        } finally { fixture.close(); }
        test.succeed();
    }

    private static void click(ServerPlayer player, int slot, int button, net.minecraft.world.inventory.ContainerInput input) {
        var menu = player.containerMenu;
        player.connection.handleContainerClick(new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
                menu.containerId, menu.getStateId(), (short)slot, (byte)button, input,
                new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), net.minecraft.network.HashedStack.EMPTY));
    }
    private static final class TestArrow extends Arrow {
        TestArrow(ServerLevel level, ServerPlayer owner) { super(level, owner, new ItemStack(Items.ARROW), new ItemStack(Items.BOW)); }
        void impact(EntityHitResult hit) { hitTargetOrDeflectSelf(hit); }
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper test;
        final ServerLevel level;
        final Vec3 center;
        final List<ServerPlayer> players = new ArrayList<>();
        private static final java.util.concurrent.atomic.AtomicInteger NEXT_ARENA = new java.util.concurrent.atomic.AtomicInteger();
        Fixture(GameTestHelper test) {
            this.test = test; level = test.getLevel();
            center = new Vec3(20000.5 + NEXT_ARENA.getAndIncrement() * 128, test.absoluteVec(new Vec3(0, 6, 0)).y, 20000.5);
            try (var ignored = empty()) {
                var feet = BlockPos.containing(center);
                for (int x=-8; x<=8; x++) for (int z=-2; z<=2; z++) {
                    var pos = feet.offset(x,0,z);
                    level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3); level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        ServerPlayer player(int x) {
            var player = test.makeMockServerPlayerInLevel(); players.add(player);
            player.setGameMode(GameType.SURVIVAL); player.getAbilities().invulnerable = false; player.setHealth(20);
            player.setPosRaw(center.x+x, center.y, center.z);
            player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
            return player;
        }
        dev.hardrush.gametest.RuntimeFixtures.Scope empty() {
            return dev.hardrush.gametest.RuntimeFixtures.protection(level.getServer(), candidate -> false);
        }
        dev.hardrush.gametest.RuntimeFixtures.Scope install() {
            return dev.hardrush.gametest.RuntimeFixtures.protection(level.getServer(), candidate -> candidate == level);
        }
        @Override public void close() { for (var player : players) level.getServer().getPlayerList().remove(player); }
    }
}
