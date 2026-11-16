package dev.hardrush.gametest;

import dev.hardrush.protection.ProtectionRuntime;
import dev.hardrush.protection.ProtectionOriginHolder;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
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

    /** Lobby decoration (doors, buttons, levers, lamps, plates, note blocks) stays usable in a sealed level; everything else stays refused. */
    @GameTest(maxTicks=200)
    public void interactiveDecorationWorksInSealedLevel(GameTestHelper test) {
        var fixture = new Fixture(test);
        var base = BlockPos.containing(fixture.center);
        BlockPos oakDoor = base.offset(-6, 0, 0), ironDoor = base.offset(-8, 0, 0);
        BlockPos button = base.offset(-4, 0, 0), wire = base.offset(-3, 0, 0), lamp = base.offset(-2, 0, 0);
        BlockPos lever = base.offset(0, 0, 0), leverLamp = base.offset(1, 0, 0);
        BlockPos noteBlock = base.offset(3, 0, 0), plate = test.absolutePos(new BlockPos(1, 2, 1)), piston = base.offset(7, 0, 0);
        BlockPos redstoneBlock = base.offset(8, 0, 0);
        try (var setup = fixture.empty()) {
            for (var door : List.of(oakDoor, ironDoor)) {
                var block = door == oakDoor ? Blocks.OAK_DOOR : Blocks.IRON_DOOR;
                fixture.level.setBlock(door, block.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
                fixture.level.setBlock(door.above(), block.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
            }
            fixture.level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
            fixture.level.setBlock(wire, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
            fixture.level.setBlock(button, Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR), 3);
            fixture.level.setBlock(leverLamp, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
            fixture.level.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR), 3);
            fixture.level.setBlock(noteBlock, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
            // Chunks around the arena do not expose entities to block queries, so the plate sits inside the test structure.
            fixture.level.setBlock(plate.below(), Blocks.STONE.defaultBlockState(), 3);
            fixture.level.setBlock(plate, Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), 3);
            fixture.level.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP), 3);
        }
        var player = fixture.player(0);
        // Entities only become visible to block queries a few ticks after spawning, so the plate is checked in a second step.
        var plateItem = fixture.player(5);
        plateItem.setPos(plate.getX() + 0.5, plate.getY(), plate.getZ() + 0.5);
        try (var ignored = fixture.install()) {
            // Right click opens and closes a door; sneaking keeps the block untouched.
            test.assertTrue(!fixture.level.getBlockState(oakDoor).getValue(DoorBlock.OPEN), "Door starts closed");
            use(player, oakDoor, Direction.SOUTH, false);
            test.assertTrue(fixture.level.getBlockState(oakDoor).getValue(DoorBlock.OPEN), "Right click opens the door");
            use(player, oakDoor, Direction.SOUTH, false);
            test.assertTrue(!fixture.level.getBlockState(oakDoor).getValue(DoorBlock.OPEN), "Right click closes the door again");
            use(player, oakDoor, Direction.SOUTH, true);
            test.assertTrue(!fixture.level.getBlockState(oakDoor).getValue(DoorBlock.OPEN), "Sneaking right click leaves the door alone");

            // Held items never act on the world: neither through a sneaking click nor through a block that ignores the hand.
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 4));
            use(player, oakDoor, Direction.EAST, true);
            test.assertTrue(fixture.level.getBlockState(oakDoor.east()).isAir(), "Sneaking with a block does not place it beside the door");
            use(player, base.below().offset(-1, 0, 0), Direction.UP, false);
            test.assertTrue(fixture.level.getBlockState(base.offset(-1, 0, 0)).isAir(), "A block cannot be placed on the floor");
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PIG_SPAWN_EGG));
            use(player, ironDoor, Direction.EAST, false);
            test.assertTrue(fixture.level.getBlockState(ironDoor).is(Blocks.IRON_DOOR) && !fixture.level.getBlockState(ironDoor).getValue(DoorBlock.OPEN), "Vanilla ignores a hand click on an iron door");
            test.assertTrue(fixture.level.getEntitiesOfClass(net.minecraft.world.entity.animal.pig.Pig.class, new net.minecraft.world.phys.AABB(ironDoor).inflate(3)).isEmpty(),
                    "A spawn egg passing through an iron door click does not act on the sealed level");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

            // Button to lamp through redstone dust; the button pops back by its own scheduled tick.
            use(player, button, Direction.UP, false);
            test.assertTrue(fixture.level.getBlockState(button).getValue(ButtonBlock.POWERED), "Button is pressed");
            test.assertTrue(fixture.level.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "Dust carries the press to the lamp");
            fixture.level.getBlockState(button).tick(fixture.level, button, fixture.level.getRandom());
            fixture.level.getBlockState(lamp).tick(fixture.level, lamp, fixture.level.getRandom());
            test.assertTrue(!fixture.level.getBlockState(button).getValue(ButtonBlock.POWERED), "Button pops back");
            test.assertTrue(!fixture.level.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "Lamp goes out after the button releases");

            use(player, lever, Direction.UP, false);
            test.assertTrue(fixture.level.getBlockState(leverLamp).getValue(RedstoneLampBlock.LIT), "Lever lights the lamp next to it");

            int note = fixture.level.getBlockState(noteBlock).getValue(NoteBlock.NOTE);
            use(player, noteBlock, Direction.UP, false);
            test.assertValueEqual(fixture.level.getBlockState(noteBlock).getValue(NoteBlock.NOTE), note + 1, "Right click tunes the note block");


            // Everything that replaces, removes or moves blocks stays sealed.
            ProtectionRuntime.ownedWrite(fixture.level, () -> fixture.level.setBlock(redstoneBlock, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3));
            test.assertTrue(!fixture.level.getBlockState(piston).getValue(BlockStateProperties.EXTENDED), "A powered piston does not extend");
            test.assertTrue(fixture.level.getBlockState(piston.above()).isAir(), "No piston head appears");
            test.assertTrue(!fixture.level.setBlock(oakDoor, Blocks.STONE.defaultBlockState(), 3), "A door cannot be turned into another block");
            test.assertTrue(fixture.level.getBlockState(oakDoor).is(Blocks.OAK_DOOR), "The door is still a door");
            test.assertTrue(!fixture.level.setBlock(lamp, Blocks.AIR.defaultBlockState(), 3), "A lamp cannot be removed by a write");
            test.assertTrue(!fixture.level.destroyBlock(oakDoor, true), "destroyBlock stays refused for interactive blocks");
            player.setGameMode(GameType.CREATIVE);
            player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, oakDoor, Direction.SOUTH, 20));
            test.assertTrue(fixture.level.getBlockState(oakDoor).is(Blocks.OAK_DOOR), "A player cannot break the door");
        } catch (RuntimeException | Error failure) { fixture.close(); throw failure; }
        test.runAfterDelay(20, () -> {
            try (var ignored = fixture.install()) {
                // The standing player may already have pressed it while the level was unprotected: reset, then press under protection.
                ProtectionRuntime.ownedWrite(fixture.level, () -> fixture.level.setBlock(plate, Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), 3));
                fixture.level.getBlockState(plate).entityInside(fixture.level, plate, plateItem, InsideBlockEffectApplier.NOOP, true);
                test.assertTrue(fixture.level.getBlockState(plate).getValue(PressurePlateBlock.POWERED), "An entity on a plate presses it");
            } finally { fixture.close(); }
            test.succeed();
        });
    }

    private static void use(ServerPlayer player, BlockPos pos, Direction face, boolean sneaking) {
        player.setShiftKeyDown(sneaking);
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.5);
        // A mock connection never confirms its join teleport, and vanilla ignores block clicks until it does.
        try {
            var awaiting = net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
            awaiting.setAccessible(true); awaiting.set(player.connection, null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false), 0));
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
