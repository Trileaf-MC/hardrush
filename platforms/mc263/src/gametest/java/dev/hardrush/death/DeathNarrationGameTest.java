package dev.hardrush.death;

import com.mojang.authlib.GameProfile;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.combat.MonsterCombatRuntime;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.gametest.RuntimeFixtures;
import dev.hardrush.gametest.TestClient;
import dev.hardrush.gametest.TestTeardown;
import dev.hardrush.monster.MonsterCreeper;
import dev.hardrush.monster.MonsterRoles;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Death messages name who was behind a monster (docs/plan/死亡消息.md) on real fixture players killed through the
 * native death path: the vanilla sentence and key stay, the monster's name changes. Revenge debts are covered by
 * {@code RevengeLedgerTest}; the round-world gate around them needs a live round.
 */
public final class DeathNarrationGameTest {
    private static final AtomicInteger ARENAS = new AtomicInteger();
    private static final int ARENA_SPACING = 256;

    @GameTest(maxTicks = 200)
    public void monsterBodiesAndMonsterVictimsAreNamedInVanillaSentences(GameTestHelper test) {
        var f = new Fixture(test, MonsterRole.SKELETON, 5);
        TestTeardown.atEnd(test, f::close);
        test.runAfterDelay(80, () -> {
            try (var ignored = f.installCombat()) {
                MonsterRoles.select(f.owner, "skeleton");
                var model = MonsterRoles.nativeModel(f.owner);
                var skeleton = monster(EntityTypes.SKELETON, f.owner);
                var slain = f.players.get(1);
                slain.hurtServer(f.level, f.level.damageSources().mobAttack(model), 1000);
                var message = f.death(1);
                test.assertValueEqual(key(message), "death.attack.mob", "The vanilla mob sentence is kept");
                test.assertValueEqual(arg(message, 1).getString(), skeleton, "The killer is the player behind the skeleton");
                var hover = (HoverEvent.ShowEntity) arg(message, 1).getStyle().getHoverEvent();
                test.assertTrue(hover.entity().type == EntityTypes.SKELETON && hover.entity().uuid.equals(f.owner.getUUID()),
                        "Hovering shows the native entity tooltip of the form, with the player's own id");
                test.assertValueEqual(f.chat(0).getLast(), message.getString(), "Everybody reads the same sentence");

                var faller = f.players.get(2);
                faller.hurtServer(f.level, f.level.damageSources().mobAttack(model), 1);
                faller.fallDistance = 20;
                faller.hurtServer(f.level, f.level.damageSources().fall(), 1000);
                message = f.death(2);
                test.assertValueEqual(key(message), "death.fell.assist", "A fall after the hit keeps vanilla's assisted wording");
                test.assertValueEqual(arg(message, 1).getString(), skeleton, "The assist names the same player");

                var human = f.players.get(3);
                test.assertValueEqual(f.owner.getDisplayName().getString(), f.owner.getName().getString(),
                        "Outside a death message a monster keeps its plain name");
                DeathNarration.savedInPlace(human, f.level.damageSources().mobAttack(model), Optional.empty());
                test.assertValueEqual(f.chat(3).getLast(), human.getName().getString() + "险些被" + skeleton + "杀死，靠复生图腾挺了过来",
                        "A revival totem's save is told with its would-be killer");
                DeathNarration.savedInPlace(human, f.level.damageSources().lava(), Optional.empty());
                test.assertValueEqual(f.chat(3).getLast(), human.getName().getString() + "险些丧命，靠复生图腾挺了过来",
                        "A save from the world names nobody");
                DeathNarration.savedInPlace(human, f.level.damageSources().lava(), Optional.of(f.monsterHit()));
                test.assertValueEqual(f.chat(3).getLast(), human.getName().getString() + "险些被" + skeleton + "杀死，靠复生图腾挺了过来",
                        "A save from the world after a monster's hit names the credited monster");

                // Vanilla would prefer the earlier human hit (lastHurtByPlayer) over the later monster body.
                var burned = f.players.get(4);
                // Each hit outdoes the last, so invulnerability frames take none of them.
                burned.hurtServer(f.level, f.level.damageSources().playerAttack(human), 1);
                burned.hurtServer(f.level, f.level.damageSources().mobAttack(model), 2);
                burned.hurtServer(f.level, f.level.damageSources().lava(), 1000);
                message = f.death(4);
                test.assertValueEqual(key(message), "death.attack.lava.player", "Lava after a hit keeps vanilla's escaping wording");
                test.assertValueEqual(arg(message, 1).getString(), skeleton,
                        "The sentence names the round's credit, the monster that hit last, as the revival totem does");

                var zombie = EntityTypes.ZOMBIE.create(f.level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                zombie.entityTags().add("hardrush.summon-owner=" + f.owner.getUUID());
                DeathNarration.savedInPlace(human, f.level.damageSources().mobAttack(zombie), Optional.empty());
                var zombieName = EntityTypes.ZOMBIE.getDescription().getString();
                test.assertValueEqual(f.chat(3).getLast(), human.getName().getString() + "险些被" + f.owner.getName().getString()
                        + "召唤的" + zombieName + "杀死，靠复生图腾挺了过来", "A hatched companion is its owner's summon");
                zombie.entityTags().add("hardrush.soul-credit=" + dev.hardrush.core.combat.CombatOriginCodec.encode(f.monsterHit()));
                DeathNarration.savedInPlace(human, f.level.damageSources().mobAttack(zombie), Optional.empty());
                test.assertValueEqual(f.chat(3).getLast(), human.getName().getString() + "险些被" + f.owner.getName().getString()
                        + "收编的" + zombieName + "杀死，靠复生图腾挺了过来", "A bag creature following its releaser was caught, not summoned");
                zombie.discard();

                var arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(EntityTypes.ARROW, f.level);
                arrow.entityTags().add("hardrush.shot-owner=" + f.owner.getUUID());
                arrow.entityTags().add("hardrush.shot-form=stray");
                test.assertTrue(MonsterRoles.nativeArrowShooter(arrow, null).getType() == EntityTypes.STRAY,
                        "An arrow outliving its sortie is still credited to the stray that loosed it");
                arrow.discard();

                f.owner.hurtServer(f.level, f.level.damageSources().playerAttack(human), 1000);
                message = f.death(0);
                test.assertValueEqual(key(message), "death.attack.player", "A human's kill keeps the vanilla player sentence");
                test.assertValueEqual(arg(message, 0).getString(), skeleton, "The monster victim is named with its form");
                test.assertValueEqual(arg(message, 1).getString(), human.getName().getString(), "The human killer keeps its name");
            }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void aDetonatingCreeperBlowsUpInsteadOfBeingKilled(GameTestHelper test) {
        var f = new Fixture(test, MonsterRole.CREEPER, 2);
        TestTeardown.atEnd(test, f::close);
        test.runAfterDelay(80, () -> {
            try (var ignored = f.installCombat()) {
                MonsterRoles.select(f.owner, "creeper");
                var creeper = monster(EntityTypes.CREEPER, f.owner);
                var victim = f.players.get(1);
                victim.teleportTo(f.center.x, f.center.y, f.center.z + 2); victim.setHealth(1);
                MonsterCreeper.use(f.owner, InteractionHand.MAIN_HAND);
                for (int tick = 0; tick < 30 && f.owner.isAlive(); tick++) MonsterCreeper.tick(f.owner);
                test.assertFalse(f.owner.isAlive() || victim.isAlive(), "The blast ends the body and the nearby victim");
                var own = f.death(0);
                test.assertValueEqual(key(own), "death.attack.explosion", "The body blew up, as vanilla words a creeper");
                test.assertValueEqual(arg(own, 0).getString(), creeper, "The creeper is named with its player");
                var blasted = f.death(1);
                test.assertValueEqual(key(blasted), "death.attack.explosion.player", "The victim keeps vanilla's blast sentence");
                test.assertValueEqual(arg(blasted, 1).getString(), creeper, "The blast names the player behind the creeper");
            }
            test.succeed();
        });
    }

    /** docs/plan/女巫药水与药水盲盒.md §5–6: what a monster's potions leave where it fell is its own doing, and named so. */
    @GameTest(maxTicks = 200)
    public void whatAMonstersPotionsLeaveBehindIsItsOwnDoing(GameTestHelper test) {
        var f = new Fixture(test, MonsterRole.ZOMBIE, 3);
        TestTeardown.atEnd(test, f::close);
        test.runAfterDelay(80, () -> {
            try (var ignored = f.installCombat()) {
                MonsterRoles.select(f.owner, "zombie");
                var zombie = monster(EntityTypes.ZOMBIE, f.owner);
                var thrown = f.players.get(1);
                thrown.teleportTo(f.center.x + 2, f.center.y, f.center.z);
                // Within the gust's reach but behind a wall: vanilla never throws it, so it is never the monster's.
                var sheltered = f.players.get(2);
                for (int dz = -8; dz <= 8; dz++) for (int y = 0; y < 5; y++)
                    f.level.setBlockAndUpdate(BlockPos.containing(f.center).offset(4, y, dz), Blocks.STONE.defaultBlockState());
                sheltered.teleportTo(f.center.x + 5.5, f.center.y, f.center.z);
                for (var effect : List.of(net.minecraft.world.effect.MobEffects.OOZING, net.minecraft.world.effect.MobEffects.WEAVING,
                        net.minecraft.world.effect.MobEffects.WIND_CHARGED))
                    f.owner.addEffect(new net.minecraft.world.effect.MobEffectInstance(effect, 600));
                f.owner.hurtServer(f.level, f.level.damageSources().playerAttack(f.players.get(2)), 1000);
                test.assertFalse(f.owner.isAlive(), "The monster falls");
                var slimes = f.level.getEntitiesOfClass(net.minecraft.world.entity.monster.cubemob.Slime.class,
                        f.owner.getBoundingBox().inflate(4));
                test.assertValueEqual(slimes.size(), 2, "Oozing leaves its two native slimes");
                test.assertTrue(slimes.stream().allMatch(slime -> f.owner.getUUID().equals(dev.hardrush.monster.MonsterLegacy.owner(slime))),
                        "The slimes act for the monster that left them");
                boolean webs = false;
                for (var pos : BlockPos.betweenClosed(BlockPos.containing(f.center).offset(-2, -1, -2), BlockPos.containing(f.center).offset(2, 2, 2)))
                    webs |= f.level.getBlockState(pos).is(Blocks.COBWEB);
                test.assertTrue(webs, "Weaving leaves its cobwebs where the monster fell");
                test.assertFalse(f.owner.hasEffect(net.minecraft.world.effect.MobEffects.OOZING), "Death effects happen once");

                test.assertTrue(CombatRuntime.peekKillCredit(f.level, sheltered, f.level.damageSources().fall()).isEmpty(),
                        "A human the gust never moved owes it nothing");
                test.assertTrue(CombatRuntime.peekKillCredit(f.level, thrown, f.level.damageSources().fall()).isPresent(),
                        "The human it threw would be its kill");
                thrown.fallDistance = 20;
                thrown.hurtServer(f.level, f.level.damageSources().fall(), 1000);
                var message = f.death(1);
                test.assertValueEqual(key(message), "death.fell.assist", "A fall after the gust keeps vanilla's assisted wording");
                test.assertValueEqual(arg(message, 1).getString(), zombie, "The gust is the monster's push");

                var slain = f.players.get(2);
                slain.hurtServer(f.level, f.level.damageSources().mobAttack(slimes.getFirst()), 1000);
                message = f.death(2);
                test.assertValueEqual(key(message), "death.attack.mob", "The vanilla mob sentence is kept");
                test.assertValueEqual(arg(message, 1).getString(), f.owner.getName().getString() + "留下的"
                        + EntityTypes.SLIME.getDescription().getString(), "A slime of oozing is named after its monster");

                // The owner deploys again: what its last body left no longer counts for it, nor bears its name.
                var slime = slimes.getLast();
                var origin = CombatRuntime.originOf(slime);
                test.assertTrue(dev.hardrush.monster.MonsterLegacy.standing(f.level.getServer(), origin).isPresent(), "Still the owner's");
                var p = f.participants.get(f.owner.getUUID());
                f.participants.put(p.id(), new Participant(p.id(), p.name(), p.role(), true, false, 0, 0, 1, false, p.monsterRole(),
                        MonsterDimension.OVERWORLD, 0, p.monsterDeploymentSequence() + 1));
                test.assertTrue(dev.hardrush.monster.MonsterLegacy.standing(f.level.getServer(), origin).isEmpty(),
                        "A slime left by an earlier sortie lapses once its owner deploys again");
                test.assertTrue(dev.hardrush.monster.MonsterLegacy.standingOwner(slime) == null, "It is a plain slime again");
            }
            test.succeed();
        });
    }

    /** A poisoned creeper's blast leaves the poison in its cloud; every later tick of it is the creeper's, and it is told. */
    @GameTest(maxTicks = 300)
    public void aCreepersPoisonCloudKeepsHurtingOnItsBehalf(GameTestHelper test) {
        var f = new Fixture(test, MonsterRole.CREEPER, 2);
        TestTeardown.atEnd(test, f::close);
        var combat = new MonsterCombatRuntime.Scope[1];
        test.runAfterDelay(80, () -> {
            combat[0] = f.installCombat();
            MonsterRoles.select(f.owner, "creeper");
            f.owner.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON, 900));
            var victim = f.players.get(1);
            victim.teleportTo(f.center.x, f.center.y, f.center.z + 1.5);
            MonsterCreeper.use(f.owner, InteractionHand.MAIN_HAND);
            for (int tick = 0; tick < 30 && f.owner.isAlive(); tick++) MonsterCreeper.tick(f.owner);
            test.assertFalse(f.owner.isAlive(), "The creeper blew up");
        });
        test.runAfterDelay(160, () -> {
            try {
                var victim = f.players.get(1);
                test.assertTrue(victim.hasEffect(net.minecraft.world.effect.MobEffects.POISON), "The cloud hands the creeper's poison on");
                var told = f.clients.get(0).packets(net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket.class).stream()
                        .map(packet -> packet.text().getString()).toList();
                test.assertTrue(told.contains("你留下的药水云伤到了 " + victim.getName().getString()),
                        "A poison tick of the cloud is the creeper's hit, and the creeper is told: " + told);
            } finally { if (combat[0] != null) combat[0].close(); }
            test.succeed();
        });
    }

    private static String monster(EntityType<?> type, ServerPlayer owner) {
        return "化身" + type.getDescription().getString() + "的" + owner.getName().getString();
    }
    private static String key(Component message) { return ((TranslatableContents) message.getContents()).getKey(); }
    private static Component arg(Component message, int index) {
        return (Component) ((TranslatableContents) message.getContents()).getArgs()[index];
    }

    private static final class Fixture {
        final ServerLevel level;
        final Vec3 center;
        final List<ServerPlayer> players = new ArrayList<>();
        final List<TestClient> clients = new ArrayList<>();
        final List<ChunkPos> forced = new ArrayList<>();
        final Map<UUID, Participant> participants = new HashMap<>();
        final ServerPlayer owner;
        final UUID round = UUID.randomUUID();

        Fixture(GameTestHelper test, MonsterRole role, int count) {
            // Farther apart than any fixture's floor and closing sweep reach (20 + 24 per player, 140 for five), so one
            // test ending never discards another's entities, such as a lingering cloud still handing out its poison.
            level = test.getLevel(); center = new Vec3(70000 + ARENAS.getAndIncrement() * ARENA_SPACING + .5, 150, .5);
            var root = new ChunkPos((int) Math.floor(center.x) >> 4, (int) Math.floor(center.z) >> 4);
            for (int dx = -2; dx <= 6; dx++) for (int dz = -2; dz <= 2; dz++) {
                var chunk = new ChunkPos(root.x() + dx, root.z() + dz);
                level.setChunkForced(chunk.x(), chunk.z(), true); forced.add(chunk);
            }
            for (int dx = -10; dx <= count * 24; dx++) for (int dz = -10; dz <= 10; dz++) {
                var pos = BlockPos.containing(center).offset(dx, 0, dz);
                level.setBlockAndUpdate(pos.below(), Blocks.SMOOTH_STONE.defaultBlockState());
                for (int y = 0; y < 5; y++) level.setBlockAndUpdate(pos.above(y), Blocks.AIR.defaultBlockState());
            }
            for (int index = 0; index < count; index++) {
                var profile = new GameProfile(UUID.randomUUID(), "HRDeath" + ARENAS.get() + index);
                var client = new TestClient(level.getServer(), profile); clients.add(client);
                var player = client.place(level);
                player.teleportTo(center.x + index * 24, center.y, center.z);
                player.setGameMode(GameType.SURVIVAL); client.acknowledge();
                if (index > 0) { player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); player.setHealth(100); }
                players.add(player);
                participants.put(player.getUUID(), new Participant(player.getUUID(), profile.name(),
                        index == 0 ? ParticipantRole.MONSTER_DEPLOYED : ParticipantRole.HUMAN_ACTIVE,
                        true, false, 0, 0, 1, false, role, MonsterDimension.OVERWORLD, 0, 1));
            }
            owner = players.getFirst();
        }

        MonsterCombatRuntime.Scope installCombat() {
            var combat = RuntimeFixtures.monsterCombat(level.getServer(), new MonsterCombatRuntime.Access() {
                public Optional<RoundSnapshot> snapshot() { return Optional.of(new RoundSnapshot(round, 1, 42, 0, RulesConfig.defaults(),
                        RoundPhase.RUNNING, PauseReason.NONE, 0, 0, participants, false, false, null)); }
                public boolean competitionLocked() { return false; }
                public boolean formalLevel(ServerLevel candidate) { return candidate == level; }
            });
            var evidence = RuntimeFixtures.combat(level.getServer(), new CombatRuntime.RoundAccess() {
                public Optional<UUID> roundOf(ServerLevel candidate) { return candidate == level ? Optional.of(round) : Optional.empty(); }
                public Optional<CombatActor> actor(UUID roundId, UUID id) { return Optional.ofNullable(participants.get(id))
                        .map(p -> new CombatActor(p.id(), p.role(), p.lifeSequence(), p.monsterDeploymentSequence())); }
            }, event -> {});
            return () -> { evidence.close(); combat.close(); };
        }

        /** A melee credit of the deployed owner in this round. */
        dev.hardrush.core.combat.CombatOrigin monsterHit() {
            var p = participants.get(owner.getUUID());
            return new dev.hardrush.core.combat.CombatOrigin(round, UUID.randomUUID(),
                    new CombatActor(p.id(), p.role(), p.lifeSequence(), p.monsterDeploymentSequence()),
                    dev.hardrush.core.api.DamagePort.AttackKind.MELEE, Optional.empty(), List.of());
        }

        /** The death message the native death screen of {@code index} received last. */
        Component death(int index) {
            var deaths = clients.get(index).packets(ClientboundPlayerCombatKillPacket.class);
            if (deaths.isEmpty()) throw new IllegalStateException("No death message for player " + index);
            return deaths.getLast().message();
        }

        List<String> chat(int index) {
            return clients.get(index).packets(ClientboundSystemChatPacket.class).stream()
                    .filter(packet -> !packet.overlay()).map(packet -> packet.content().getString()).toList();
        }

        void close() {
            level.getEntities((Entity) null, owner.getBoundingBox().inflate(20 + players.size() * 24),
                    entity -> !(entity instanceof ServerPlayer)).forEach(Entity::discard);
            for (var client : clients) { var player = client.player(); if (player != null) MonsterRoles.clear(player); client.close(); }
            for (var chunk : forced) level.setChunkForced(chunk.x(), chunk.z(), false);
        }
    }
}
