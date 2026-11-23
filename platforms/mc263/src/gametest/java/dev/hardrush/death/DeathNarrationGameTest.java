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
            level = test.getLevel(); center = new Vec3(70000 + ARENAS.getAndIncrement() * 128 + .5, 150, .5);
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
