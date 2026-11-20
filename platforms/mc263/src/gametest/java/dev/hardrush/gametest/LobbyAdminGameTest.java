package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.lobby.LobbyCredits;
import dev.hardrush.core.lobby.plan.*;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.lobby.ButtonMenu;
import dev.hardrush.lobby.LobbyInteractions;
import dev.hardrush.lobby.LobbyMenus;
import dev.hardrush.lobby.pack.*;
import dev.hardrush.lobby.workshop.Workshop;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.round.RoundSession;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.world.mixin.TextDisplayAccessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;

/**
 * Installing a pack into the running production lobby and rolling back (design 10.2, M4), through {@link LobbyAdmin}
 * as the menu will call it. A test pack is made on the spot from the built-in one: its history lectern is unbound (a
 * stand-in must appear), the rules get an extra {@code interaction} entity, and credits get a sign and a text display.
 * The install starts during the countdown and switches only once the round is back to waiting.
 */
public final class LobbyAdminGameTest {
    private static final String PACK_NAME = "测试营地";
    private static final List<String> AUTHORS = List.of("甲", "乙");

    @GameTest(maxTicks = 60000)
    public void installWaitsForTheRoundSwitchesEveryoneAndRollsBack(GameTestHelper test) {
        if (!Boolean.getBoolean(TestProperties.LOBBY_ADMIN)) { test.succeed(); return; }
        var f = new Fixture(test);
        var sequence = test.startSequence().thenWaitUntil(() -> f.await(LobbyWorld.open(f.server).ready()
                && RoundRuntime.snapshot(f.server).filter(r -> r.phase() == RoundPhase.WAITING).isPresent(), "Lobby ready"))
                .thenExecute(f::bind);
        f.a.login(sequence); f.b.login(sequence);
        sequence.thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.WAITING && f.idle(), "Peers in the lobby"))
                .thenExecute(f::openScratch)
                .thenWaitUntil(() -> f.await(f.scratchChunks.tick(), "Scratch dimension loaded"))
                .thenExecute(f::writePack)
                .thenWaitUntil(() -> {
                    var library = LobbyAdmin.library(f.server);
                    f.await(!library.loading() && library.packs().stream().anyMatch(p -> p.file().equals(f.pack)), "Library lists the pack");
                })
                .thenWaitUntil(() -> f.await(Workshop.get(f.server).filter(Workshop::loaded).isPresent(), "Workshop listing read"))
                .thenExecute(() -> {
                    // A 2-level operator sees the entry greyed out and cannot use it.
                    f.op(f.a, PermissionLevel.GAMEMASTERS);
                    test.assertTrue(Workshop.get(f.server).orElseThrow().openMenu(f.a.player), "The workshop chest opens for level 2");
                    int slot = f.slot("大厅包库与安装");
                    test.assertTrue(slot >= 0 && !f.item(slot).is(Items.BOOKSHELF), "The library entry is greyed out for level 2");
                    f.click("大厅包库与安装");
                    test.assertTrue(f.slot("当前大厅：", true) < 0, "A greyed entry opens nothing");
                    f.a.player.closeContainer();
                    f.op(f.a, PermissionLevel.OWNERS);
                    test.assertTrue(Workshop.get(f.server).orElseThrow().openMenu(f.a.player), "The workshop chest opens for level 4");
                    test.assertTrue(f.item(f.slot("大厅包库与安装")).is(Items.BOOKSHELF), "Level 4 gets a working entry");
                    f.click("大厅包库与安装");
                })
                .thenIdle(6)
                .thenExecute(() -> {
                    test.assertTrue(f.slot("当前大厅：默认营地", true) >= 0, "The top page names the live lobby");
                    test.assertFalse(f.item(f.slot("回到上一个大厅")).is(Items.RECOVERY_COMPASS), "Roll back is greyed with no previous lobby");
                    test.assertTrue(f.lore(f.slot("回到上一个大厅")).contains("没有上一个大厅可以回去"), "...and says why");
                    test.assertTrue(f.slot("安装进行中：", true) < 0, "No progress button while idle");
                    f.a.player.closeContainer();
                })
                .thenExecute(() -> {
                    var summary = LobbyAdmin.library(f.server).packs().stream().filter(p -> p.file().equals(f.pack)).findFirst().orElseThrow();
                    test.assertTrue(summary.installable(), "The test pack can be installed: " + summary.problems());
                    test.assertValueEqual(summary.fallbacks(), List.of(LobbyFunction.HISTORY), "The library says history gets a stand-in");
                    test.assertValueEqual(summary.manifest().orElseThrow().name(), PACK_NAME, "Name from pack.json");
                    // Ready in the circle: the countdown runs (on a frozen clock), so no switch is allowed.
                    f.place(f.a, Landing.RESTORE_READY);
                })
                .thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.COUNTDOWN && f.idle(), "Countdown"))
                .thenExecute(() -> {
                    f.before = LobbyWorld.open(f.server).installation().orElseThrow();
                    f.oldKey = LobbyWorld.open(f.server).key();
                    test.assertTrue(Workshop.get(f.server).orElseThrow().openMenu(f.a.player), "Open the workshop chest");
                    f.click("大厅包库与安装");
                })
                .thenIdle(6)
                .thenExecute(() -> f.click("大厅包库"))
                .thenIdle(6)
                .thenWaitUntil(() -> { f.refresh(); f.await(f.slot("测试营地 v2") >= 0, "Library page lists the test pack"); })
                .thenExecute(() -> {
                    test.assertTrue(f.lore(f.slot("测试营地 v2")).contains("会放临时设施：战绩"), "The library lore names the stand-in: " + f.lore(f.slot("测试营地 v2")));
                    f.click("测试营地 v2");
                })
                .thenIdle(6)
                .thenExecute(() -> {
                    test.assertTrue(f.item(f.slot("安装")).is(Items.ENDER_PEARL), "The detail page offers installing");
                    f.click("安装");
                })
                .thenIdle(6)
                .thenExecute(() -> {
                    test.assertTrue(LobbyAdmin.progress(f.server).isEmpty(), "Installing asks for confirmation first");
                    f.click("确认安装");
                    test.assertTrue(LobbyAdmin.progress(f.server).isPresent(), "Confirming starts the install");
                    test.assertTrue(f.slot("安装进行中：", true) >= 0, "The top page shows the install progress");
                    test.assertFalse(f.item(f.slot("回到上一个大厅")).is(Items.RECOVERY_COMPASS), "Roll back is greyed while installing");
                    test.assertFalse(LobbyAdmin.install(f.server, f.pack, null).accepted(), "Only one install at a time");
                })
                .thenWaitUntil(() -> f.await(LobbyAdmin.progress(f.server).map(LobbyAdmin.Progress::waitingForSwitch).orElse(false), "Built, waiting for the round"))
                .thenIdle(20)
                .thenExecute(() -> {
                    test.assertTrue(LobbyAdmin.progress(f.server).orElseThrow().waitingForSwitch(), "Still waiting during the countdown");
                    test.assertValueEqual(LobbyWorld.open(f.server).key(), f.oldKey, "The live lobby is unchanged while waiting");
                    test.assertValueEqual(readCurrent(f).seq(), f.before.seq(), "current.json still names the old lobby");
                    f.place(f.a, Landing.CENTRAL);
                })
                .thenWaitUntil(() -> f.await(LobbyAdmin.progress(f.server).isEmpty()
                        && LobbyWorld.open(f.server).installation().orElseThrow().seq() != f.before.seq(), "Switched once back to waiting"))
                .thenExecute(() -> f.checkSwitched(PACK_NAME))
                .thenWaitUntil(() -> f.await(f.server.getLevel(f.oldKey) == null, "Old lobby dimension deleted"))
                .thenExecute(f::checkNewLobby)
                .thenWaitUntil(() -> f.await(f.creditsWritten(), "Credits written"))
                .thenExecute(() -> {
                    f.before = LobbyWorld.open(f.server).installation().orElseThrow();
                    f.oldKey = LobbyWorld.open(f.server).key();
                    var rollback = LobbyAdmin.rollback(f.server, null);
                    test.assertTrue(rollback.accepted(), rollback.message());
                })
                .thenWaitUntil(() -> f.await(LobbyAdmin.progress(f.server).isEmpty()
                        && LobbyWorld.open(f.server).installation().orElseThrow().seq() != f.before.seq(), "Rolled back"))
                .thenExecute(() -> {
                    f.checkSwitched("默认营地");
                    test.assertValueEqual(LobbyWorld.open(f.server).installation().orElseThrow().pack().id(), "default", "Back to the default pack");
                    test.assertTrue(LobbyWorld.open(f.server).plan().triggers(LobbyFunction.HISTORY).stream().allMatch(t -> t.block() != null
                            && t.block().equals(new Cell(-22, 65, 3))), "The default history lectern is bound again");
                })
                .thenWaitUntil(() -> f.await(f.server.getLevel(f.oldKey) == null, "Test lobby dimension deleted"))
                .thenExecute(() -> {
                    f.a.disconnect(); f.b.disconnect();
                    try { Files.deleteIfExists(f.pack); } catch (IOException ignored) {}
                    System.out.println("LOBBY_ADMIN_PASS: install waits for the round, switch moves everyone, stand-in, entity trigger, credits, rollback");
                })
                .thenSucceed();
    }

    private static LobbyInstallation readCurrent(Fixture f) {
        try { return LobbyWorld.open(f.server).storage().readCurrent().orElseThrow(); }
        catch (IOException | PackException e) { throw new IllegalStateException(e); }
    }

    private static final class Fixture {
        final GameTestHelper test;
        final MinecraftServer server;
        final AtomicLong clock = new AtomicLong(System.nanoTime());
        final Peer a, b;
        RoundSession session;
        PlayerProfiles profiles;
        String lastStage;
        int stageStarted;
        xyz.nucleoid.fantasy.RuntimeLevelHandle scratch;
        ChunkWarmup scratchChunks;
        PackContents base;
        Path pack;
        LobbyInstallation before;
        ResourceKey<Level> oldKey;
        Fixture(GameTestHelper test) {
            this.test = test; server = test.getLevel().getServer();
            a = new Peer("LobbyAdminA"); b = new Peer("LobbyAdminB");
        }

        @SuppressWarnings("unchecked") <T> T service(String name) {
            try { var field = RoundRuntime.class.getDeclaredField(name); field.setAccessible(true); return (T) ((Map<?, ?>) field.get(null)).get(server); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        /** The session's clock only moves when the test says so: the countdown never runs out. */
        void bind() {
            session = service("SESSIONS"); profiles = service("PROFILES");
            clock.set(System.nanoTime());
            try {
                var last = RoundSession.class.getDeclaredField("lastCheckpointNanos"); last.setAccessible(true); last.set(session, clock.get());
                var ef = RoundSession.class.getDeclaredField("engine"); ef.setAccessible(true); var engine = ef.get(session);
                var boundary = dev.hardrush.core.round.RoundEngine.class.getDeclaredField("boundary"); boundary.setAccessible(true); boundary.set(engine, clock.get());
                var field = RoundSession.class.getDeclaredField("clock"); field.setAccessible(true); field.set(session, (MonotonicClock) clock::get);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        void await(boolean condition, String stage) {
            if (!stage.equals(lastStage)) {
                lastStage = stage; stageStarted = server.getTickCount();
                System.out.println("Lobby admin stage: " + stage);
            }
            if (!condition && server.getTickCount() - stageStarted > 6000) throw new IllegalStateException("Lobby admin stage timed out: " + stage
                    + "; " + RoundRuntime.status(server) + "; " + LobbyAdmin.progress(server));
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            test.assertTrue(condition, stage + ": " + RoundRuntime.status(server));
        }
        void op(Peer peer, PermissionLevel level) {
            server.getPlayerList().op(new NameAndId(peer.profile), Optional.of(LevelBasedPermissionSet.forLevel(level)), Optional.empty());
        }
        net.minecraft.world.item.ItemStack item(int slot) { return a.player.containerMenu.getSlot(slot).getItem(); }
        /** The chest slot labelled {@code label} (or starting with it); -1 when there is none. */
        int slot(String label) { return slot(label, false); }
        int slot(String label, boolean prefix) {
            if (!ButtonMenu.isMenu(a.player)) return -1;
            for (int i = 0; i < 54; i++) {
                var name = item(i).getHoverName().getString();
                if (prefix ? name.startsWith(label) : name.equals(label)) return i;
            }
            return -1;
        }
        String lore(int slot) {
            var lore = item(slot).get(DataComponents.LORE);
            return lore == null ? "" : String.join(" | ", lore.lines().stream().map(Component::getString).toList());
        }
        void click(String label) {
            int slot = slot(label);
            test.assertTrue(slot >= 0, "Button " + label + " is on the page: " + (ButtonMenu.isMenu(a.player)
                    ? java.util.stream.IntStream.range(0, 54).mapToObj(i -> item(i).getHoverName().getString()).filter(n -> !n.isEmpty()).toList()
                    : "no menu open, container " + a.player.containerMenu));
            // Ticks run faster than real time here, and a menu drops repeated clicks within its cooldown.
            long until = System.nanoTime() + ButtonMenu.BUTTON_COOLDOWN_NANOS + 50_000_000L;
            while (System.nanoTime() < until) java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
            a.player.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, a.player);
        }
        void refresh() { if (ButtonMenu.isMenu(a.player)) a.player.containerMenu.clicked(49, 0, ContainerInput.PICKUP, a.player); }
        dev.hardrush.core.round.RoundSnapshot round() { return session.snapshot().orElseThrow(); }
        boolean idle() { return !session.mutationPending(); }
        void place(Peer peer, Landing landing) { profiles.placeInLobby(peer.player, landing); peer.client.acknowledge(); }

        /** A throwaway void dimension holding the built-in lobby, to change and capture into the test pack. */
        void openScratch() {
            try (var in = LobbyInstall.Source.builtIn().open()) { base = PackFile.read(in, PackLimits.forServer(256)); }
            catch (IOException | PackException e) { throw new IllegalStateException(e); }
            var config = new RuntimeLevelConfig().setGenerator(new xyz.nucleoid.fantasy.util.VoidChunkGenerator(server))
                    .setDimensionType(BuiltinDimensionTypes.OVERWORLD).setDifficulty(Difficulty.PEACEFUL)
                    .setMirrorOverworldGameRules(false).setShouldTickTime(false)
                    .setGameRule(GameRules.SPAWN_MOBS, false).setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
            scratch = Fantasy.get(server).openTemporaryLevel(config);
            scratch.setTickWhenEmpty(true);
            scratchChunks = new ChunkWarmup(scratch.asLevel(), base.manifest().bounds());
        }

        void writePack() {
            var level = scratch.asLevel();
            var bounds = base.manifest().bounds();
            for (var tile : base.tiles().entrySet()) {
                var copy = PackTiles.upgrade(tile.getValue().copy(), base.manifest().dataVersion());
                TileSanitizer.sanitize(copy, SanitizeMode.INSTALL);
                PackPlacer.place(level, tile.getKey(), copy, bounds);
            }
            var plan = base.plan();
            var central = LandingRules.candidates(plan, Landing.CENTRAL).getFirst();
            FallbackSites.Ground ground = c -> {
                var pos = new BlockPos(c.x(), c.y(), c.z());
                return level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                        && level.getBlockState(pos.below()).isCollisionShapeFullBlock(level, pos.below());
            };
            // Away from the central landing, so the history stand-in has its own spot.
            var away = new Point(central.x() - 12, central.y(), central.z(), 0);
            var signCell = FallbackSites.find(plan, away, List.of(), ground).orElseThrow(() -> new AssertionError("No room for the credits sign"));
            var clickCell = FallbackSites.find(plan, away, List.of(signCell), ground).orElseThrow(() -> new AssertionError("No room for the interaction"));
            level.setBlock(new BlockPos(signCell.x(), signCell.y(), signCell.z()), Blocks.OAK_SIGN.defaultBlockState(), 3);
            var click = new Interaction(EntityTypes.INTERACTION, level);
            click.snapTo(clickCell.x() + .5, clickCell.y(), clickCell.z() + .5);
            click.addTag(MarkerTags.FUNCTION + LobbyFunction.RULES.id());
            level.addFreshEntity(click);
            var displayAt = new Point(central.x(), central.y() + 3, central.z(), 0);
            var display = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
            display.snapTo(displayAt.x(), displayAt.y(), displayAt.z());
            display.addTag(MarkerTags.FUNCTION + LobbyFunction.CREDITS.id());
            level.addFreshEntity(display);

            var triggers = new ArrayList<>(plan.triggers().stream().filter(t -> t.function() != LobbyFunction.HISTORY).toList());
            triggers.add(new Trigger(LobbyFunction.RULES, null, null, new Point(clickCell.x() + .5, clickCell.y(), clickCell.z() + .5, 0)));
            var texts = new ArrayList<>(plan.texts());
            texts.add(new TextSlot(LobbyFunction.CREDITS, signCell, new Point(signCell.x() + .5, signCell.y() + 1, signCell.z() + .5, 0)));
            texts.add(new TextSlot(LobbyFunction.CREDITS, null, displayAt));
            var changed = new LobbyPlan(bounds, triggers, plan.models(), plan.points(), plan.zones(), plan.altarLights(), texts);
            var m = base.manifest();
            var manifest = new PackManifest(m.format(), "admin-test", PACK_NAME, AUTHORS, 2, m.created(), m.modVersion(), PackTiles.currentDataVersion(), bounds);
            pack = LobbyWorld.open(server).storage().packs().resolve(manifest.fileName());
            try {
                PackFile.write(pack, manifest, changed, PackCapture.captureAll(level, bounds, OptionalInt.empty()));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } finally {
                scratchChunks.close();
                scratch.delete();
            }
        }

        /** Both peers stand in the new lobby near its central landing, in lobby mode. */
        void checkSwitched(String name) {
            var lobby = LobbyWorld.open(server);
            test.assertValueEqual(lobby.installation().orElseThrow().pack().name(), name, "Switched to " + name);
            test.assertFalse(lobby.key().equals(oldKey), "A new dimension");
            test.assertValueEqual(readCurrent(this), lobby.installation().orElseThrow(), "current.json names the live lobby");
            var central = LobbyWorld.position(LandingRules.candidates(lobby.plan(), Landing.CENTRAL).getFirst());
            for (var peer : List.of(a, b)) {
                test.assertTrue(peer.player.level() == lobby.holdingLevel(), peer.profile.name() + " was moved into the new lobby");
                // Vanilla's scatter picks any column of the respawn_radius (10) square: a corner is ~14.2 blocks away.
                test.assertTrue(peer.player.position().distanceTo(central) < 15,peer.profile.name() + " stands by the central landing: " + peer.player.position());
                test.assertTrue(PlayerProfiles.canMoveInLobby(peer.player), peer.profile.name() + " can still move");
            }
        }

        void checkNewLobby() {
            var lobby = LobbyWorld.open(server);
            var plan = lobby.plan();
            var router = LobbyInteractions.get(server).orElseThrow();
            // The history stand-in: a lectern beside the central landing that opens the records.
            var history = plan.triggers(LobbyFunction.HISTORY);
            test.assertValueEqual(history.size(), 1, "One history binding, the stand-in");
            var lectern = LobbyWorld.pos(history.getFirst().block());
            test.assertTrue(lobby.holdingLevel().getBlockState(lectern).is(Blocks.LECTERN), "A lectern stands in for history");
            teleport(a, Vec3.atBottomCenterOf(lectern.south(2)));
            a.player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(lectern), Direction.UP, lectern, false), 901));
            test.assertTrue(LobbyMenus.isMenu(a.player), "The stand-in lectern opens the records");
            a.player.closeContainer();
            // The pack's own history lectern is still there, but no longer bound.
            var old = new BlockPos(-22, 65, 3);
            test.assertTrue(lobby.holdingLevel().getBlockState(old).is(Blocks.LECTERN), "The unbound lectern was installed");
            teleport(a, Vec3.atBottomCenterOf(old.south(2)));
            a.player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(old), Direction.UP, old, false), 902));
            test.assertFalse(LobbyMenus.isMenu(a.player), "The unbound lectern does nothing");

            // The interaction entity tagged for the rules opens the rule book on a right click, not on a left click.
            var b = plan.bounds();
            var box = new AABB(b.min().x(), b.min().y(), b.min().z(), b.max().x() + 1, b.max().y() + 1, b.max().z() + 1);
            var click = lobby.holdingLevel().getEntities(EntityTypeTest.forClass(Interaction.class), box,
                    e -> e.entityTags().contains(MarkerTags.FUNCTION + LobbyFunction.RULES.id()));
            test.assertValueEqual(click.size(), 1, "The tagged interaction entity was installed");
            var entity = click.getFirst();
            teleport(a, entity.position().add(0, 0, 1.5));
            long books = a.books();
            a.player.connection.handleAttack(new ServerboundAttackPacket(entity.getId()));
            test.assertValueEqual(a.books(), books, "A left click does not open the rules");
            a.player.connection.handleInteract(new ServerboundInteractPacket(entity.getId(), InteractionHand.MAIN_HAND, new Vec3(0, .5, 0), false));
            test.assertValueEqual(router.lastResult(a.player).orElseThrow(), LobbyInteractions.Result.ACCEPTED, "The tagged entity is a rules trigger");
            test.assertValueEqual(a.books(), books + 1, "A right click opens the rule book");
            a.player.connection.handleInteract(new ServerboundInteractPacket(entity.getId(), InteractionHand.OFF_HAND, new Vec3(0, .5, 0), false));
            test.assertValueEqual(a.books(), books + 1, "The off-hand repeat opens nothing more");
        }

        boolean creditsWritten() {
            var lobby = LobbyWorld.open(server);
            var plan = lobby.plan();
            var want = LobbyCredits.signLines(PACK_NAME, 2, AUTHORS);
            for (var slot : plan.texts(LobbyFunction.CREDITS)) {
                if (slot.sign() == null) continue;
                if (!(lobby.holdingLevel().getBlockEntity(LobbyWorld.pos(slot.sign())) instanceof SignBlockEntity sign)) return false;
                var lines = sign.getText(SignTextSlot.FRONT).getMessages(false).stream().map(Component::getString).toList();
                if (!lines.equals(want)) return false;
            }
            var b = plan.bounds();
            var box = new AABB(b.min().x(), b.min().y(), b.min().z(), b.max().x() + 1, b.max().y() + 1, b.max().z() + 1);
            var displays = lobby.holdingLevel().getEntities(EntityTypeTest.forClass(Display.TextDisplay.class), box,
                    e -> e.entityTags().contains(MarkerTags.FUNCTION + LobbyFunction.CREDITS.id()));
            return displays.size() == 1 && ((TextDisplayAccessor) displays.getFirst()).hardrush$text().getString()
                    .equals(LobbyCredits.display(PACK_NAME, 2, AUTHORS));
        }

        void teleport(Peer peer, Vec3 pos) { peer.player.connection.teleport(pos.x, pos.y, pos.z, 0, 0); peer.client.acknowledge(); }

        final class Peer {
            final GameProfile profile;
            final TestClient client;
            ServerPlayer player;
            Peer(String name) { profile = new GameProfile(UUID.randomUUID(), name); client = new TestClient(server, profile); }
            long books() { return client.packets(ClientboundOpenBookPacket.class).size(); }
            void login(GameTestSequence sequence) {
                sequence.thenExecute(client::beginLogin)
                        .thenWaitUntil(() -> await(client.finishLogin(), "Native login data loaded"))
                        .thenExecute(() -> {
                            player = client.player(); client.acknowledge();
                        }).thenWaitUntil(() -> await(PlayerProfiles.canMoveInLobby(player), "Profile verification opens lobby movement"));
            }
            void disconnect() {
                client.disconnect(Component.literal("大厅安装回归结束")); player = client.player();
            }
        }
    }
}
