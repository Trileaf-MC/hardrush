package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import dev.hardrush.HardRushProperties;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.combat.KillCreditMemory;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.round.*;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.revival.RevivalTotems;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.round.RoundSession;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.world.RoundWorldBundle;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Leaving mid-fight (docs/plan/战斗中断线.md) on the production runtime: real logins, native disconnects, the
 * server-side kick {@code /kick} makes, the real round session and SQLite store, and a native reconnect. Opt-in, in its own fresh hardcore run:
 * {@code -PhardrushCompetition=true -PhardrushCombatDeparture=true}.
 *
 * <p>Five humans: Cleo leaves unhurt, Dana is kicked mid-fight, Alma leaves after Brix hit her (and comes back as a camp
 * monster), Brix leaves holding the totem he picked up from Alma, and Eden leaves after Alma, sent out and back once,
 * still holds the credit for a hit.
 */
public final class CombatDepartureGameTest {
    @GameTest(maxTicks = 40000)
    public void leavingMidFightIsTheLethalHitAndOnlyThePlayersOwnDisconnectCounts(GameTestHelper test) {
        if (!Boolean.getBoolean(TestProperties.COMBAT_DEPARTURE)) { test.succeed(); return; }
        test.assertTrue(HardRushProperties.competitionEnabled(), "The dedicated run requires the real enabled runtime");
        var f = new Fixture(test);
        var alma = f.actor("Alma"); var brix = f.actor("Brix"); var cleo = f.actor("Cleo");
        var dana = f.actor("Dana"); var eden = f.actor("Eden");
        var marks = new Object() { Vec3 alma, brix; int almaCharges; };
        test.onEachTick(() -> {
            if (f.clock != null) f.clock.accumulateAndGet(System.nanoTime(), Math::max);
            for (var actor : f.actors) if (actor.client.connected()) actor.client.acknowledge();
            test.assertFalse(RoundRuntime.sessionState(f.server).filter(state -> state == RoundSession.State.FAILED).isPresent(),
                    "Production runtime failed: " + RoundRuntime.status(f.server));
        });
        var sequence = test.startSequence()
                .thenWaitUntil(() -> f.await(RoundRuntime.sessionState(f.server).filter(state -> state == RoundSession.State.OPEN).isPresent(),
                        "Runtime bootstrap"))
                .thenExecute(f::installClock);
        for (var actor : f.actors) actor.login(sequence);
        sequence.thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.WAITING && !f.session.mutationPending(), "The round waits"));
        for (var actor : f.actors) {
            sequence.thenExecute(() -> actor.command("hardrush join"))
                    .thenWaitUntil(() -> f.await(actor.participant() != null && !f.session.mutationPending(), actor.name() + " joins"))
                    .thenExecute(() -> actor.command("hardrush ready"))
                    .thenWaitUntil(() -> f.await(actor.participant().ready() && !f.session.mutationPending(), actor.name() + " is ready"));
        }
        sequence.thenExecute(() -> f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30)))
                .thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.RUNNING && !f.session.mutationPending()
                        && f.actors.stream().noneMatch(actor -> PlayerProfiles.isLocked(actor.player)), "Every human is out in the round"))
                .thenExecute(() -> {
                    f.roundLevel = f.service("WORLDS", RoundWorldBundle.class).level(Level.OVERWORLD);
                    test.assertValueEqual(f.round().rules().mode(), GameMode.HARDCORE, "The run is hardcore");
                    // Far enough apart that each one's drops are told apart.
                    var spots = f.standings(5);
                    for (int i = 0; i < f.actors.size(); i++) f.actors.get(i).move(spots.get(i));
                })
                // Nobody has hurt Cleo: leaving keeps the place, as before.
                .thenExecute(cleo::disconnect)
                .thenWaitUntil(() -> f.await(!cleo.participant().online() && !f.session.mutationPending(), "Cleo is offline"))
                .thenExecute(() -> {
                    test.assertValueEqual(cleo.participant().role(), ParticipantRole.HUMAN_ACTIVE, "An unhurt human leaves still human");
                    test.assertValueEqual(cleo.participant().lifeSequence(), 1, "No life ended");
                    // Brix hits Dana, but the server kicks her: an administrator's decision never costs a life.
                    CombatRuntime.creditHit(f.roundLevel, dana.player, brix.player);
                    // What KickCommand does; the GameTest server refuses /kick as a singleplayer game.
                    dana.player.connection.disconnect(net.minecraft.network.chat.Component.translatable("multiplayer.disconnect.kicked"));
                })
                .thenWaitUntil(() -> f.await(!dana.client.connected() && !dana.participant().online() && !f.session.mutationPending(), "Dana is kicked"))
                .thenExecute(() -> {
                    test.assertValueEqual(dana.participant().role(), ParticipantRole.HUMAN_ACTIVE, "A kicked human leaves still human");
                    // Brix hits Alma, and she quits: the hit is lethal, where she stood, with her belongings.
                    alma.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                    CombatRuntime.creditHit(f.roundLevel, alma.player, brix.player);
                    marks.alma = alma.player.position();
                    brix.client.clearPackets();
                    alma.disconnect();
                })
                .thenWaitUntil(() -> f.await(alma.participant().role() == ParticipantRole.MONSTER_LOBBY && !alma.participant().online()
                        && !f.session.mutationPending(), "Alma's departure is her death"))
                .thenExecute(() -> {
                    test.assertValueEqual(f.items(marks.alma, Items.DIAMOND), 3, "Her belongings drop where she left");
                    test.assertValueEqual(f.totems(marks.alma).size(), 1, "One revival totem is released where she left");
                    f.chat(brix, "在与" + brix.name() + "交战时断线，按阵亡处理");
                });
        alma.login(sequence);
        sequence.thenWaitUntil(() -> f.await(alma.monsterReady(), "Alma comes back as a camp monster"))
                .thenExecute(() -> {
                    test.assertValueEqual(alma.participant().lifeSequence(), 1, "Coming back creates no human life");
                    test.assertTrue(alma.player.getInventory().isEmpty(), "Her dropped belongings are not duplicated");
                    // Brix takes the totem she released, and holds it.
                    var totem = f.totems(marks.alma).getFirst();
                    brix.move(totem.position());
                })
                .thenWaitUntil(() -> f.await(brix.participant().revivalCharges() == 1 && !f.session.mutationPending()
                        && f.totemSlot(brix.player) >= 0, "Brix picks up Alma's totem"))
                .thenExecute(() -> {
                    var inventory = brix.player.getInventory();
                    int slot = f.totemSlot(brix.player);
                    var held = inventory.getItem(slot).copy();
                    inventory.setItem(slot, ItemStack.EMPTY);
                    brix.player.setItemInHand(InteractionHand.MAIN_HAND, held);
                    // Eden hits Brix, and he quits holding it: the totem saves him in place, as from any lethal hit.
                    CombatRuntime.creditHit(f.roundLevel, brix.player, eden.player);
                    marks.brix = brix.player.position();
                    eden.client.clearPackets();
                    brix.disconnect();
                })
                .thenWaitUntil(() -> f.await(!brix.participant().online() && !f.session.mutationPending(), "Brix leaves saved"))
                .thenExecute(() -> {
                    test.assertValueEqual(brix.participant().role(), ParticipantRole.HUMAN_ACTIVE, "The totem keeps him human");
                    test.assertValueEqual(brix.participant().revivalCharges(), 0, "The save spent his one charge");
                    test.assertValueEqual(f.totems(marks.brix).size(), 1, "The save releases one totem at his feet");
                    f.chat(eden, "靠复生图腾挺了过来");
                    // Alma is sent out once and called back, so a hit of hers is still a monster's.
                    marks.almaCharges = alma.participant().revivalCharges();
                    // Past the cooldown a fresh camp monster waits out before its first sortie.
                    f.clock.addAndGet(TimeUnit.MINUTES.toNanos(10));
                    f.session.requestMonsterDeploy(f.round().roundId(), alma.profile.id(), 1, 0, f.reply::set);
                })
                .thenWaitUntil(() -> f.await(f.reply.get() != null && !f.session.mutationPending(), "Alma deploys"))
                .thenExecute(() -> {
                    test.assertTrue(f.reply.get().success(), "Deployed: " + f.reply.get().message());
                    f.reply.set(null);
                    f.session.requestMonsterDeployCancelled(f.round().roundId(), alma.profile.id(), 1, 1, f.reply::set);
                })
                .thenWaitUntil(() -> f.await(f.reply.get() != null && !f.session.mutationPending(), "Alma is called back"))
                .thenExecute(() -> {
                    test.assertTrue(f.reply.get().success(), "Called back: " + f.reply.get().message());
                    // Her arrow, loosed while out, hit Eden; Eden quits.
                    f.monsterHit(eden.player, alma.participant());
                    eden.disconnect();
                })
                .thenWaitUntil(() -> f.await(eden.participant().role() == ParticipantRole.MONSTER_LOBBY && !f.session.mutationPending(),
                        "Eden's departure is his death"))
                .thenExecute(() -> test.assertValueEqual(alma.participant().revivalCharges(), marks.almaCharges + 1,
                        "A monster's credit earns it the released totem"))
                .thenExecute(() -> { for (var actor : f.actors) actor.disconnect(); })
                .thenSucceed();
    }

    private static final class Fixture {
        final GameTestHelper test;
        final MinecraftServer server;
        final List<Actor> actors = new ArrayList<>();
        final AtomicReference<RoundSession.Reply> reply = new AtomicReference<>();
        RoundSession session;
        PlayerProfiles profiles;
        AtomicLong clock;
        ServerLevel roundLevel;

        Fixture(GameTestHelper test) { this.test = test; server = test.getLevel().getServer(); }

        Actor actor(String name) { var actor = new Actor(this, name); actors.add(actor); return actor; }

        void installClock() {
            session = service("SESSIONS", RoundSession.class);
            profiles = service("PROFILES", PlayerProfiles.class);
            clock = new AtomicLong(System.nanoTime());
            try {
                var field = RoundSession.class.getDeclaredField("clock"); field.setAccessible(true);
                field.set(session, (MonotonicClock) clock::get);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }

        RoundSnapshot round() { return RoundRuntime.snapshot(server).orElseThrow(); }

        void await(boolean condition, String message) {
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            test.assertTrue(condition, message + ": " + RoundRuntime.status(server));
        }

        /** {@code count} standing points around the spawn, at least six blocks apart. */
        List<Vec3> standings(int count) {
            var spawn = service("WORLDS", RoundWorldBundle.class).spawn();
            var found = new ArrayList<Vec3>();
            for (int distance = 2; distance <= 32 && found.size() < count; distance += 2) for (var direction : Direction.Plane.HORIZONTAL) {
                for (int dy = -12; dy <= 12; dy++) {
                    BlockPos feet = spawn.relative(direction, distance).offset(0, dy, 0);
                    if (!RoundWorldBundle.safeStanding(roundLevel, feet)) continue;
                    var point = Vec3.atBottomCenterOf(feet);
                    if (found.stream().allMatch(other -> other.distanceTo(point) >= 6)) found.add(point);
                    break;
                }
                if (found.size() == count) break;
            }
            test.assertValueEqual(found.size(), count, "The round has enough nearby standing points");
            return found;
        }

        int items(Vec3 at, net.minecraft.world.item.Item item) {
            return roundLevel.getEntitiesOfClass(ItemEntity.class, new AABB(BlockPos.containing(at)).inflate(2)).stream()
                    .map(ItemEntity::getItem).filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
        }

        List<ItemEntity> totems(Vec3 at) {
            return roundLevel.getEntitiesOfClass(ItemEntity.class, new AABB(BlockPos.containing(at)).inflate(2),
                    entity -> RevivalTotems.is(entity.getItem()));
        }

        int totemSlot(ServerPlayer player) {
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) if (RevivalTotems.is(inventory.getItem(i))) return i;
            return -1;
        }

        void chat(Actor listener, String fragment) {
            var lines = listener.client.packets(ClientboundSystemChatPacket.class).stream().map(packet -> packet.content().getString()).toList();
            test.assertTrue(lines.stream().anyMatch(line -> line.contains(fragment)), "Chat says \"" + fragment + "\": " + lines);
        }

        /** A monster's direct hit on {@code victim}, as {@code CombatRuntime} remembers one its arrow landed. */
        void monsterHit(ServerPlayer victim, Participant monster) {
            var origin = new CombatOrigin(round().roundId(), UUID.randomUUID(), new CombatActor(monster.id(),
                    ParticipantRole.MONSTER_DEPLOYED, monster.lifeSequence(), monster.monsterDeploymentSequence()),
                    AttackKind.MELEE, Optional.empty(), List.of());
            try {
                var field = CombatRuntime.class.getDeclaredField("CREDITS"); field.setAccessible(true);
                var memory = (KillCreditMemory) ((Map<?, ?>) field.get(null)).get(server);
                memory.hurt(victim.getUUID(), origin, server.getTickCount());
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }

        <T> T service(String name, Class<T> type) {
            try {
                var field = RoundRuntime.class.getDeclaredField(name); field.setAccessible(true);
                return type.cast(((Map<?, ?>) field.get(null)).get(server));
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }

    private static final class Actor {
        final Fixture f;
        final GameProfile profile;
        final TestClient client;
        ServerPlayer player;

        Actor(Fixture f, String name) {
            this.f = f;
            profile = new GameProfile(UUID.randomUUID(), name);
            client = new TestClient(f.server, profile);
        }

        String name() { return profile.name(); }
        Participant participant() { return f.round().participants().get(profile.id()); }

        void login(GameTestSequence sequence) {
            sequence.thenExecute(client::beginLogin)
                    .thenWaitUntil(() -> f.await(client.finishLogin(), name() + " loads"))
                    .thenExecute(() -> { player = client.player(); client.acknowledge(); })
                    .thenWaitUntil(() -> f.await(f.profiles.isReady(profile.id(), f.round()), name() + "'s profile is ready"))
                    .thenExecute(() -> player = client.player());
        }

        boolean monsterReady() {
            var current = f.server.getPlayerList().getPlayer(profile.id());
            if (current != null) player = current;
            return participant().role() == ParticipantRole.MONSTER_LOBBY && participant().online() && current != null
                    && current.isAlive() && current.level() == LobbyWorld.open(f.server).holdingLevel()
                    && f.profiles.isReady(profile.id(), f.round()) && !f.session.mutationPending();
        }

        void command(String text) {
            if (SpatialInputFixtures.handle(player, text)) return;
            try { f.server.getCommands().getDispatcher().execute(text, player.createCommandSourceStack()); }
            catch (Exception error) { throw new IllegalStateException(text, error); }
        }

        void move(Vec3 target) {
            player.teleport(new TeleportTransition(f.roundLevel, target, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
            client.acknowledge();
        }

        void disconnect() {
            if (!client.connected()) return;
            client.disconnect();
            f.test.assertTrue(f.server.getPlayerList().getPlayer(profile.id()) == null, name() + " left the player list");
            player = null;
        }
    }
}
