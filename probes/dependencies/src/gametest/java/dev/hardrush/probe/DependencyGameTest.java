package dev.hardrush.probe;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.HashedStack;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import xyz.nucleoid.fantasy.Fantasy;

/** Feasibility fixtures running in each real server. No final gameplay or visual claims. */
public final class DependencyGameTest {
    @GameTest(maxTicks = 100)
    public void fantasyServerHooksLoad(GameTestHelper test) {
        test.assertTrue(Fantasy.get(test.getLevel().getServer()) != null, "Fantasy server mixins initialized");
        test.succeed();
    }

    @GameTest(maxTicks = 100)
    public void sqliteNativeMigrationRollbackAndWriterLock(GameTestHelper test) throws Exception {
        Class.forName("org.sqlite.JDBC");
        var directory = Files.createTempDirectory("hardrush-dependency-probe-sqlite-");
        var database = directory.resolve("probe.sqlite");
        var url = "jdbc:sqlite:" + database;
        try {
            try (var writer = DriverManager.getConnection(url); var sql = writer.createStatement()) {
                try (var mode = sql.executeQuery("PRAGMA journal_mode=WAL")) {
                    test.assertTrue(mode.next() && mode.getString(1).equals("wal"), "Native WAL available");
                }
                sql.execute("PRAGMA busy_timeout=100");
                writer.setAutoCommit(false);
                sql.execute("CREATE TABLE probe_event (id TEXT PRIMARY KEY, value INTEGER NOT NULL)");
                sql.execute("INSERT INTO probe_event VALUES ('one', 7)");
                sql.execute("PRAGMA user_version=1");
                writer.commit();

                // Deliberately fail a multi-statement migration; both schema and version must roll back.
                try {
                    sql.execute("CREATE TABLE failed_migration (id INTEGER)");
                    sql.execute("PRAGMA user_version=2");
                    sql.execute("INSERT INTO no_such_table VALUES (1)");
                    throw new AssertionError("Broken migration was accepted");
                } catch (SQLException expected) {
                    writer.rollback();
                }
                try (var version = sql.executeQuery("PRAGMA user_version")) {
                    test.assertTrue(version.next() && version.getInt(1) == 1, "Schema version rolled back");
                }
                try (var table = sql.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='failed_migration'")) {
                    test.assertTrue(table.next() && table.getInt(1) == 0, "DDL rolled back");
                }
                writer.commit();
                sql.execute("UPDATE probe_event SET value=8 WHERE id='one'");
                try (var competing = DriverManager.getConnection(url); var other = competing.createStatement()) {
                    other.execute("PRAGMA busy_timeout=100");
                    try (var snapshot = other.executeQuery("SELECT value FROM probe_event WHERE id='one'")) {
                        test.assertTrue(snapshot.next() && snapshot.getInt(1) == 7, "WAL reader sees committed snapshot");
                    }
                    try {
                        other.executeUpdate("UPDATE probe_event SET value=9 WHERE id='one'");
                        throw new AssertionError("Concurrent writer bypassed database lock");
                    } catch (SQLException expected) {
                        test.assertValueEqual(expected.getErrorCode(), 5, "Concurrent writer reports SQLITE_BUSY");
                    }
                    writer.commit();
                    test.assertValueEqual(other.executeUpdate("UPDATE probe_event SET value=9 WHERE id='one'"), 1,
                            "Lock released after commit");
                }
            }
            try (var reopened = DriverManager.getConnection(url); var sql = reopened.createStatement()) {
                try (var data = sql.executeQuery("SELECT value FROM probe_event WHERE id='one'")) {
                    test.assertTrue(data.next() && data.getInt(1) == 9, "Data survives native close/reopen");
                }
                try (var check = sql.executeQuery("PRAGMA integrity_check")) {
                    test.assertTrue(check.next() && check.getString(1).equals("ok"), "SQLite integrity check");
                }
            }
        } finally {
            for (var suffix : new String[]{"-wal", "-shm", ""}) Files.deleteIfExists(directory.resolve("probe.sqlite" + suffix));
            Files.deleteIfExists(directory);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 100)
    public void vanillaContainerOpensClicksAndCloses(GameTestHelper test) {
        var player = test.makeMockServerPlayerInLevel();
        var originalListener = player.connection;
        var recording = new RecordingConnection();
        var channel = new EmbeddedChannel(recording);
        player.connection = new ServerGamePacketListenerImpl(test.getLevel().getServer(), recording, player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false));
        var clicks = new AtomicInteger();
        var gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        try {
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            gui.setTitle(Component.literal("HardRush dependencies"));
            gui.setSlot(13, new GuiElementBuilder(Items.COMPASS).setCallback((index, type, action, screen) -> clicks.incrementAndGet()).build());
            test.assertTrue(gui.open(), "SGui opens on a server player without client mod");
            test.assertTrue(player.containerMenu.getType() == MenuType.GENERIC_9x3, "Uses vanilla chest menu ID");
            test.assertTrue(recording.packets.stream().anyMatch(packet -> packet instanceof ClientboundOpenScreenPacket open
                    && open.getType() == MenuType.GENERIC_9x3), "Sends a vanilla open-screen packet");
            test.assertTrue(recording.packets.stream().anyMatch(packet -> packet instanceof ClientboundContainerSetContentPacket),
                    "Sends vanilla container contents");
            test.assertTrue(recording.packets.stream().noneMatch(packet -> packet instanceof ClientboundCustomPayloadPacket),
                    "GUI requires no custom client payload");
            test.assertValueEqual(gui.getVirtualSize(), 27, "Three-row vanilla container");
            test.assertTrue(!gui.open(), "Opening an already open GUI is rejected");
            player.connection.handleContainerClick(new ServerboundContainerClickPacket(
                    gui.getSyncId(), player.containerMenu.getStateId(), (short) 13, (byte) 0,
                    ContainerInput.PICKUP, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
            test.assertValueEqual(clicks.get(), 1, "Vanilla click dispatch reaches callback once");
            test.assertTrue(player.containerMenu.getCarried().isEmpty(), "GUI icon is not extracted");
            test.assertValueEqual(player.getInventory().getItem(0).getCount(), 3, "Player inventory preserved");
            player.connection.handleContainerClose(new ServerboundContainerClosePacket(gui.getSyncId()));
            test.assertTrue(!gui.isOpen() && player.containerMenu == player.inventoryMenu, "Close restores vanilla inventory menu");
        } finally {
            gui.close();
            player.connection = originalListener;
            player.level().getServer().getPlayerList().remove(player);
            channel.finishAndReleaseAll();
        }
        test.succeed();
    }

    private static final class RecordingConnection extends Connection {
        final ArrayList<Packet<?>> packets = new ArrayList<>();

        RecordingConnection() { super(PacketFlow.SERVERBOUND); }

        @Override public void send(Packet<?> packet) { packets.add(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { packets.add(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { packets.add(packet); }
    }
}
