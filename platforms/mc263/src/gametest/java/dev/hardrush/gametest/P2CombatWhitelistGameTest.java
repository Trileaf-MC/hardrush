package dev.hardrush.gametest;

import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.p0.monster.MonsterRoles;
import dev.hardrush.p1.combat.P1CombatRuntime;
import dev.hardrush.p2.combat.P2CombatRuntime;
import dev.hardrush.p2.protection.P2ProtectionRuntime;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** P2-08 native server hooks; no client, screenshots or computer use. */
public final class P2CombatWhitelistGameTest {
    @GameTest(maxTicks=200)
    public void nativeMonsterWhitelistRejectsIllegalTargetsBeforeEverySideEffect(GameTestHelper test) {
        try (var f = new Fixture(test); var ignored = f.install()) {
            var model = f.model();
            var cow = EntityTypes.COW.create(f.level, EntitySpawnReason.COMMAND);
            cow.setPosRaw(f.center.x + 4, f.center.y, f.center.z);
            float before = cow.getHealth();
            test.assertTrue(!model.doHurtTarget(f.level, cow), "Native zombie attack rejects a nonhuman before damage or enchantment effects");
            test.assertTrue(!cow.hurtServer(f.level, f.level.damageSources().mobAttack(model), 8), "Direct native LivingEntity damage cannot bypass the whitelist");
            test.assertValueEqual(cow.getHealth(), before, "Illegal mob health is unchanged");
            test.assertTrue(!cow.addEffect(new MobEffectInstance(MobEffects.POISON, 200), model), "Explicit harmful effect admission uses the same source policy");
            cow.forceAddEffect(new MobEffectInstance(MobEffects.WITHER, 200), model);
            test.assertTrue(!cow.hasEffect(MobEffects.WITHER), "Forced effects cannot bypass native admission");
            cow.setDeltaMovement(Vec3.ZERO);
            cow.knockback(3, 1, 0, f.level.damageSources().mobAttack(model), 1, false);
            try (var action = P2CombatRuntime.action(model)) {
                cow.setRemainingFireTicks(200);
                cow.push(1, 2, 3);
                cow.pushFromExplosion(new Vec3(1, 2, 3));
            }
            test.assertTrue(cow.getRemainingFireTicks() <= 0, "Source-scoped ignition is denied before native fire state changes");
            test.assertValueEqual(cow.getDeltaMovement(), Vec3.ZERO, "Knockback, push and explosion impulse cannot move illegal targets");
            var blast = new ServerExplosion(f.level, model, null, null, f.center, 4, false, Explosion.BlockInteraction.KEEP);
            test.assertTrue(cow.ignoreExplosion(blast), "Native explosion skips illegal targets before damage and impulse");
            var crystal = EntityTypes.END_CRYSTAL.create(f.level, EntitySpawnReason.COMMAND);
            crystal.setPosRaw(f.center.x + 4, f.center.y, f.center.z);
            test.assertTrue(!crystal.hurtServer(f.level, f.level.damageSources().mobAttack(model), 2), "Nonliving crystal hook rejects monster damage");
            test.assertTrue(!crystal.isRemoved(), "Rejected crystal damage cannot trigger a chain explosion");
            var dragon = EntityTypes.ENDER_DRAGON.create(f.level, EntitySpawnReason.COMMAND);
            dragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);
            test.assertTrue(!dragon.hurt(f.level, dragon.head, f.level.damageSources().mobAttack(model), 30), "Native dragon-part hook denies monsters before phase or health changes");
            test.assertValueEqual(dragon.getHealth(), dragon.getMaxHealth(), "Dragon health is unchanged");
            f.owner.attack(f.human);
            test.assertValueEqual(f.human.getHealth(), 20f, "Formal monster player cannot use ordinary player attack to bypass the model");
            model.discard(); cow.discard(); crystal.discard(); dragon.discard();
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void flamingArrowRejectsMobsCrystalsProtectedAndStaleHumansBeforeNativeImpact(GameTestHelper test) {
        try (var f = new Fixture(test); var ignored = f.install()) {
            var cow = EntityTypes.COW.create(f.level, EntitySpawnReason.COMMAND);
            var crystal = EntityTypes.END_CRYSTAL.create(f.level, EntitySpawnReason.COMMAND);
            cow.setPosRaw(f.center.x + 4, f.center.y, f.center.z);
            crystal.setPosRaw(f.center.x + 4, f.center.y, f.center.z);
            f.impact(f.arrow(), cow);
            f.impact(f.arrow(), crystal);
            test.assertValueEqual(cow.getHealth(), cow.getMaxHealth(), "Flaming arrow cannot damage an illegal mob");
            test.assertTrue(cow.getRemainingFireTicks() <= 0 && !cow.hasEffect(MobEffects.POISON) && !crystal.isRemoved(), "Rejected impact does not ignite or poison mobs or remove crystals");
            var protectedArrow = f.arrow();
            f.protectedTarget = true;
            f.impact(protectedArrow, f.human);
            f.protectedTarget = false;
            var oldArrow = f.arrow();
            f.round = UUID.randomUUID(); // Owner UUID remains identical; the attack's round does not.
            f.impact(oldArrow, f.human);
            test.assertValueEqual(f.human.getHealth(), 20f, "Protected and stale-round humans receive no arrow damage");
            test.assertTrue(f.human.getRemainingFireTicks() <= 0, "Flaming-arrow ignition is rejected before hurtServer");
            test.assertValueEqual(f.human.getArrowCount(), 0, "Rejected impact cannot add an embedded arrow");
            test.assertValueEqual(f.human.getDeltaMovement(), Vec3.ZERO, "Rejected impact cannot add native arrow knockback");
            cow.discard(); crystal.discard();
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void frozenOwnerRoundAndAttributionSurviveArrowSaveLoadAndOwnerIdentityChanges(GameTestHelper test) {
        try (var f = new Fixture(test); var ignored = f.install()) {
            var arrow = f.arrow();
            var identity = P2CombatRuntime.source(arrow).orElseThrow();
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, f.level.registryAccess());
            arrow.saveWithoutId(output);
            var reloaded = new TestArrow(f.level, f.owner);
            reloaded.load(TagValueInput.create(ProblemReporter.DISCARDING, f.level.registryAccess(), output.buildResult()));
            test.assertValueEqual(P2CombatRuntime.source(reloaded).orElseThrow(), identity, "Persisted arrow retains the exact owner, round, life and deployment");
            f.participants.put(f.owner.getUUID(), f.participants.get(f.owner.getUUID()).withRole(ParticipantRole.HUMAN_ACTIVE).withSession("returned", false));
            var replacementOwner = f.player(8, ParticipantRole.HUMAN_ACTIVE);
            reloaded.setOwner(replacementOwner);
            P2CombatRuntime.stampSource(reloaded, replacementOwner);
            test.assertValueEqual(P2CombatRuntime.source(reloaded).orElseThrow(), identity, "Reassigning owner cannot replace frozen identity");
            test.assertTrue(!P2CombatRuntime.reject(reloaded, f.human), "Same-round old arrow remains valid after the original owner logs out or revives");
            f.impact(reloaded, f.human);
            test.assertTrue(f.human.getHealth() < 20 && f.human.getRemainingFireTicks() > 0, "Legal native flaming arrow preserves actual damage and fire");
            test.assertTrue(f.human.hasEffect(MobEffects.POISON), "Legal native tipped arrow preserves potion effects after owner changes");
            var humanArrow = new TestArrow(f.level, replacementOwner);
            test.assertValueEqual(P1CombatRuntime.originOf(humanArrow).orElseThrow().actor().identity(), ParticipantRole.HUMAN_ACTIVE,
                    "Second arrow captures a real human launch before its owner changes identity");
            humanArrow.setPosRaw(replacementOwner.getX(), replacementOwner.getY(), replacementOwner.getZ());
            humanArrow.setDeltaMovement(1, 0, 0); humanArrow.setBaseDamage(4);
            f.participants.put(replacementOwner.getUUID(), f.participants.get(replacementOwner.getUUID()).withRole(ParticipantRole.MONSTER_DEPLOYED));
            try {
                MonsterRoles.select(replacementOwner, "zombie");
                test.assertTrue(MonsterRoles.isMonster(replacementOwner), "The old human arrow owner has an actual native monster body");
                f.human.setHealth(20); f.human.clearFire(); f.human.setInvulnerableTime(0); f.human.damageCooldownTime = 0;
                f.human.removeAllEffects();
                f.impact(humanArrow, f.human);
                test.assertTrue(f.human.getHealth() < 20,
                        "Frozen human arrow applies native damage after its owner's later monster identity: health=" + f.human.getHealth()
                        + ", damageCooldown=" + f.human.damageCooldownTime + ", source=" + P1CombatRuntime.originOf(humanArrow));
                test.assertTrue(f.human.hasEffect(MobEffects.POISON),
                        "Frozen human arrow applies native potion effects after its owner's later monster identity: health=" + f.human.getHealth());
            } finally { MonsterRoles.clear(replacementOwner); }
            f.round = UUID.randomUUID();
            test.assertTrue(P2CombatRuntime.reject(reloaded, f.human), "Same UUID in a new round cannot revive an old arrow's authority");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void nativeModelAndSummonKeepFrozenOwnerCombatEvidence(GameTestHelper test) {
        try (var f = new Fixture(test); var ignored = f.install()) {
            var model = f.model();
            var skeleton = EntityTypes.SKELETON.create(f.level, EntitySpawnReason.COMMAND);
            skeleton.setPosRaw(f.owner.getX(), f.owner.getY(), f.owner.getZ());
            P2CombatRuntime.stampSource(skeleton, f.owner);
            var nativeArrow = ProjectileUtil.getMobArrow(skeleton, new ItemStack(Items.ARROW), 1, new ItemStack(Items.BOW));
            var nativeOrigin = P1CombatRuntime.originOf(nativeArrow).orElseThrow();
            test.assertValueEqual(nativeOrigin.actor().identity(), ParticipantRole.MONSTER_DEPLOYED,
                    "Real ProjectileUtil mob-arrow constructor freezes the model's monster identity on its first owner assignment");
            test.assertValueEqual(nativeOrigin.actor().playerId(), f.owner.getUUID(),
                    "Real mob-arrow constructor retains the monster player owner rather than the native model UUID");
            nativeArrow.setOwner(f.owner);
            test.assertValueEqual(P1CombatRuntime.originOf(nativeArrow).orElseThrow(), nativeOrigin,
                    "Replacing native collision owner with the player cannot replace the sealed arrow provenance");
            nativeArrow.discard(); skeleton.discard();
            try (var action = P2CombatRuntime.action(model)) {
                test.assertTrue(model.hurtServer(f.level, f.level.damageSources().thorns(f.human), 2),
                        "A legal human's native thorns damage is preserved inside a monster attack scope");
            }
            test.assertTrue(model.doHurtTarget(f.level, f.human), "Stamped native monster melee still reaches a legal current-round human");
            test.assertTrue(f.human.getHealth() < 20, "Valid melee applies native damage");
            f.human.setInvulnerableTime(0);
            test.assertTrue(f.human.hurtServer(f.level, f.level.damageSources().mobAttack(model), 100), "Lethal native model damage is accepted");
            var lethal = f.events.stream().filter(CombatEvent::lethal).findFirst().orElseThrow();
            var origin = lethal.origin().orElseThrow();
            test.assertValueEqual(origin.actor().playerId(), f.owner.getUUID(), "Native summon/model attribution uses owner UUID rather than child UUID");
            test.assertValueEqual(origin.actor().identity(), ParticipantRole.MONSTER_DEPLOYED, "Combat evidence freezes the deployed monster identity");
            test.assertValueEqual(origin.roundId(), f.round, "Combat evidence retains the exact creation round");
            test.assertValueEqual(origin.actor().deploymentSequence(), 1L, "Combat evidence retains the exact sortie");
            model.discard();
        }
        test.succeed();
    }

    private static final class TestArrow extends Arrow {
        TestArrow(ServerLevel level, ServerPlayer owner) {
            super(level, owner, PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.POISON), new ItemStack(Items.BOW));
        }
        void impact(Entity target) { hitTargetOrDeflectSelf(new EntityHitResult(target)); }
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper test;
        final ServerLevel level;
        final Vec3 center;
        final Map<UUID, Participant> participants = new HashMap<>();
        final List<ServerPlayer> players = new ArrayList<>();
        final List<CombatEvent> events = new ArrayList<>();
        final ServerPlayer owner;
        final ServerPlayer human;
        UUID round = UUID.randomUUID();
        boolean protectedTarget;
        Fixture(GameTestHelper test) {
            this.test = test; level = test.getLevel(); center = test.absoluteVec(new Vec3(4, 4, 4));
            owner = player(0, ParticipantRole.MONSTER_DEPLOYED);
            human = player(3, ParticipantRole.HUMAN_ACTIVE);
            human.setDeltaMovement(Vec3.ZERO);
        }
        ServerPlayer player(int x, ParticipantRole role) {
            var player = test.makeMockServerPlayerInLevel(); players.add(player);
            player.setGameMode(GameType.SURVIVAL); player.getAbilities().invulnerable = false; player.setHealth(20);
            player.setPosRaw(center.x + x, center.y, center.z);
            player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
            participants.put(player.getUUID(), new Participant(player.getUUID(), player.getScoreboardName(), role,
                    true, false, 0, 0, 1, false, true, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 1));
            return player;
        }
        RoundSnapshot snapshot() {
            return new RoundSnapshot(round, 1, 42, 0, RulesConfig.defaults(), RoundPhase.RUNNING,
                    PauseReason.NONE, 0, 0, participants, false, false, null);
        }
        P2CombatRuntime.Scope install() {
            var combat = P2CombatRuntime.temporaryConfiguration(level.getServer(), new P2CombatRuntime.Access() {
                public Optional<RoundSnapshot> snapshot() { return Optional.of(Fixture.this.snapshot()); }
                public boolean competitionLocked() { return false; }
                public boolean formalLevel(ServerLevel candidate) { return candidate == level; }
            });
            var protection = P2ProtectionRuntime.temporaryConfiguration(level.getServer(), new P2ProtectionRuntime.Access() {
                public Optional<P2ProtectionRuntime.Zone> zone(ServerLevel candidate) {
                    return candidate == level && protectedTarget ? Optional.of(new P2ProtectionRuntime.Zone(round, human.position(), 1, false)) : Optional.empty();
                }
                public Optional<Participant> participant(UUID id) { return Optional.ofNullable(participants.get(id)); }
                public boolean leaveProtection(UUID roundId, UUID id, int life) { return false; }
            });
            var evidence = P1CombatRuntime.temporaryConfiguration(level.getServer(), new P1CombatRuntime.RoundAccess() {
                public Optional<UUID> roundOf(ServerLevel candidate) { return candidate == level ? Optional.of(round) : Optional.empty(); }
                public Optional<CombatActor> actor(UUID roundId, UUID id) {
                    return Optional.ofNullable(participants.get(id)).map(p -> new CombatActor(p.id(), p.role(), p.lifeSequence(), p.monsterDeploymentSequence()));
                }
            }, events::add);
            return () -> { evidence.close(); protection.close(); combat.close(); };
        }
        Zombie model() {
            var model = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            model.setPosRaw(owner.getX(), owner.getY(), owner.getZ());
            P2CombatRuntime.stampSource(model, owner);
            test.assertTrue(P2CombatRuntime.source(model).orElseThrow().admitted(), "Fixture native model is a committed formal deployment");
            return model;
        }
        TestArrow arrow() {
            var arrow = new TestArrow(level, owner);
            arrow.setPosRaw(owner.getX(), owner.getY(), owner.getZ());
            arrow.setDeltaMovement(1, 0, 0); arrow.setBaseDamage(4); arrow.setRemainingFireTicks(200);
            P2CombatRuntime.stampSource(arrow, owner);
            return arrow;
        }
        void impact(TestArrow arrow, Entity target) { arrow.impact(target); arrow.discard(); }
        @Override public void close() { for (var player : players) level.getServer().getPlayerList().remove(player); }
    }
}
