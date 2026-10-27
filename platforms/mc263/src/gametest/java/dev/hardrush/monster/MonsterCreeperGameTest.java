package dev.hardrush.monster;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.combat.MonsterCombatRuntime;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Native-server coverage; the visual swell interpolation still needs human client acceptance. */
public final class MonsterCreeperGameTest {
    private static final AtomicInteger ARENAS = new AtomicInteger();

    @GameTest(maxTicks = 200)
    public void heldIgniterMovesBetweenHandsAndReleaseReversesNativeSwell(GameTestHelper test) {
        var f = new Fixture(test, 2);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(f.owner, "creeper");
                var igniter = f.owner.getInventory().getItem(0);
                test.assertTrue(igniter.is(Items.GUNPOWDER) && MonsterItems.matches(f.owner, igniter, "igniter"),
                        "The first slot initially contains body-bound gunpowder");
                test.assertValueEqual(igniter.getHoverName().getString(), "引爆器", "The control is named 引爆器");
                test.assertValueEqual(igniter.get(DataComponents.USE_EFFECTS).speedMultiplier(), 1f,
                        "Native clients preserve walking speed while using the igniter");
                test.assertFalse(igniter.has(DataComponents.FOOD), "The input transport is not food");
                f.owner.getInventory().setItem(0, ItemStack.EMPTY);
                f.owner.getInventory().setItem(5, igniter);
                f.owner.getInventory().setSelectedSlot(5);
                var reference = EntityTypes.CREEPER.create(f.level, EntitySpawnReason.COMMAND);
                reference.setNoAi(true); reference.setNoGravity(true); reference.snapTo(f.center);
                test.assertTrue(MonsterRoles.useControl(f.owner, InteractionHand.MAIN_HAND), "A moved control still works");
                for (int tick = 0; tick < 12; tick++) { MonsterCreeper.tick(f.owner); reference.setSwellDir(1); reference.tick(); }
                test.assertValueEqual(MonsterCreeper.swellTicks(f.owner), Math.round(reference.getSwelling(1f) * 28),
                        "Held input advances exactly like native Creeper.tick");
                f.owner.releaseUsingItem();
                for (int tick = 0; tick < 5; tick++) { MonsterCreeper.tick(f.owner); reference.setSwellDir(-1); reference.tick(); }
                test.assertValueEqual(MonsterCreeper.swellTicks(f.owner), 7, "Native RELEASE_USE_ITEM reverses the fuse by one tick at a time");
                test.assertValueEqual(MonsterCreeper.swellTicks(f.owner), Math.round(reference.getSwelling(1f) * 28),
                        "Release decay matches the native curve");
                f.owner.getInventory().setItem(5, ItemStack.EMPTY);
                f.owner.setItemSlot(EquipmentSlot.OFFHAND, igniter);
                test.assertTrue(MonsterRoles.useControl(f.owner, InteractionHand.OFF_HAND), "The same bound control works in the off hand");
                MonsterCreeper.tick(f.owner);
                test.assertValueEqual(MonsterCreeper.swellTicks(f.owner), 8, "Holding again resumes existing progress");
                f.owner.releaseUsingItem();
                var fake = new ItemStack(Items.GUNPOWDER);
                fake.set(DataComponents.CUSTOM_NAME, Component.literal("引爆器"));
                f.owner.getInventory().setItem(5, fake);
                test.assertFalse(MonsterCreeper.use(f.owner, InteractionHand.MAIN_HAND), "A matching name cannot forge body ownership");
                for (int tick = 0; tick < 8; tick++) MonsterCreeper.tick(f.owner);
                test.assertFalse(MonsterCreeper.isSwelling(f.owner), "A fully released fuse returns to zero");
                test.assertTrue(f.owner.isAlive(), "Release never schedules an unconditional delayed explosion");
                reference.discard();
            } finally { f.close(); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void nativeFallDamageCapsSwellAndLightningPersistsOnlyForCurrentBody(GameTestHelper test) {
        var f = new Fixture(test, 2);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(f.owner, "creeper");
                var reference = EntityTypes.CREEPER.create(f.level, EntitySpawnReason.COMMAND);
                reference.setNoAi(true); reference.setNoGravity(true); reference.snapTo(f.center);
                f.owner.causeFallDamage(5, 1, f.owner.damageSources().fall());
                reference.causeFallDamage(5, 1, reference.damageSources().fall());
                test.assertValueEqual(f.owner.getHealth(), reference.getHealth(), "Native fall damage remains intact");
                test.assertValueEqual(MonsterCreeper.swellTicks(f.owner), Math.round(reference.getSwelling(1f) * 28),
                        "Fall progress uses native truncation after multiplying distance by 1.5");
                f.owner.setHealth(20); f.owner.damageCooldownTime = 0; f.owner.setInvulnerableTime(0);
                MonsterCreeper.use(f.owner, InteractionHand.MAIN_HAND);
                while (MonsterCreeper.swellTicks(f.owner) < 28) MonsterCreeper.tick(f.owner);
                f.owner.causeFallDamage(1, 0, f.owner.damageSources().fall());
                test.assertValueEqual(MonsterCreeper.swellTicks(f.owner), 25, "The native fall cap also lowers progress already above fuse minus five");
                f.owner.releaseUsingItem();
                MonsterCreeper.tick(f.owner);
                test.assertValueEqual(MonsterCreeper.swellTicks(f.owner), 24, "Unheld fall progress naturally decays");
                var bolt = EntityTypes.LIGHTNING_BOLT.create(f.level, EntitySpawnReason.COMMAND);
                f.owner.thunderHit(f.level, bolt);
                test.assertValueEqual(f.owner.getHealth(), 15f, "Lightning applies its original five damage once");
                test.assertTrue(((Creeper)MonsterRoles.nativeModel(f.owner)).isPowered(), "Lightning powers the actual native explosion body");
                var replacement = EntityTypes.CREEPER.create(f.level, EntitySpawnReason.COMMAND);
                MonsterCreeper.bindModel(f.owner, replacement);
                test.assertTrue(replacement.isPowered(), "A same-body model rebuild preserves charge");
                test.assertValueEqual(MonsterCreeper.swellTicks(f.owner), 24, "A same-body model rebuild preserves swell");
                MonsterCreeper.clear(f.owner);
                MonsterCreeper.bindModel(f.owner, replacement);
                test.assertFalse(replacement.isPowered(), "Clearing a departed body resets charge for the next body");
                test.assertValueEqual(MonsterCreeper.swellTicks(f.owner), 0, "A new body starts with an empty fuse");
                bolt.discard(); reference.discard(); replacement.discard();
            } finally { f.close(); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void chargedBlastKeepsNativeHeadsCloudSourceAndOneDeathBatch(GameTestHelper test) {
        var f = new Fixture(test, 3);
        test.runAfterDelay(80, () -> {
            boolean mobDrops = f.level.getGameRules().get(GameRules.MOB_DROPS);
            try (var ignored = f.installCombat()) {
                f.level.getGameRules().set(GameRules.MOB_DROPS, true, f.level.getServer());
                MonsterRoles.select(f.owner, "creeper");
                var model = (Creeper)MonsterRoles.nativeModel(f.owner);
                f.zombie(2, 0);
                MonsterRoles.nativeCreeperBlast(model, f.owner.getUUID(), f.center, 3);
                test.assertValueEqual(f.heads(), 0, "An uncharged native blast cannot drop a mob head");
                var bolt = EntityTypes.LIGHTNING_BOLT.create(f.level, EntitySpawnReason.COMMAND);
                f.owner.thunderHit(f.level, bolt); bolt.discard();
                f.level.getGameRules().set(GameRules.MOB_DROPS, false, f.level.getServer());
                f.zombie(2, 0);
                MonsterRoles.nativeCreeperBlast(model, f.owner.getUUID(), f.center, 3);
                test.assertValueEqual(f.heads(), 0, "The powered native source respects the mob-drops game rule");
                f.level.getGameRules().set(GameRules.MOB_DROPS, true, f.level.getServer());
                f.zombie(2, 0); f.zombie(0, 2); f.zombie(-2, 0);
                var victim = f.players.get(1);
                victim.teleportTo(f.center.x, f.center.y, f.center.z + 2); victim.setHealth(1);
                var survivor = f.players.get(2);
                survivor.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); survivor.setHealth(1000);
                survivor.teleportTo(f.center.x, f.center.y, f.center.z + 8);
                f.owner.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 800));
                f.owner.addEffect(new MobEffectInstance(MobEffects.INSTANT_DAMAGE, 800));
                MonsterCreeper.use(f.owner, InteractionHand.MAIN_HAND);
                for (int tick = 0; tick < 29; tick++) MonsterCreeper.tick(f.owner);
                test.assertTrue(f.owner.isAlive(), "The body stays alive for the first 29 held ticks");
                MonsterCreeper.tick(f.owner);
                test.assertFalse(f.owner.isAlive() || victim.isAlive(), "The thirtieth held tick ends the body and nearby victim");
                test.assertTrue(survivor.getHealth() < 1000, "Charged strength reaches beyond the normal six-block damage range");
                test.assertValueEqual(f.heads(), 1, "Several real eligible mobs produce at most one native head");
                var deaths = f.events.stream().filter(CombatEvent::lethal)
                        .filter(event -> event.victimId().equals(f.owner.getUUID()) || event.victimId().equals(victim.getUUID())).toList();
                test.assertValueEqual(deaths.size(), 2, "Both player deaths have combat evidence");
                test.assertValueEqual(deaths.get(0).batchId(), deaths.get(1).batchId(), "Explosion victims and self-death share one batch");
                test.assertTrue(f.level.getBlockState(BlockPos.containing(f.center).below()).is(Blocks.SMOOTH_STONE), "The charged blast preserves blocks");
                test.assertTrue(f.level.getBlockState(BlockPos.containing(f.center)).isAir(), "The charged blast creates no fire");
                var clouds = f.level.getEntitiesOfClass(AreaEffectCloud.class, f.owner.getBoundingBox().inflate(5));
                test.assertValueEqual(clouds.size(), 1, "An affected exploding body creates one native lingering cloud");
                var cloud = clouds.getFirst();
                test.assertValueEqual(cloud.getRadius(), 2.5f, "The cloud uses the native initial radius");
                test.assertValueEqual(cloud.getWaitTime(), 10, "The cloud uses the native wait time");
                test.assertValueEqual(cloud.getDuration(), 300, "The cloud uses the native lifetime");
                test.assertValueEqual(cloud.getRadiusOnUse(), -.5f, "The cloud shrinks by the native amount per victim");
                var source = MonsterCombatRuntime.source(cloud).orElseThrow();
                var origin = CombatRuntime.originOf(cloud).orElseThrow();
                test.assertValueEqual(origin.kind(), AttackKind.CREEPER, "Cloud damage keeps the Creeper attack kind");
                test.assertTrue(origin.causalParents().contains(CombatRuntime.originOf(model).orElseThrow().originId()),
                        "The cloud is a frozen child of the original native body");
                f.participants.put(f.owner.getUUID(), f.participants.get(f.owner.getUUID())
                        .withRole(ParticipantRole.HUMAN_ACTIVE).withSession("revived-offline", false));
                survivor.teleportTo(f.center.x, f.center.y, f.center.z);
                survivor.damageCooldownTime = 0; survivor.setInvulnerableTime(0);
                for (int tick = 0; tick < 10; tick++) { cloud.tickCount++; cloud.tick(); }
                test.assertTrue(survivor.hasEffect(MobEffects.SLOWNESS), "The native cloud still applies effects after its owner's death and role change");
                test.assertValueEqual(survivor.getEffect(MobEffects.SLOWNESS).getDuration(), 200,
                        "The native cloud applies one quarter of the copied effect duration");
                test.assertTrue(survivor.getLastDamageSource() != null
                                && survivor.getLastDamageSource().getDirectEntity() == cloud
                                && CombatRuntime.damageOrigin(f.level, survivor.getLastDamageSource()).filter(origin::equals).isPresent(),
                        "Instant cloud damage retains its original actor and Creeper provenance");
                test.assertValueEqual(MonsterCombatRuntime.source(cloud).orElseThrow(), source, "Cloud ownership remains immutable");
                f.round = UUID.randomUUID();
                test.assertTrue(MonsterCombatRuntime.reject(cloud, survivor), "A lingering cloud from an old round loses admission");
                test.assertFalse(survivor.addEffect(new MobEffectInstance(MobEffects.POISON, 200), cloud),
                        "Stale cloud effects are rejected at the native effect boundary");
            } finally {
                f.level.getGameRules().set(GameRules.MOB_DROPS, mobDrops, f.level.getServer());
                f.close();
            }
            test.succeed();
        });
    }

    private static final class Fixture implements AutoCloseable {
        final ServerLevel level;
        final Vec3 center;
        final List<ServerPlayer> players = new ArrayList<>();
        final List<EmbeddedChannel> channels = new ArrayList<>();
        final List<ChunkPos> forced = new ArrayList<>();
        final List<CombatEvent> events = new ArrayList<>();
        final Map<UUID, Participant> participants = new HashMap<>();
        final ServerPlayer owner;
        UUID round = UUID.randomUUID();

        Fixture(GameTestHelper test, int count) {
            level = test.getLevel(); center = new Vec3(60000 + ARENAS.getAndIncrement() * 128 + .5, 150, .5);
            var root = new ChunkPos((int)Math.floor(center.x) >> 4, (int)Math.floor(center.z) >> 4);
            for (int dx = -2; dx <= 4; dx++) for (int dz = -2; dz <= 2; dz++) {
                var chunk = new ChunkPos(root.x() + dx, root.z() + dz);
                level.setChunkForced(chunk.x(), chunk.z(), true); forced.add(chunk);
            }
            for (int dx = -10; dx <= count * 24; dx++) for (int dz = -10; dz <= 10; dz++) {
                var pos = BlockPos.containing(center).offset(dx, 0, dz);
                level.setBlockAndUpdate(pos.below(), Blocks.SMOOTH_STONE.defaultBlockState());
                for (int y = 0; y < 5; y++) level.setBlockAndUpdate(pos.above(y), Blocks.AIR.defaultBlockState());
            }
            for (int index = 0; index < count; index++) {
                var profile = new GameProfile(UUID.randomUUID(), "HRCreep" + ARENAS.get() + index);
                var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
                var connection = new SinkConnection(); channels.add(new EmbeddedChannel(connection));
                dev.hardrush.gametest.UnmanagedProfileFixtures.run(() -> level.getServer().getPlayerList().placeNewPlayer(connection, player,
                        CommonListenerCookie.createInitial(profile, false)));
                player.teleportTo(center.x + index * 24, center.y, center.z);
                player.setGameMode(GameType.SURVIVAL); player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                players.add(player);
                participants.put(player.getUUID(), new Participant(player.getUUID(), profile.name(),
                        index == 0 ? ParticipantRole.MONSTER_DEPLOYED : ParticipantRole.HUMAN_ACTIVE,
                        true, false, 0, 0, 1, false, true, MonsterRole.CREEPER, MonsterDimension.OVERWORLD, 0, 1));
            }
            owner = players.getFirst();
        }

        MonsterCombatRuntime.Scope installCombat() {
            var combat = dev.hardrush.gametest.RuntimeFixtures.monsterCombat(level.getServer(), new MonsterCombatRuntime.Access() {
                public Optional<RoundSnapshot> snapshot() { return Optional.of(new RoundSnapshot(round, 1, 42, 0, RulesConfig.defaults(),
                        RoundPhase.RUNNING, PauseReason.NONE, 0, 0, participants, false, false, null)); }
                public boolean competitionLocked() { return false; }
                public boolean formalLevel(ServerLevel candidate) { return candidate == level; }
            });
            var evidence = dev.hardrush.gametest.RuntimeFixtures.combat(level.getServer(), new CombatRuntime.RoundAccess() {
                public Optional<UUID> roundOf(ServerLevel candidate) { return candidate == level ? Optional.of(round) : Optional.empty(); }
                public Optional<CombatActor> actor(UUID roundId, UUID id) { return Optional.ofNullable(participants.get(id))
                        .map(p -> new CombatActor(p.id(), p.role(), p.lifeSequence(), p.monsterDeploymentSequence())); }
            }, events::add);
            return () -> { evidence.close(); combat.close(); };
        }

        void zombie(double x, double z) {
            var mob = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            mob.snapTo(center.add(x, 0, z)); mob.setNoAi(true); mob.setHealth(1);
            level.addFreshEntity(mob);
        }

        int heads() {
            return level.getEntitiesOfClass(ItemEntity.class, owner.getBoundingBox().inflate(12)).stream()
                    .filter(item -> item.getItem().is(Items.ZOMBIE_HEAD) || item.getItem().is(Items.PLAYER_HEAD))
                    .mapToInt(item -> item.getItem().getCount()).sum();
        }

        @Override public void close() {
            level.getEntities((Entity)null, owner.getBoundingBox().inflate(20), entity -> !(entity instanceof ServerPlayer)).forEach(Entity::discard);
            for (var player : players) { MonsterRoles.clear(player); level.getServer().getPlayerList().remove(player); }
            for (var channel : channels) channel.finishAndReleaseAll();
            for (var chunk : forced) level.setChunkForced(chunk.x(), chunk.z(), false);
        }
    }

    private static final class SinkConnection extends Connection {
        SinkConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) {}
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {}
    }
}
