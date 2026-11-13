package dev.hardrush.lobby;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.lobby.LobbyHints;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.notice.Notices;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The usage line above the vanilla selected-item name, driven exactly as {@code PlayerFeedback} drives it: one update
 * and one render per tick against the viewer's own arbiter. Whether it sits right above the name on a real client is
 * a manual check.
 */
public final class HeldItemHintsGameTest {
    private static final AtomicInteger ARENAS = new AtomicInteger();
    private static final String IGNITER = "按住右键引爆 · 中途松开取消";

    @GameTest(maxTicks = 100)
    public void usageFlashesWhenAnItemComesIntoTheHandAndClearsWhenItLeaves(GameTestHelper test) {
        var f = new Fixture(test);
        test.startSequence().thenExecute(() -> {
            MonsterRoles.select(f.player, "creeper");
            var igniter = f.player.getMainHandItem();
            test.assertValueEqual(igniter.getHoverName().getString(), "引爆器", "The igniter starts in the hand");
            test.assertFalse(igniter.has(DataComponents.CUSTOM_NAME), "Named like a native item, never an italic rename");
            test.assertValueEqual(lore(igniter, 0), IGNITER, "The description opens with the same usage");
            test.assertValueEqual(f.tick(), Optional.of(IGNITER), "Being handed the item counts like scrolling to it");
            test.assertValueEqual(f.tick(), Optional.empty(), "Sent once; the client fades it with the vanilla name");

            f.select(7);
            var shard = f.player.getMainHandItem();
            var usage = f.tick().orElseThrow();
            test.assertTrue(usage.contains("回到庭院"), "The echo shard states how to return: " + usage);
            test.assertValueEqual(Notices.format(usage).getString(), lore(shard, 0), "Held hint and description say the same thing");
            test.assertValueEqual(shard.getHoverName().getString(), "返回营地", "The usage moved out of the name");

            f.player.getInventory().setItem(4, new ItemStack(Items.STICK));
            f.select(4);
            test.assertValueEqual(f.tick(), Optional.of(""), "A plain item blanks the old usage at once");
            test.assertValueEqual(f.tick(), Optional.<String>empty(), "Nothing new to send");
            f.select(5);
            test.assertValueEqual(f.tick(), Optional.empty(), "An empty hand has nothing to clear twice");

            f.select(0);
            test.assertValueEqual(f.tick(), Optional.of(IGNITER), "Coming back to the item shows it again");
        }).thenExecuteAfter(1, f::close).thenSucceed();
    }

    @GameTest(maxTicks = 100)
    public void stateItemsChangeTheirLineWhenWhatTheyCanDoChanges(GameTestHelper test) {
        var f = new Fixture(test);
        test.startSequence().thenExecute(() -> MonsterRoles.select(f.player, "skeleton"))
                .thenIdle(2).thenExecute(() -> {
                    test.assertValueEqual(f.tick(), Optional.of("潜行时拉满弓松手，触发三连射"), "A ready bow tells how to loose the volley");
                    test.assertTrue(MonsterRoles.burst(f.player), "The volley starts");
                }).thenIdle(2).thenExecute(() -> {
                    test.assertValueEqual(f.tick(), Optional.of("三连射冷却中"), "The changed state shows again while held");

                    f.select(6);
                    var compass = f.player.getMainHandItem();
                    var dark = f.tick().orElseThrow();
                    test.assertTrue(dark.contains("发光"), "An unlit compass says when it lights: " + dark);
                    test.assertValueEqual(Notices.format(dark).getString(), lore(compass, 1), "The same condition the description gives");
                    compass.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                    var lit = f.tick().orElseThrow();
                    test.assertTrue(lit.contains("循迹到目标附近"), "A lit compass says how to retrack: " + lit);
                    test.assertValueEqual(f.tick(), Optional.<String>empty(), "Nothing new to send");
                }).thenExecuteAfter(1, f::close).thenSucceed();
    }

    private static String lore(ItemStack stack, int line) { return stack.get(DataComponents.LORE).lines().get(line).getString(); }

    private static final class Fixture {
        final ServerLevel level;
        final ServerPlayer player;
        final EmbeddedChannel channel;
        final Vec3 center;
        /** The viewer's own arbiter, apart from the one the runtime keeps for real connections. */
        final LobbyHints hints = new LobbyHints();
        long now;

        Fixture(GameTestHelper test) {
            level = test.getLevel();
            center = new Vec3(-20000 - ARENAS.getAndIncrement() * 64 + .5, 150, .5);
            var chunk = new net.minecraft.world.level.ChunkPos((int) Math.floor(center.x) >> 4, 0);
            level.setChunkForced(chunk.x(), chunk.z(), true);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                var pos = BlockPos.containing(center).offset(dx, 0, dz);
                level.setBlockAndUpdate(pos.below(), Blocks.SMOOTH_STONE.defaultBlockState());
            }
            var profile = new GameProfile(UUID.randomUUID(), "HRHeld" + ARENAS.get());
            player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
            var connection = new SinkConnection(); channel = new EmbeddedChannel(connection);
            dev.hardrush.gametest.UnmanagedProfileFixtures.run(() -> level.getServer().getPlayerList().placeNewPlayer(connection, player,
                    CommonListenerCookie.createInitial(profile, false)));
            player.teleportTo(center.x, center.y, center.z);
            player.setGameMode(GameType.SURVIVAL); player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        }

        /** The hotbar packet a vanilla client sends when the selection changes. */
        void select(int slot) { player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot)); }

        /** One feedback tick, never a refresh tick, so only new lines go out. */
        Optional<String> tick() {
            now += 50;
            HeldItemHints.update(player, hints, now);
            return hints.render(now, false);
        }

        void close() {
            MonsterRoles.clear(player);
            level.getServer().getPlayerList().remove(player);
            channel.finishAndReleaseAll();
            level.setChunkForced((int) Math.floor(center.x) >> 4, 0, false);
        }
    }

    private static final class SinkConnection extends Connection {
        SinkConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) {}
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {}
    }
}
