package dev.hardrush.gametest;

import com.mojang.serialization.JsonOps;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.DawnPact;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.p1.pact.DawnPactData;
import dev.hardrush.p1.pact.DawnPactRuntime;
import dev.hardrush.p1.combat.P1CombatRuntime;
import dev.hardrush.p2.combat.P2CombatRuntime;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Native server regressions; no graphical Minecraft client or computer use. */
public final class DawnPactGameTest {
    @GameTest(maxTicks=200)
    public void nativeSavedDataReusesOneStateAndReloadsFromDisk(GameTestHelper test) {
        var level = test.getLevel();
        var root = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("hardrush/dawn-pact-storage-tests/" + UUID.randomUUID());
        try {
            java.nio.file.Files.createDirectories(root);
            try (var storage = new net.minecraft.world.level.storage.SavedDataStorage(root, net.minecraft.util.datafix.DataFixers.getDataFixer(), level.registryAccess())) {
                var data = DawnPactData.get(storage, false);
                data.start();
                test.assertTrue(data == DawnPactData.get(storage, true), "All admissions/damage/UI reads use the same native saved instance");
                test.assertTrue(data.protectedAt(23_999), "Final tick remains protected");
                test.assertFalse(data.protectedAt(24_000), "Sunrise durably releases contract");
            }
            try (var storage = new net.minecraft.world.level.storage.SavedDataStorage(root, net.minecraft.util.datafix.DataFixers.getDataFixer(), level.registryAccess())) {
                var data = DawnPactData.get(storage, false);
                test.assertTrue(data.started() && data.released(), "New native storage session reads both flags from the owned disk file");
                test.assertFalse(data.protectedAt(0), "Reload and clock rewind cannot revive the contract");
            }
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        test.succeed();
    }
    @GameTest(maxTicks=200)
    public void pvpArrowsFireEffectsAndSummonsAreBlockedUntilSunrise(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            test.assertFalse(f.victim.hurtServer(f.level, f.level.damageSources().playerAttack(f.attacker), 4), "Direct PvP rejected");
            var arrow = new TestArrow(f.level, f.attacker);
            arrow.setDeltaMovement(1, 0, 0); arrow.setBaseDamage(4); arrow.setRemainingFireTicks(200);
            arrow.impact(f.victim);
            test.assertValueEqual(f.victim.getHealth(), 20F, "Burning tipped arrow cannot damage");
            test.assertFalse(f.victim.isOnFire(), "Arrow cannot ignite protected target");
            test.assertFalse(f.victim.hasEffect(MobEffects.POISON), "Arrow cannot leave delayed poison");
            test.assertFalse(f.victim.addEffect(new MobEffectInstance(MobEffects.POISON, 200), f.attacker), "Player harmful potion rejected");
            test.assertTrue(f.victim.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200), f.attacker), "Beneficial potion preserved");
            f.victim.removeAllEffects();
            var body = EntityTypes.ZOMBIE.create(f.level, EntitySpawnReason.COMMAND);
            f.roles.put(f.attacker.getUUID(), ParticipantRole.MONSTER_DEPLOYED);
            test.assertTrue(DawnPactRuntime.reject(f.victim, f.attacker), "Human cannot harm a player in monster form either");
            P2CombatRuntime.stampSource(body, f.attacker);
            test.assertFalse(body.doHurtTarget(f.level, f.victim), "Player-owned monster native attack rejected");
            body.discard();
            f.roles.put(f.attacker.getUUID(), ParticipantRole.HUMAN_ACTIVE);
            test.assertTrue(f.victim.hurtServer(f.level, f.level.damageSources().fall(), 2), "Environment damage still applies");
            f.victim.damageCooldownTime = 0;
            var natural = EntityTypes.ZOMBIE.create(f.level, EntitySpawnReason.COMMAND);
            test.assertFalse(DawnPactRuntime.rejectDamage(f.victim, f.level.damageSources().mobAttack(natural)), "Natural mob is not attributed to a player");
            test.assertTrue(f.victim.hurtServer(f.level, f.level.damageSources().mobAttack(natural), 2), "Natural mobs still hurt");
            natural.discard(); f.victim.damageCooldownTime = 0;
            test.assertTrue(f.victim.hurtServer(f.level, f.level.damageSources().playerAttack(f.victim), 1), "Self harm not reclassified as another player");
            f.time(24_000); f.victim.damageCooldownTime = 0;
            test.assertTrue(f.victim.hurtServer(f.level, f.level.damageSources().playerAttack(f.attacker), 2), "PvP enabled at sunrise without waiting for UI tick");
            f.time(0); f.victim.damageCooldownTime = 0;
            test.assertTrue(f.victim.hurtServer(f.level, f.level.damageSources().playerAttack(f.attacker), 2), "Time rewind cannot reactivate protection");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void capturedOfflineExplosionsAndCrossDimensionSourcesRespectPact(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            var tnt = new net.minecraft.world.entity.item.PrimedTnt(f.level, f.attacker.getX(), f.attacker.getY(), f.attacker.getZ(), f.attacker);
            P1CombatRuntime.tntCreated(tnt, f.attacker);
            var explosion = f.level.damageSources().explosion(tnt, f.attacker);
            test.assertFalse(f.victim.hurtServer(f.level, explosion, 10), "Player TNT blocked");
            f.level.getServer().getPlayerList().remove(f.attacker);
            test.assertFalse(f.victim.hurtServer(f.level, explosion, 10), "Frozen TNT attribution survives shooter going offline");
            var cloud = new net.minecraft.world.entity.AreaEffectCloud(f.level, f.victim.getX(), f.victim.getY(), f.victim.getZ());
            cloud.setOwner(f.attacker);
            test.assertTrue(DawnPactRuntime.reject(cloud, f.victim), "Lingering potion uses its vanilla persisted owner reference");
            test.assertFalse(f.victim.addEffect(new MobEffectInstance(MobEffects.POISON, 100), cloud), "Lingering poison blocked");
            cloud.discard();
            try (var action = P1CombatRuntime.explosion(f.level, tnt)) {
                test.assertTrue(DawnPactRuntime.reject(null, f.victim), "Chained explosions with no native owner retain captured player source");
            }
            tnt.discard();
            var other = f.level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
            if (other != null) {
                var original = f.victim.level();
                f.victim.setServerLevel(other);
                test.assertTrue(DawnPactRuntime.reject(f.attacker, f.victim), "Nether shares overworld grace deadline");
                f.victim.setServerLevel(original);
            }
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void nativeBossbarSoundsJoinReconnectAndSunrisePackets(GameTestHelper test) {
        try (var f = new Fixture(test); var capture = new Capture(f.victim)) {
            f.time(0); DawnPactRuntime.tick(f.level.getServer());
            test.assertValueEqual(capture.chats(DawnPact.INTRO), 1L, "Entering round explains protection once");
            test.assertTrue(capture.packets.stream().anyMatch(ClientboundBossEventPacket.class::isInstance), "Native bossbar packet sent");
            test.assertValueEqual(capture.boss().color, net.minecraft.world.BossEvent.BossBarColor.GREEN, "Initial native bar is green");
            test.assertTrue(capture.boss().name.startsWith("黎明契约 · 日出前暂息纷争"), "Initial native bar has the agreed wording");
            DawnPactRuntime.tick(f.level.getServer());
            test.assertValueEqual(capture.chats(DawnPact.INTRO), 1L, "Repeated ticks do not spam intro");
            f.time(20_400); DawnPactRuntime.tick(f.level.getServer());
            test.assertValueEqual(capture.boss().color, net.minecraft.world.BossEvent.BossBarColor.YELLOW, "180-second native bar is yellow");
            test.assertValueEqual(capture.boss().name, "黎明契约即将解除 · 距猎杀开始还有 03:00", "Warning packet uses final user wording");
            test.assertValueEqual(capture.sounds(), 1L, "180-real-second warning plays once");
            f.time(22_800); DawnPactRuntime.tick(f.level.getServer());
            test.assertValueEqual(capture.boss().color, net.minecraft.world.BossEvent.BossBarColor.RED, "Final minute is red");
            test.assertValueEqual(capture.boss().name, "准备作战", "Final minute has exactly the requested text");
            test.assertValueEqual(capture.sounds(), 1L, "Final 60-second combat text does not start second-by-second sounds early");
            for (int seconds = 10; seconds >= 1; seconds--) {
                f.time(24_000 - seconds * 20L);
                DawnPactRuntime.tick(f.level.getServer()); DawnPactRuntime.tick(f.level.getServer());
            }
            test.assertValueEqual(capture.sounds(), 11L, "Last 10 seconds play exactly one cue each");
            f.time(24_000); DawnPactRuntime.tick(f.level.getServer()); DawnPactRuntime.tick(f.level.getServer());
            test.assertFalse(capture.boss().active, "Sunrise removes native bossbar");
            test.assertValueEqual(capture.sounds(), 12L, "Sunrise sound occurs once");
            test.assertValueEqual(capture.chats("极限追猎开始，祝你好运"), 1L, "Sunrise announcement occurs once");
            test.assertTrue(capture.packets.stream().anyMatch(ClientboundSetSubtitleTextPacket.class::isInstance), "Sunrise native subtitle sent");
            var encoded = DawnPactData.CODEC.encodeStart(JsonOps.INSTANCE, f.data).getOrThrow();
            f.data = DawnPactData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            f.time(0);
            test.assertFalse(DawnPactRuntime.reject(f.attacker, f.victim), "Serialized release remains final after reload and clock rewind");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void preparationDoesNotSpendFirstDayAndReloadDoesNotResetIt(GameTestHelper test) {
        try (var f = new Fixture(test)) {
            f.data = new DawnPactData(false, false); f.phase = RoundPhase.WAITING; f.time(30_000);
            DawnPactRuntime.tick(f.level.getServer());
            test.assertFalse(f.data.started(), "Waiting never starts protection clock");
            f.phase = RoundPhase.RUNNING; DawnPactRuntime.tick(f.level.getServer());
            test.assertValueEqual(f.clock().totalTicks(), 0L, "First actual start begins at sunrise");
            f.time(12_000);
            var encoded = DawnPactData.CODEC.encodeStart(JsonOps.INSTANCE, f.data).getOrThrow();
            f.data = DawnPactData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            DawnPactRuntime.tick(f.level.getServer());
            test.assertValueEqual(f.clock().totalTicks(), 12_000L, "Reloaded running round retains original day progress");
            f.time(48_000); DawnPactRuntime.tick(f.level.getServer());
            test.assertTrue(f.data.released(), "Sleeping/time jump beyond dawn releases protection");
        }
        test.succeed();
    }

    private static final class TestArrow extends Arrow {
        TestArrow(ServerLevel level, ServerPlayer owner) { super(level, owner, PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.POISON), new ItemStack(Items.BOW)); }
        void impact(Entity target) { hitTargetOrDeflectSelf(new EntityHitResult(target)); }
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper test;
        final ServerLevel level;
        final UUID round = UUID.randomUUID();
        final Map<UUID, ParticipantRole> roles = new LinkedHashMap<>();
        final ServerPlayer attacker, victim;
        final List<RuntimeFixtures.Scope> scopes = new ArrayList<>();
        final net.minecraft.world.clock.ClockState original;
        final net.minecraft.world.Difficulty difficulty;
        DawnPactData data = new DawnPactData(true, false);
        RoundPhase phase = RoundPhase.RUNNING;
        Fixture(GameTestHelper test) {
            this.test = test; level = test.getLevel(); original = clock().packState(); difficulty = level.getDifficulty();
            level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
            attacker = player(); victim = player(); time(0);
            var monster = RuntimeFixtures.monsterCombat(level.getServer(), new P2CombatRuntime.Access() {
                public Optional<RoundSnapshot> snapshot() { return Optional.of(Fixture.this.snapshot()); }
                public boolean competitionLocked() { return false; }
                public boolean formalLevel(ServerLevel candidate) { return true; }
            }); scopes.add(monster::close);
            var evidence = RuntimeFixtures.combat(level.getServer(), new P1CombatRuntime.RoundAccess() {
                public Optional<UUID> roundOf(ServerLevel candidate) { return Optional.of(round); }
                public Optional<CombatActor> actor(UUID roundId, UUID id) {
                    return Optional.ofNullable(roles.get(id)).map(role -> new CombatActor(id, role, 1, role.isMonster() ? 1 : 0));
                }
            }, event -> {}); scopes.add(evidence::close);
            scopes.add(RuntimeFixtures.dawnPact(level.getServer(), new DawnPactRuntime.Access() {
                public Optional<RoundSnapshot> snapshot() { return Optional.of(Fixture.this.snapshot()); }
                public ServerLevel overworld() { return level; }
                public DawnPactData data() { return data; }
                public boolean formalLevel(ServerLevel candidate) { return true; }
            }));
        }
        ServerPlayer player() {
            var player = test.makeMockServerPlayerInLevel();
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setGameMode(GameType.SURVIVAL); player.getAbilities().invulnerable = false; player.setHealth(20);
            var position = test.absoluteVec(new Vec3(4, 4, 4));
            player.setPosRaw(position.x, position.y, position.z);
            roles.put(player.getUUID(), ParticipantRole.HUMAN_ACTIVE);
            return player;
        }
        RoundSnapshot snapshot() {
            var participants = new LinkedHashMap<UUID, Participant>();
            roles.forEach((id, role) -> participants.put(id, new Participant(id, "Pact", role, true, false, 0, 0, 1, false, true,
                    MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, role.isMonster() ? 1 : 0)));
            return new RoundSnapshot(round, 1, 42, 0, RulesConfig.defaults(), phase, PauseReason.NONE, 0, 0, participants, false, false, null);
        }
        net.minecraft.core.Holder<net.minecraft.world.clock.WorldClock> clockKey() { return level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD); }
        net.minecraft.world.clock.ServerClockManager.ServerClockInstance clock() { return level.clockManager().getInstance(clockKey()); }
        void time(long ticks) { level.clockManager().setTotalTicks(clockKey(), ticks); }
        public void close() {
            Collections.reverse(scopes); scopes.forEach(RuntimeFixtures.Scope::close);
            clock().loadFrom(original);
            level.getServer().setDifficulty(difficulty, true);
            for (var player : List.of(attacker, victim)) level.getServer().getPlayerList().remove(player);
        }
    }
    private static final class Capture implements AutoCloseable {
        final ServerPlayer player;
        final ServerGamePacketListenerImpl previous;
        final List<Packet<?>> packets;
        final EmbeddedChannel channel;
        Capture(ServerPlayer player) {
            this.player = player; previous = player.connection;
            var connection = new RecordingConnection(); packets = connection.packets;
            channel = new EmbeddedChannel(connection);
            player.connection = new ServerGamePacketListenerImpl(player.level().getServer(), connection, player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }
        long sounds() { return packets.stream().filter(ClientboundSoundPacket.class::isInstance).count(); }
        BossView boss() {
            var state = new BossView();
            packets.stream().filter(ClientboundBossEventPacket.class::isInstance).map(ClientboundBossEventPacket.class::cast).forEach(packet -> packet.dispatch(state));
            return state;
        }
        long chats(String text) { return packets.stream().filter(ClientboundSystemChatPacket.class::isInstance)
                .map(ClientboundSystemChatPacket.class::cast).filter(packet -> packet.content().getString().equals(text)).count(); }
        public void close() { player.connection = previous; channel.finishAndReleaseAll(); }
    }
    private static final class BossView implements ClientboundBossEventPacket.Handler {
        String name = "";
        net.minecraft.world.BossEvent.BossBarColor color;
        boolean active;
        @Override public void add(UUID id, net.minecraft.network.chat.Component text, float progress, net.minecraft.world.BossEvent.BossBarColor color,
                net.minecraft.world.BossEvent.BossBarOverlay overlay, boolean darken, boolean music, boolean fog) {
            name = text.getString(); this.color = color; active = true;
        }
        @Override public void updateName(UUID id, net.minecraft.network.chat.Component text) { name = text.getString(); }
        @Override public void updateStyle(UUID id, net.minecraft.world.BossEvent.BossBarColor color, net.minecraft.world.BossEvent.BossBarOverlay overlay) { this.color = color; }
        @Override public void remove(UUID id) { active = false; }
    }
    private static final class RecordingConnection extends Connection {
        final List<Packet<?>> packets = new ArrayList<>();
        RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        private void record(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(this::record); else packets.add(packet);
        }
        @Override public void send(Packet<?> packet) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { record(packet); }
    }
}
