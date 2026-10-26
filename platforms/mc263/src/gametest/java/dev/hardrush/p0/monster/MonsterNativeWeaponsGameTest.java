package dev.hardrush.p0.monster;

import dev.hardrush.p0.monster.mixin.MonsterWeaponEntityAccessor;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.gametest.RuntimeFixtures;
import dev.hardrush.p1.combat.P1CombatRuntime;
import dev.hardrush.p2.combat.P2CombatRuntime;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.EntityHitResult;
import static dev.hardrush.gametest.MonsterRoleGameTest.actor;
import static dev.hardrush.gametest.MonsterRoleGameTest.remove;

public final class MonsterNativeWeaponsGameTest {
    @GameTest
    public void infiniteTridentCopiesRetainDamageEnchantmentsWithoutReturningGear(GameTestHelper test) {
        var registry = test.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var loyalty = registry.getOrThrow(Enchantments.LOYALTY);
        var impaling = registry.getOrThrow(Enchantments.IMPALING);
        var original = new ItemStack(Items.TRIDENT);
        original.enchant(loyalty, 3);
        original.enchant(impaling, 5);
        original.setDamageValue(17);
        var copy = MonsterNativeWeapons.copyForProjectile(original);
        test.assertValueEqual(copy.getEnchantments().getLevel(loyalty), 0, "A copied projectile cannot return an extra trident");
        test.assertValueEqual(copy.getEnchantments().getLevel(impaling), 5, "Native damaging enchantments remain on the projectile");
        test.assertValueEqual(original.getEnchantments().getLevel(loyalty), 3, "The real equipment retains its enchantments");
        copy.setDamageValue(99);
        copy.shrink(1);
        test.assertValueEqual(original.getCount(), 1, "A projectile never consumes the real trident");
        test.assertValueEqual(original.getDamageValue(), 17, "Projectile changes cannot alter real equipment durability");
        test.succeed();
    }

    @GameTest
    public void nativeWeaponMotionUsesObservedSpeedAndMobReach(GameTestHelper test) {
        var model = EntityTypes.ZOMBIE.create(test.getLevel(), EntitySpawnReason.COMMAND);
        var spear = new ItemStack(Items.IRON_SPEAR);
        ((MonsterWeaponEntityAccessor) model).hardrush$setKnownWeaponSpeed(new Vec3(0, 0, 0.1));
        test.assertTrue(KineticWeapon.getMotion(model).closerThan(new Vec3(0, 0, 2), 0.000001),
                "Native kinetic attacks see real observed blocks per second");
        test.assertValueEqual(model.getAttackRangeWith(spear).effectiveMinRange(model), 1F, "Native mob minimum spear reach");
        test.assertValueEqual(model.getAttackRangeWith(spear).effectiveMaxRange(model), 2.25F, "Native mob maximum spear reach");
        test.succeed();
    }

    @GameTest(maxTicks = 200)
    public void movedSpearUsesNativeStabCooldownAndKineticMovement(GameTestHelper test) {
        var player = actor(test);
        var target = EntityTypes.IRON_GOLEM.create(player.level(), EntitySpawnReason.COMMAND);
        target.setNoAi(true); target.setNoGravity(true);
        target.snapTo(player.position().add(0, 0, 2)); player.level().addFreshEntity(target);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(player, "zombie");
                player.getInventory().setItem(4, new ItemStack(Items.IRON_SPEAR));
                player.getInventory().setSelectedSlot(4); player.setYRot(0); player.setXRot(0);
                float health = player.getHealth(), before = target.getHealth();
                player.getMainHandItem().get(net.minecraft.core.component.DataComponents.PIERCING_WEAPON)
                        .attack(player, EquipmentSlot.MAINHAND);
                test.assertTrue(target.getHealth() < before, "The vanilla STAB entry point routes a moved spear through the mob");
                float after = target.getHealth();
                test.assertFalse(MonsterNativeWeapons.stabbing(player), "The native stab animation interval prevents same-tick spam");
                test.assertValueEqual(target.getHealth(), after, "Rejected stabbing cannot deal extra damage");
                test.assertValueEqual(player.getHealth(), health, "The unregistered native body cannot pierce its own player");
                target.snapTo(player.position().add(0, 0, 5)); target.damageCooldownTime = 0;
                test.assertTrue(MonsterNativeWeapons.startUse(player, InteractionHand.MAIN_HAND), "A spear in slot four can charge");
                test.runAfterDelay(13, () -> {
                    try {
                        target.snapTo(player.position().add(0, 0, 2)); target.setDeltaMovement(Vec3.ZERO);
                        player.setKnownMovement(new Vec3(0, 0, .1));
                        MonsterNativeWeapons.tick(player);
                        test.assertTrue(target.getHealth() < after, "Right-hand kinetic damage uses native movement thresholds");
                        test.assertValueEqual(player.getHealth(), health, "Kinetic piercing also excludes the owning player");
                        player.releaseUsingItem();
                        test.assertFalse(MonsterNativeWeapons.using(player), "Release stops native use");
                    } finally { target.discard(); remove(player); }
                    test.succeed();
                });
            } catch (Throwable error) { target.discard(); remove(player); throw error; }
        });
    }

    @GameTest(maxTicks = 220)
    public void drownedOffhandTridentRequiresChargeKeepsGearAndCooldown(GameTestHelper test) {
        var player = actor(test);
        test.runAfterDelay(80, () -> {
            MonsterRoles.select(player, "zombie"); MonsterRoles.convert(player, MonsterForm.DROWNED);
            var trident = new ItemStack(Items.TRIDENT); trident.setDamageValue(17);
            player.setItemSlot(EquipmentSlot.OFFHAND, trident); player.setYRot(0); player.setXRot(-30);
            test.assertTrue(MonsterNativeWeapons.startUse(player, InteractionHand.OFF_HAND), "A drowned can use its real offhand trident");
            test.runAfterDelay(5, () -> {
                player.releaseUsingItem();
                test.assertTrue(tridents(player).isEmpty(), "A release before ten ticks cannot throw");
                test.assertTrue(MonsterNativeWeapons.startUse(player, InteractionHand.OFF_HAND), "An aborted throw has no cooldown");
                test.runAfterDelay(11, () -> {
                    try {
                        player.releaseUsingItem();
                        var shots = tridents(player);
                        test.assertValueEqual(shots.size(), 1, "A native release produces exactly one trident");
                        var shot = shots.getFirst();
                        test.assertTrue(shot.getOwner() == player, "Collision exclusion uses the actual player");
                        test.assertValueEqual(shot.pickup, AbstractArrow.Pickup.DISALLOWED, "Infinite projectiles cannot be picked up");
                        test.assertTrue(MonsterNativeWeapons.tridentShooter(shot, player) == MonsterRoles.nativeModel(player),
                                "A current-body trident keeps its exact native source for retaliation");
                        test.assertValueEqual(player.getOffhandItem().getCount(), 1, "Throwing never consumes the real trident");
                        test.assertValueEqual(player.getOffhandItem().getDamageValue(), 17, "Throwing never damages the real trident");
                        test.assertFalse(MonsterNativeWeapons.startUse(player, InteractionHand.OFF_HAND), "The native forty-tick attack interval applies");
                        shots.forEach(Entity::discard);
                        test.runAfterDelay(40, () -> {
                            try { test.assertTrue(MonsterNativeWeapons.startUse(player, InteractionHand.OFF_HAND), "The trident is available after forty ticks"); }
                            finally { tridents(player).forEach(Entity::discard); remove(player); }
                            test.succeed();
                        });
                    } catch (Throwable error) { tridents(player).forEach(Entity::discard); remove(player); throw error; }
                });
            });
        });
    }

    @GameTest(maxTicks = 200)
    public void tridentOverrideRejectsOwnImpactBeforeNativeSideEffects(GameTestHelper test) {
        var player = actor(test);
        test.runAfterDelay(80, () -> {
            var shot = new ImpactTrident(player.level(), player);
            try {
                MonsterRoles.select(player, "zombie");
                shot.entityTags().add("hardrush.trident-owner=" + player.getUUID());
                shot.entityTags().add("hardrush.shot-owner=" + player.getUUID());
                shot.snapTo(player.position()); player.level().addFreshEntity(shot);
                float before = player.getHealth();
                shot.hit(player);
                test.assertTrue(shot.isRemoved(), "ThrownTrident's own impact override is guarded before it applies side effects");
                test.assertValueEqual(player.getHealth(), before, "An owner cannot receive damage from a copied trident");
            } finally { shot.discard(); remove(player); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void onePiercingAttackKeepsBothLethalVictimsInOneCombatBatch(GameTestHelper test) {
        var owner = actor(test); var first = actor(test); var second = actor(test);
        var level = owner.level(); var round = UUID.randomUUID();
        var participants = new HashMap<UUID, Participant>();
        for (var player : List.of(owner, first, second)) participants.put(player.getUUID(), new Participant(player.getUUID(),
                player.getGameProfile().name(), player == owner ? ParticipantRole.MONSTER_DEPLOYED : ParticipantRole.HUMAN_ACTIVE,
                true, false, 0, 0, 1, false, true, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 1));
        var events = new ArrayList<CombatEvent>();
        var sealedBatch = new UUID[1];
        test.runAfterDelay(80, () -> {
            try (var combat = RuntimeFixtures.monsterCombat(level.getServer(), new P2CombatRuntime.Access() {
                     public Optional<RoundSnapshot> snapshot() { return Optional.of(new RoundSnapshot(round, 1, 42, 0, RulesConfig.defaults(),
                             RoundPhase.RUNNING, PauseReason.NONE, 0, 0, participants, false, false, null)); }
                     public boolean competitionLocked() { return false; }
                     public boolean formalLevel(net.minecraft.server.level.ServerLevel candidate) { return candidate == level; }
                 });
                 var evidence = RuntimeFixtures.combat(level.getServer(), new P1CombatRuntime.RoundAccess() {
                     public Optional<UUID> roundOf(net.minecraft.server.level.ServerLevel candidate) { return candidate == level ? Optional.of(round) : Optional.empty(); }
                     public Optional<CombatActor> actor(UUID id, UUID player) { return Optional.ofNullable(participants.get(player))
                             .map(p -> new CombatActor(p.id(), p.role(), p.lifeSequence(), p.monsterDeploymentSequence())); }
                     public boolean mayDamage(net.minecraft.server.level.ServerLevel candidate, UUID batch) {
                         return sealedBatch[0] == null || sealedBatch[0].equals(batch);
                     }
                     public void batchClosed(UUID id, UUID batch) { if (events.stream().anyMatch(CombatEvent::lethal)) sealedBatch[0] = batch; }
                 }, events::add)) {
                MonsterRoles.select(owner, "zombie");
                owner.getInventory().setItem(4, new ItemStack(Items.IRON_SPEAR)); owner.getInventory().setSelectedSlot(4);
                owner.setYRot(0); owner.setXRot(0);
                first.teleportTo(owner.getX(), owner.getY(), owner.getZ() + 1.4);
                second.teleportTo(owner.getX(), owner.getY(), owner.getZ() + 2.0);
                first.setHealth(1); second.setHealth(1);
                test.assertTrue(MonsterNativeWeapons.stabbing(owner), "One real piercing attack is admitted");
                test.assertFalse(first.isAlive() || second.isAlive(), "Terminal damage does not interrupt another target from the same stab");
                var deaths = events.stream().filter(CombatEvent::lethal).toList();
                test.assertValueEqual(deaths.size(), 2, "Both pierced players have lethal evidence");
                test.assertValueEqual(deaths.get(0).batchId(), deaths.get(1).batchId(), "The complete native piercing action shares one batch");
                test.assertTrue(deaths.stream().allMatch(event -> event.origin().orElseThrow().actor().playerId().equals(owner.getUUID())),
                        "Both deaths retain the deployed monster's native source");
            } finally { remove(owner); remove(first); remove(second); }
            test.succeed();
        });
    }

    private static java.util.List<ThrownTrident> tridents(net.minecraft.server.level.ServerPlayer player) {
        return player.level().getEntitiesOfClass(ThrownTrident.class, player.getBoundingBox().inflate(100)).stream()
                .filter(shot -> player.getUUID().equals(MonsterNativeWeapons.tridentOwner(shot))).toList();
    }

    @GameTest(maxTicks = 200)
    public void realThornsArmorDamagesTheAttackingPlayerBody(GameTestHelper test) {
        var owner = actor(test); var defender = actor(test);
        test.runAfterDelay(80, () -> {
            try (var combat = formalCombat(owner, defender)) {
                MonsterRoles.select(owner, "zombie");
                MonsterRoles.select(defender, "zombie");
                owner.getInventory().setSelectedSlot(4); owner.getInventory().setItem(4, ItemStack.EMPTY);
                for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET))
                    owner.setItemSlot(slot, ItemStack.EMPTY);
                defender.teleportTo(owner.getX(), owner.getY(), owner.getZ() + 1);
                var helmet = new ItemStack(Items.DIAMOND_HELMET);
                helmet.enchant(owner.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.THORNS), 3);
                defender.setItemSlot(EquipmentSlot.HEAD, helmet);
                float before = owner.getHealth();
                // Keep the native maximum level and use a fixed RNG sequence for its 45% proc chance.
                owner.level().getRandom().setSeed(730213L);
                for (int attack = 0; attack < 32 && owner.getHealth() == before; attack++) {
                    owner.damageCooldownTime = 0; owner.setInvulnerableTime(0);
                    defender.setHealth(defender.getMaxHealth()); defender.damageCooldownTime = 0; defender.setInvulnerableTime(0);
                    MonsterRoles.syncNativeContext(owner);
                    test.assertTrue(MonsterRoles.nativeModel(owner).doHurtTarget(owner.level(), defender), "The actual native mob attack reaches the equipped target");
                    MonsterRoles.afterNativeAttack(owner);
                }
                test.assertTrue(owner.getHealth() < before, "Native Thorns III hurts the actual monster player, not only its unregistered model");
                test.assertTrue(P2CombatRuntime.source(MonsterRoles.nativeModel(owner)).isPresent()
                        && P2CombatRuntime.source(MonsterRoles.nativeModel(defender)).isPresent(),
                        "Both attacker and thorn wearer use formal frozen monster identities");
                test.assertValueEqual(MonsterRoles.nativeModel(owner).getHealth(), owner.getHealth(), "The template follows the authoritative damaged body");
                test.assertTrue(defender.getItemBySlot(EquipmentSlot.HEAD).getDamageValue() > 0, "The real target armor receives native thorns wear");
            } finally { remove(owner); remove(defender); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void oldNativeModelsAndArrowsCannotReflectIntoANewBody(GameTestHelper test) {
        var owner = actor(test); var defender = actor(test);
        test.runAfterDelay(80, () -> {
            var shots = new ArrayList<AbstractArrow>();
            try {
                MonsterRoles.select(owner, "skeleton");
                var oldModel = MonsterRoles.nativeModel(owner);
                test.assertTrue(MonsterRoles.burst(owner), "The original body fires a real native arrow");
                shots.addAll(owner.level().getEntitiesOfClass(AbstractArrow.class, owner.getBoundingBox().inflate(5)).stream()
                        .filter(arrow -> arrow.getOwner() == owner).toList());
                var oldArrow = shots.getFirst();
                test.assertTrue(MonsterRoles.nativeArrowShooter(oldArrow, owner) == oldModel, "A same-body arrow may route retaliation to its owner");
                MonsterRoles.clear(owner); MonsterRoles.select(owner, "skeleton");
                var current = MonsterRoles.nativeModel(owner); float before = owner.getHealth();
                test.assertFalse(oldModel.hurtServer(owner.level(), owner.damageSources().thorns(defender), 3), "A departed template cannot hurt the new body");
                var fallback = (net.minecraft.world.entity.LivingEntity) MonsterRoles.nativeArrowShooter(oldArrow, owner);
                test.assertTrue(fallback != current, "An old arrow never adopts a new outing's template");
                test.assertFalse(fallback.hurtServer(owner.level(), owner.damageSources().thorns(defender), 3), "Old-arrow thorns stays detached from a new player life");
                var round = UUID.randomUUID();
                ((dev.hardrush.p2.combat.MonsterSourceHolder) oldArrow).hardrush$stampMonsterSource(
                        new dev.hardrush.p2.combat.FrozenMonsterSource(round, owner.getUUID(), 1, 1, true));
                ((dev.hardrush.p2.combat.MonsterSourceHolder) current).hardrush$stampMonsterSource(
                        new dev.hardrush.p2.combat.FrozenMonsterSource(round, owner.getUUID(), 2, 2, true));
                oldArrow.entityTags().add("hardrush.shot-binding=" + MonsterRoles.binding(owner));
                test.assertTrue(MonsterRoles.nativeArrowShooter(oldArrow, owner) != current,
                        "A matching body tag alone cannot override frozen life and deployment identity");
                test.assertValueEqual(owner.getHealth(), before, "Rejected old effects leave the current player untouched");
            } finally { shots.forEach(Entity::discard); remove(owner); remove(defender); }
            test.succeed();
        });
    }

    private static final class ImpactTrident extends ThrownTrident {
        ImpactTrident(net.minecraft.server.level.ServerLevel level, net.minecraft.server.level.ServerPlayer owner) {
            super(level, owner, new ItemStack(Items.TRIDENT));
        }
        void hit(Entity target) { onHitEntity(new EntityHitResult(target)); }
    }

    private static P2CombatRuntime.Scope formalCombat(net.minecraft.server.level.ServerPlayer... players) {
        var level = players[0].level(); var round = UUID.randomUUID();
        var participants = new HashMap<UUID, Participant>();
        for (var player : players) participants.put(player.getUUID(), new Participant(player.getUUID(), player.getGameProfile().name(),
                ParticipantRole.MONSTER_DEPLOYED, true, false, 0, 0, 1, false, true, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 1));
        return RuntimeFixtures.monsterCombat(level.getServer(), new P2CombatRuntime.Access() {
            public Optional<RoundSnapshot> snapshot() { return Optional.of(new RoundSnapshot(round, 1, 42, 0, RulesConfig.defaults(),
                    RoundPhase.RUNNING, PauseReason.NONE, 0, 0, participants, false, false, null)); }
            public boolean competitionLocked() { return false; }
            public boolean formalLevel(net.minecraft.server.level.ServerLevel candidate) { return candidate == level; }
        });
    }
}
