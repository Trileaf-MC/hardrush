package dev.hardrush.revival;

import com.mojang.authlib.GameProfile;
import dev.hardrush.combat.CombatOriginHolder;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.gametest.TestClients;
import dev.hardrush.gametest.TestProperties;
import dev.hardrush.gametest.TestTeardown;
import dev.hardrush.notice.Notices;
import dev.hardrush.persistence.CommitResult;
import dev.hardrush.persistence.SnapshotWrite;
import dev.hardrush.persistence.SqliteRoundStore;
import dev.hardrush.revival.TotemProjection.Identity;
import dev.hardrush.round.RoundSession;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The in-place revival (docs/plan/复生图腾掉落.md 1.5, 2.5) on real fixture players and real lethal hits, outside the
 * competition runtime. The ledger is a real {@link RoundSession} on its own SQLite file, wired the way the production
 * runtime wires it: {@link TotemSave} books through {@link RoundSession#captureTotemSave}, the projection subtracts
 * {@link RoundSession#pendingTotemSaves}, {@link CombatRuntime} attributes hits and closes damage batches into the
 * session, and an {@code AFTER_DEATH} hook captures deaths as {@code RoundRuntime} does. Its commits run on the server
 * thread from a queue this test drains.
 *
 * <p>It binds {@link CombatRuntime} to the test world, so it only runs in its own JVM: {@code -PhardrushTotemSave=true}.
 */
public final class TotemSaveGameTest {
    private static final AtomicInteger ARENAS = new AtomicInteger();
    private static Harness harness;
    static {
        // Production captures a death in AFTER_DEATH; a saved body must never get here.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (harness != null && entity instanceof ServerPlayer player) harness.died(player, source);
        });
    }

    @GameTest(maxTicks = 1200)
    public void heldRevivalTotemsSaveInPlaceByVanillaRulesAndTheLedgerFollows(GameTestHelper test) {
        if (!Boolean.getBoolean(TestProperties.TOTEM_SAVE)) { test.succeed(); return; }
        var f = new Fixture(test);
        TestTeardown.atEnd(test, f::close);
        var h = new Harness(f);
        harness = h;
        TestTeardown.atEnd(test, () -> {
            harness = null;
            h.close();
        });
        test.onEachTick(h::pump);
        var monster = UUID.randomUUID();
        // A: monster hit, main hand. B: human hit, off hand. C: totem not in hand. D: void. E: /kill. F: locked.
        // G: vanilla totem first, revival second. H: revival first, vanilla second. I: a revival totem that cannot save,
        // vanilla second. J: two lethal hits in one tick with one charge. K: the human attacker, never hit. L: K's spear
        // jab, M: K's mace smash, which reach damage outside Player.attack or with their own damage type.
        var c = new LinkedHashMap<String, Client>();
        var charges = Map.ofEntries(Map.entry("A", 2), Map.entry("B", 1), Map.entry("C", 1), Map.entry("D", 1), Map.entry("E", 1),
                Map.entry("F", 1), Map.entry("G", 1), Map.entry("H", 1), Map.entry("I", 0), Map.entry("J", 1), Map.entry("K", 0),
                Map.entry("L", 1), Map.entry("M", 1));
        for (var tag : List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M")) c.put(tag, f.client(tag));
        var refs = new Object() { Zombie zombie; };

        test.startSequence().thenWaitUntil(f::awaitArena).thenExecute(() -> {
            var players = new LinkedHashMap<UUID, Participant>();
            c.forEach((tag, client) -> players.put(client.id(), participant(client.id(), client.name(), ParticipantRole.HUMAN_ACTIVE, charges.get(tag))));
            players.put(monster, participant(monster, "Hunter", ParticipantRole.MONSTER_LOBBY, 0));
            h.open(players);
        }).thenWaitUntil(() -> require(h.session != null && h.session.state() == RoundSession.State.OPEN, "the session opens"))
        .thenExecute(() -> {
            h.session.adaptersReady(h.round);
        }).thenWaitUntil(() -> require(!h.session.mutationPending(), "adapters ready")).thenExecute(() -> {
            for (var client : c.values()) h.session.onOnline(client.id(), client.name(), true);
            h.session.onOnline(monster, "Hunter", true);
        }).thenWaitUntil(() -> require(!h.session.mutationPending() && h.online(), "everyone online")).thenExecute(() -> {
            h.session.requestMonsterDeploy(h.round, monster, 1, 0, h.reply::set);
        }).thenWaitUntil(() -> require(h.reply.get() != null && !h.session.mutationPending(), "the monster deploys")).thenExecute(() -> {
            test.assertTrue(h.reply.get().success(), "Deployed: " + h.reply.get().message());
            h.configure();

            // Every human's stack is the committed balance; then each is placed in the hand its case needs.
            for (var client : c.values()) RevivalTotems.refresh(client.player());
            test.assertValueEqual(total(c.get("A").player()), 2, "A holds its two charges");
            toOffHand(c.get("B").player());
            var cp = c.get("C").player();
            cp.getInventory().setItem(9, cp.getMainHandItem().copy());
            cp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            toOffHand(c.get("G").player());
            c.get("G").player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            c.get("H").player().setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            var ip = c.get("I").player();
            test.assertValueEqual(total(ip), 0, "No committed charge projects no totem");
            // A stale copy of this very life, from before its charge was spent: the projection would take it back.
            ip.setItemInHand(InteractionHand.MAIN_HAND, RevivalTotems.held(Identity.of(h.round, h.committed(ip.getUUID())), 1, 1));
            ip.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            for (var client : c.values()) {
                client.player().setInvulnerableTime(0);
                client.player().addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 600, 0));
                client.bars().clear(); client.events().clear();
            }
            // The deployed monster's native body: its melee carries the owner's frozen identity, as a hunt's model does.
            refs.zombie = f.zombie();
            ((CombatOriginHolder) refs.zombie).hardrush$sealCombatOrigin(Optional.of(new CombatOrigin(h.round, UUID.randomUUID(),
                    new CombatActor(monster, ParticipantRole.MONSTER_DEPLOYED, 1, 1), AttackKind.MELEE,
                    Optional.of(refs.zombie.getUUID()), List.of())));
        }).thenExecute(() -> {
            var level = f.level;
            var sources = level.damageSources();
            var a = c.get("A").player();
            // A: a deployed monster's melee, with absorption on top of health; everything there was is the coin credit.
            a.setHealth(12);
            a.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0));
            // The effect fills absorption when the body next ticks; a fixture body is not ticked.
            a.setAbsorptionAmount(4);
            float shielded = a.getHealth() + a.getAbsorptionAmount();
            test.assertTrue(a.getAbsorptionAmount() > 0, "The monster's hit meets absorption too");
            a.hurtServer(level, sources.mobAttack(refs.zombie), 1000);
            saved(test, h, c.get("A"), 1);
            test.assertValueEqual(h.coins, List.of(shielded), "The monster's hit counts the health and absorption before the pop");
            test.assertValueEqual(a.getStats().getValue(Stats.ITEM_USED.get(Items.TOTEM_OF_UNDYING)), 1, "The use is counted like vanilla");
            test.assertTrue(a.getActiveEffects().stream().noneMatch(e -> e.is(MobEffects.SLOWNESS)), "Every earlier effect cleared");

            // G: a totem of undying in the main hand comes first; the revival totem in the off hand is untouched.
            var g = c.get("G").player();
            g.hurtServer(level, sources.generic(), 1000);
            test.assertTrue(g.isAlive() && g.getHealth() == 1 && g.getMainHandItem().isEmpty(), "Vanilla's own totem saved G");
            test.assertValueEqual(g.getOffhandItem().getCount(), 1, "G's revival totem is not used");
            test.assertValueEqual(h.session.pendingTotemSaves(g.getUUID()), 0, "Nothing is booked for G");
            test.assertValueEqual(effects(a), effects(g), "The revival totem's effects are vanilla's own");
            test.assertValueEqual(a.getAbsorptionAmount(), g.getAbsorptionAmount(), "The same absorption as vanilla");

            // H: the revival totem first in the main hand; the totem of undying in the off hand stays.
            var hp = c.get("H").player();
            hp.hurtServer(level, sources.generic(), 1000);
            saved(test, h, c.get("H"), 0);
            test.assertTrue(hp.getOffhandItem().is(Items.TOTEM_OF_UNDYING) && hp.getOffhandItem().getCount() == 1, "H keeps its totem of undying");

            // I: the revival totem cannot save (no charge): skipped, and vanilla finds the totem of undying behind it.
            var ip = c.get("I").player();
            ip.hurtServer(level, sources.generic(), 1000);
            test.assertTrue(ip.isAlive() && ip.getHealth() == 1 && ip.getOffhandItem().isEmpty(), "Vanilla's totem in the other hand saved I");
            test.assertTrue(RevivalTotems.is(ip.getMainHandItem()) && ip.getMainHandItem().getCount() == 1, "The revival totem is passed over intact");
            test.assertValueEqual(h.session.pendingTotemSaves(ip.getUUID()), 0, "Nothing is booked for I");

            // B: a human's melee, off hand: saved, no coin, and one totem drops at B's feet, pending until the save commits.
            var b = c.get("B").player();
            b.hurtServer(level, sources.playerAttack(c.get("K").player()), 1000);
            saved(test, h, c.get("B"), 0);
            test.assertTrue(b.getOffhandItem().isEmpty(), "The off-hand stack was used");
            test.assertValueEqual(h.coins.size(), 1, "A human's hit pays no monster coins");
            forcedOut(test, h, f, c.get("B"), "B");

            // L: K's spear jab, through the native weapon component the STAB action calls. M: K's mace smash. Both are
            // K's human melee (commit 55c17d9), so both force out a totem that drops like B's.
            var k = c.get("K").player();
            var l = c.get("L").player();
            l.teleportTo(f.base.getX() + .5, f.base.getY(), f.base.getZ() - 7.5);
            k.snapTo(l.getX(), l.getY(), l.getZ() - 3, 0, 0);
            k.setYHeadRot(0);
            var spear = new ItemStack(Items.IRON_SPEAR);
            k.setItemSlot(EquipmentSlot.MAINHAND, spear);
            // A fixture body is never ticked, so its attack cooldown never charges: any landed hit must be lethal.
            l.setHealth(0.05F);
            spear.get(DataComponents.PIERCING_WEAPON).attack(k, EquipmentSlot.MAINHAND);
            saved(test, h, c.get("L"), 0);
            forcedOut(test, h, f, c.get("L"), "L");
            var m = c.get("M").player();
            m.teleportTo(f.base.getX() + 6.5, f.base.getY(), f.base.getZ() - 7.5);
            k.snapTo(m.getX(), m.getY(), m.getZ() - 2, 0, 0);
            k.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.MACE));
            k.fallDistance = 2;
            m.setHealth(0.05F);
            k.attack(m);
            k.fallDistance = 0;
            saved(test, h, c.get("M"), 0);
            forcedOut(test, h, f, c.get("M"), "M");

            // C: the totem is carried but not held; D: the void and E: /kill bypass any totem. All die normally.
            var cp = c.get("C").player();
            cp.hurtServer(level, sources.generic(), 1000);
            var d = c.get("D").player();
            d.hurtServer(level, sources.fellOutOfWorld(), 1000);
            var e = c.get("E").player();
            e.hurtServer(level, sources.genericKill(), Float.MAX_VALUE);
            for (var tag : List.of("C", "D", "E")) {
                var p = c.get(tag).player();
                test.assertTrue(p.isDeadOrDying() && h.deaths.contains(p.getUUID()), "Dies normally: " + tag);
                test.assertValueEqual(h.session.pendingTotemSaves(p.getUUID()), 0, "Nothing is booked: " + tag);
                test.assertTrue(c.get(tag).events().stream().noneMatch(ev -> ev == 35), "No totem pops: " + tag);
            }

            // J: popped by one lethal hit, killed by the next in the same tick: the charge is spent once, before the death.
            var j = c.get("J").player();
            j.hurtServer(level, sources.generic(), 1000);
            saved(test, h, c.get("J"), 0);
            // Within the hit cooldown only a harder hit lands, by the difference: 1000 more is still lethal.
            j.hurtServer(level, sources.generic(), 2000);
            test.assertTrue(j.isDeadOrDying() && h.deaths.contains(j.getUUID()), "The second lethal hit kills J");
            test.assertValueEqual(h.session.pendingTotemSaves(j.getUUID()), 1, "Still exactly one save in flight");

            for (var tag : List.of("A", "B", "H", "G", "I"))
                test.assertFalse(h.deaths.contains(c.get(tag).id()), "Saved in place is not a death: " + tag);
        }).thenWaitUntil(() -> require(h.settled(), "every save and death commits")).thenExecute(() -> {
            // The ledger: one charge per save, the monster's forced pop counted as its kill, nothing voided.
            test.assertValueEqual(h.types.stream().filter(t -> t.startsWith("TOTEM_SAVE")).toList(), Collections.nCopies(6, "TOTEM_SAVE"),
                    "A, H, B, L, M and J were saved, none voided: " + h.types);
            test.assertValueEqual(h.committed(c.get("A").id()).revivalCharges(), 1, "A spent one");
            for (var tag : List.of("B", "H", "K", "I", "L", "M")) test.assertValueEqual(h.committed(c.get(tag).id()).revivalCharges(), 0, "Charges: " + tag);
            // A human forcing out a totem earns nothing by itself: what it forced out lies registered at each saved human.
            // A save without any credit (H's and J's generic hits) releases an unclaimed totem the same way (守恒 1.1).
            var registry = h.session.snapshot().orElseThrow().looseTotems();
            test.assertValueEqual(registry.size(), 5, "B's, L's, M's, H's and J's totems are registered: " + registry);
            for (var tag : List.of("H", "J")) {
                var id = c.get(tag).id();
                test.assertTrue(registry.values().stream().anyMatch(t -> t.victim().equals(id) && t.victimLife() == 1
                        && t.source() == LooseTotem.Source.UNCLAIMED && t.killer().isEmpty()), "Unclaimed: " + tag);
            }
            for (var tag : List.of("B", "L", "M")) {
                var token = h.drops.get(tag);
                test.assertValueEqual(registry.get(token), new LooseTotem(c.get(tag).id(), 1, LooseTotem.Source.HUMAN,
                        Optional.of(c.get("K").id()), registry.get(token).droppedAtChallengeNanos()), "Registered for others to take: " + tag);
                test.assertFalse(h.session.totemSaveDropInFlight(token), "No longer in flight: " + tag);
                test.assertTrue(floorTotems(f, c.get(tag).player()).stream().anyMatch(item -> RevivalTotems.dropToken(item.getItem())
                        .equals(Optional.of(token))), "It still lies in the world: " + tag);
            }
            // Told once a round to the others, never to the attacker or the saved human.
            var forced = Notices.format(TotemDrops.forcedLine(c.get("B").name(), 1)).getString();
            test.assertValueEqual(c.get("A").bars().stream().filter(forced::equals).count(), 1L, "A bystander is told of the first forced drop");
            test.assertTrue(c.get("K").bars().stream().noneMatch(bar -> bar.contains("逼出")), "The attacker is not told");
            test.assertValueEqual(h.committed(c.get("G").id()).revivalCharges(), 1, "Vanilla's totem spent no charge of G");
            var hunter = h.committed(monster);
            test.assertValueEqual(hunter.revivalCharges(), 1, "Forcing A's totem counts as the monster's kill");
            test.assertTrue(MonsterCoins.enabled(h.rules), "The fixture round is a coin round");
            test.assertValueEqual(hunter.role(), ParticipantRole.MONSTER_LOBBY, "A coin round recalls the hunter to the altar, as after a kill");
            for (var tag : List.of("A", "B", "H", "G", "I", "K"))
                test.assertValueEqual(h.committed(c.get(tag).id()).role(), ParticipantRole.HUMAN_ACTIVE, "Still the same human: " + tag);
            var carried = h.committed(c.get("C").id());
            test.assertTrue(carried.role() == ParticipantRole.MONSTER_LOBBY && carried.revivalCharges() == 1, "C died with its charge, kept for camp");
            var j = h.committed(c.get("J").id());
            test.assertTrue(j.role() == ParticipantRole.MONSTER_LOBBY && j.revivalCharges() == 0, "J died once its one charge was spent");
            test.assertValueEqual(h.journey(c.get("J").id()), List.of("TOTEM_SAVE", "DEATH_BATCH"), "J's save committed before J's death");

            // The projection already showed the spent charge and does not flicker once committed.
            for (var tag : List.of("A", "B", "H")) {
                var p = c.get(tag).player();
                RevivalTotems.refresh(p);
                test.assertValueEqual(total(p), h.committed(p.getUUID()).revivalCharges(), "Committed and shown agree: " + tag);
            }

            // F: the competition locked (a full checkpoint saving): the revival totem does not save.
            test.assertTrue(h.session.beginFullCheckpoint(), "A full checkpoint begins");
            var fp = c.get("F").player();
            test.assertTrue(RevivalTotems.is(fp.getMainHandItem()), "F holds its revival totem");
            fp.hurtServer(f.level, f.level.damageSources().generic(), 1000);
            test.assertTrue(fp.isDeadOrDying() && h.deaths.contains(fp.getUUID()), "Locked: F dies normally");
            test.assertValueEqual(h.session.pendingTotemSaves(fp.getUUID()), 0, "Nothing is booked while locked");
            h.session.endFullCheckpoint();
        }).thenIdle(1).thenSucceed();
    }

    /** The checks every in-place save shares: alive at 1 health, one charge less in hand and in flight, event 35, the line. */
    private static void saved(GameTestHelper test, Harness h, Client client, int left) {
        var p = client.player();
        test.assertTrue(p.isAlive() && !p.isDeadOrDying() && p.getHealth() == 1, "Saved in place at 1 health: " + client.name()
                + " (health " + p.getHealth() + ", dying " + p.isDeadOrDying() + ", at " + p.position() + ")");
        test.assertValueEqual(total(p), left, "The stack is one less at once: " + client.name());
        RevivalTotems.refresh(p);
        test.assertValueEqual(total(p), left, "The projection subtracts the save in flight: " + client.name());
        test.assertValueEqual(h.session.totemChargesLeft(p.getUUID()), left, "Charges left: " + client.name());
        test.assertTrue(h.session.pendingTotemSaves(p.getUUID()) >= 1, "Booked before the commit: " + client.name());
        test.assertTrue(client.events().contains((byte) 35), "Entity event 35 like vanilla's totem: " + client.name());
        test.assertTrue(p.getActiveEffects().stream().anyMatch(e -> e.is(MobEffects.REGENERATION)), "Totem effects: " + client.name());
        test.assertValueEqual(client.bars().getLast(), Notices.format(TotemSave.savedTip(left)).getString(), "The action bar: " + client.name());
    }

    private static Set<String> effects(ServerPlayer player) {
        return player.getActiveEffects().stream().map(e -> e.getEffect().getRegisteredName() + "/" + e.getAmplifier() + "/" + e.getDuration())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static void toOffHand(ServerPlayer player) {
        player.setItemInHand(InteractionHand.OFF_HAND, player.getMainHandItem().copy());
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    /** One floor totem at the saved human's feet, pending (in flight with its save, not yet registered). */
    private static void forcedOut(GameTestHelper test, Harness h, Fixture f, Client client, String tag) {
        var totems = floorTotems(f, client.player()).stream()
                .filter(item -> item.position().distanceTo(client.player().position()) < 2 && !h.drops.containsValue(
                        RevivalTotems.dropToken(item.getItem()).orElse(null))).toList();
        test.assertValueEqual(totems.size(), 1, "One totem drops at the saved human's feet: " + tag);
        var token = RevivalTotems.dropToken(totems.getFirst().getItem()).orElseThrow();
        test.assertTrue(h.session.totemSaveDropInFlight(token), "Pending with its save: " + tag);
        test.assertFalse(h.session.snapshot().orElseThrow().looseTotems().containsKey(token), "Not registered before the commit: " + tag);
        h.drops.put(tag, token);
    }

    private static List<ItemEntity> floorTotems(Fixture f, ServerPlayer near) {
        return f.level.getEntities(EntityTypeTest.forClass(ItemEntity.class), new AABB(near.blockPosition()).inflate(8),
                item -> RevivalTotems.is(item.getItem()));
    }

    private static Participant participant(UUID id, String name, ParticipantRole role, int charges) {
        return new Participant(id, name, role, false, false, 100, 0, 1, charges, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD,
                0, 0, Map.of(), MonsterWallet.EMPTY, 0, false);
    }

    private static int total(ServerPlayer player) {
        int count = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) if (RevivalTotems.is(inventory.getItem(i))) count += inventory.getItem(i).getCount();
        return count;
    }

    private static void require(boolean condition, String what) {
        if (!condition) throw new GameTestAssertException(Component.literal("Waiting until " + what), 0);
    }

    /** A real session on its own SQLite file, wired to the totem, projection and combat runtimes as production does. */
    private static final class Harness {
        final Fixture f;
        final UUID round = UUID.randomUUID();
        final RulesConfig rules = RulesConfig.defaults();
        final AtomicLong clock = new AtomicLong();
        final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        final AtomicReference<RoundSession.Reply> reply = new AtomicReference<>();
        final Set<UUID> deaths = new HashSet<>();
        final List<Float> coins = new ArrayList<>();
        final List<String> types = new ArrayList<>();
        final Map<UUID, List<String>> journeys = new HashMap<>();
        /** Each forced-out totem's drop token, by the saved human's tag. */
        final Map<String, UUID> drops = new HashMap<>();
        java.nio.file.Path directory;
        RoundSession session;
        Difficulty difficulty;

        Harness(Fixture f) { this.f = f; }

        void open(Map<UUID, Participant> players) {
            try {
                directory = Files.createTempDirectory("hardrush-totem-save");
                var path = directory.resolve("round.sqlite");
                var snapshot = new RoundSnapshot(round, 1, 9, 0, rules, RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                        100, 0, players, true, false, null);
                try (var store = new SqliteRoundStore(path)) {
                    if (store.commit(new SnapshotWrite(round, -1, 0, RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "FIXTURE"))
                            != CommitResult.APPLIED) throw new IllegalStateException("Fixture round not stored");
                }
                session = new RoundSession(path, clock::get, callbacks::add);
            } catch (Exception error) { throw new IllegalStateException(error); }
        }

        void pump() { for (Runnable task; (task = callbacks.poll()) != null; ) task.run(); }

        boolean online() { return session.snapshot().orElseThrow().participants().values().stream().allMatch(Participant::online); }

        Participant committed(UUID id) { return session.snapshot().orElseThrow().participants().get(id); }

        List<String> journey(UUID id) { return journeys.getOrDefault(id, List.of()); }

        boolean settled() {
            return !session.mutationPending() && session.state() == RoundSession.State.OPEN
                    && session.snapshot().orElseThrow().participants().keySet().stream().allMatch(id -> session.pendingTotemSaves(id) == 0);
        }

        void configure() {
            var server = f.server;
            difficulty = f.level.getDifficulty();
            // Monster melee scales with difficulty and does nothing on peaceful.
            server.setDifficulty(Difficulty.NORMAL, true);
            session.onCommitted((before, after) -> {
                TotemDrops.onCommitted(server, before, after);
                types.add(session.committedEventType());
                after.participants().forEach((id, p) -> {
                    var old = before.participants().get(id);
                    if (old != null && (old.role() != p.role() || old.revivalCharges() != p.revivalCharges()))
                        journeys.computeIfAbsent(id, ignored -> new ArrayList<>()).add(session.committedEventType());
                });
            });
            RevivalTotems.configure(server, new RevivalTotems.Access() {
                @Override public Optional<RoundSnapshot> committed() { return session.snapshot(); }
                @Override public boolean competitionLocked() { return session.competitionLocked(); }
                @Override public boolean bodyReady(ServerPlayer player, Participant participant) { return player.isAlive(); }
            });
            RevivalTotems.pendingSaves(server, session::pendingTotemSaves);
            // As LobbyRuntime binds it, without the profile service's body checks (fixture players have no profile).
            TotemSave.configure(server, (player, roundId, life, killCredit, drop) -> session.captureTotemSave(roundId, player.getUUID(), life,
                    killCredit, drop) ? OptionalInt.of(session.totemChargesLeft(player.getUUID())) : OptionalInt.empty());
            TotemDrops.configure(server, new TotemDrops.Access() {
                @Override public Optional<RoundSnapshot> view() { return session.view(); }
                @Override public boolean open() { return session.state() == RoundSession.State.OPEN; }
                @Override public boolean collects(ServerPlayer player) { return true; }
                @Override public boolean hunts(ServerPlayer player) { return true; }
                @Override public void refresh(ServerPlayer player) { RevivalTotems.refresh(player); }
                @Override public void requestPickup(UUID round, UUID token, UUID player, int life, int sortie,
                        java.util.function.Consumer<RoundSession.Reply> reply) {
                    session.requestLooseTotemPickup(round, token, player, life, sortie, reply);
                }
                @Override public boolean saveInFlight(UUID token) { return session.totemSaveDropInFlight(token); }
            });
            // The deployed monster's model may only hit within its own running round.
            dev.hardrush.combat.MonsterCombatRuntime.configure(server, new dev.hardrush.combat.MonsterCombatRuntime.Access() {
                @Override public Optional<RoundSnapshot> snapshot() { return session.snapshot(); }
                @Override public boolean competitionLocked() { return session.competitionLocked(); }
                @Override public boolean formalLevel(ServerLevel level) { return level == f.level; }
            });
            CombatRuntime.configure(server, new CombatRuntime.RoundAccess() {
                @Override public Optional<UUID> roundOf(ServerLevel level) { return level == f.level ? Optional.of(round) : Optional.empty(); }
                @Override public Optional<CombatActor> actor(UUID roundId, UUID playerId) {
                    return session.snapshot().filter(r -> r.roundId().equals(roundId)).map(r -> r.participants().get(playerId))
                            .filter(p -> p.role() == ParticipantRole.HUMAN_ACTIVE)
                            .map(p -> new CombatActor(p.id(), p.role(), p.lifeSequence(), 0));
                }
                @Override public void batchClosed(UUID roundId, UUID batchId) { session.finishDamageBatch(roundId, batchId); }
                @Override public void playerDamaged(ServerLevel level, ServerPlayer victim, CombatOrigin origin, float actualDamage) {
                    coins.add(actualDamage);
                }
            }, event -> session.captureLethalEvidence(event));
        }

        /** {@code RoundRuntime}'s capture of a human's death, minus the profile hold; nothing is captured while locked. */
        void died(ServerPlayer player, DamageSource source) {
            deaths.add(player.getUUID());
            var killCredit = CombatRuntime.killCredit(player.level(), player, source);
            var p = session == null ? null : session.snapshot().map(r -> r.participants().get(player.getUUID())).orElse(null);
            if (p == null || p.role() != ParticipantRole.HUMAN_ACTIVE || session.competitionLocked()) return;
            session.captureDeath(round, p.id(), p.lifeSequence(), 0, CombatRuntime.currentBatch(player.level()).orElse(null),
                    killCredit, false, Optional.empty());
        }

        void close() {
            TotemSave.unconfigure(f.server);
            TotemDrops.unconfigure(f.server);
            RevivalTotems.unconfigure(f.server);
            CombatRuntime.unconfigure(f.server);
            dev.hardrush.combat.MonsterCombatRuntime.unconfigure(f.server);
            if (difficulty != null) f.server.setDifficulty(difficulty, true);
            try { if (session != null) session.close(); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
    }

    private record Client(ServerPlayer player, GameProfile profile, EmbeddedChannel channel, List<String> bars, List<Byte> events) {
        UUID id() { return profile.id(); }
        String name() { return profile.name(); }
    }

    private static final class Fixture {
        final ServerLevel level;
        final MinecraftServer server;
        final BlockPos base;
        final int arena = ARENAS.getAndIncrement();
        final List<Client> clients = new ArrayList<>();
        final List<net.minecraft.world.entity.Entity> keep = new ArrayList<>();

        Fixture(GameTestHelper test) {
            level = test.getLevel();
            server = level.getServer();
            base = new BlockPos(44000 + arena * 64, 150, 3000);
            for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++)
                level.setChunkForced((base.getX() >> 4) + cx, (base.getZ() >> 4) + cz, true);
            for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
                level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.SMOOTH_STONE.defaultBlockState());
                for (int dy = 0; dy < 4; dy++) level.setBlockAndUpdate(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
            }
        }

        Client client(String tag) {
            var profile = new GameProfile(UUID.randomUUID(), "HRSave" + tag + arena);
            var bars = new ArrayList<String>();
            var events = new ArrayList<Byte>();
            var player = new ServerPlayer(server, level, profile, TestClients.information());
            var connection = new Recorder(bars, events, player);
            var channel = new EmbeddedChannel(connection);
            dev.hardrush.gametest.UnmanagedProfileFixtures.run(() -> server.getPlayerList().placeNewPlayer(connection, player,
                    TestClients.cookie(profile)));
            player.teleportTo(base.getX() - 10 + clients.size() * 2 + .5, base.getY(), base.getZ() + .5);
            player.setGameMode(GameType.SURVIVAL);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            bars.clear(); events.clear();
            var client = new Client(player, profile, channel, bars, events);
            clients.add(client);
            return client;
        }

        void awaitArena() { require(level.isPositionEntityTicking(base), "the arena simulates entities"); }

        Zombie zombie() {
            var zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            zombie.setPos(Vec3.atBottomCenterOf(base.offset(0, 0, 6)));
            zombie.setNoAi(true);
            zombie.setPersistenceRequired();
            level.addFreshEntity(zombie);
            keep.add(zombie);
            return zombie;
        }

        void close() {
            for (var entity : keep) entity.discard();
            for (var c : clients) {
                var current = server.getPlayerList().getPlayer(c.id());
                if (current != null) server.getPlayerList().remove(current);
                c.channel().finishAndReleaseAll();
            }
            for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++)
                level.setChunkForced((base.getX() >> 4) + cx, (base.getZ() >> 4) + cz, false);
        }
    }

    /** Keeps the action-bar lines and the player's own entity events. */
    private static final class Recorder extends Connection {
        final List<String> bars;
        final List<Byte> events;
        final ServerPlayer self;
        Recorder(List<String> bars, List<Byte> events, ServerPlayer self) {
            super(PacketFlow.SERVERBOUND);
            this.bars = bars; this.events = events; this.self = self;
        }
        private void record(Packet<?> packet) {
            if (packet instanceof ClientboundSetActionBarTextPacket bar) bars.add(bar.text().getString());
            if (packet instanceof ClientboundEntityEventPacket event && event.getEntity(self.level()) == self) events.add(event.getEventId());
        }
        @Override public void send(Packet<?> packet) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { record(packet); }
    }
}
