package dev.hardrush.gametest;

import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.round.ParticipantRole;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Melee that does not deal {@code player_attack} or does not pass through {@code Player.attack}: a spear's jab (the
 * STAB action) and charge (its use tick) and a mace smash. Each must credit the human who struck, as a sword does,
 * because revival totem drops and the first-dragon winner read that credit. Headless, on mock players.
 */
public final class MeleeProvenanceGameTest {
    @GameTest(maxTicks = 100)
    public void spearAndMaceHitsCreditTheirHumanLikeASword(GameTestHelper test) {
        var level = test.getLevel(); var server = level.getServer();
        var attacker = test.makeMockServerPlayerInLevel();
        var jabbed = test.makeMockServerPlayerInLevel();
        var charged = test.makeMockServerPlayerInLevel();
        var smashed = test.makeMockServerPlayerInLevel();
        var everyone = List.of(attacker, jabbed, charged, smashed);
        var humans = new HashSet<UUID>(); everyone.forEach(p -> humans.add(p.getUUID()));
        var round = UUID.randomUUID(); var events = new ArrayList<CombatEvent>(); var frozen = new boolean[1];
        var access = new CombatRuntime.RoundAccess() {
            @Override public Optional<UUID> roundOf(ServerLevel any) { return Optional.of(round); }
            @Override public Optional<CombatActor> actor(UUID id, UUID playerId) {
                return humans.contains(playerId) ? Optional.of(new CombatActor(playerId, ParticipantRole.HUMAN_ACTIVE, 1, 0)) : Optional.empty();
            }
            @Override public boolean mayDamage(ServerLevel any, UUID batch) { return !frozen[0]; }
        };
        // A mock player starts creative and, never sending the client-loaded packet, invulnerable.
        everyone.forEach(p -> {
            p.setGameMode(GameType.SURVIVAL); p.setPermanentlyInvulnerable(false); p.setInvulnerableTime(0);
            p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        });
        var base = test.absoluteVec(new Vec3(1.5, 2, 1.5));
        for (int i = 1; i < everyone.size(); i++) everyone.get(i).snapTo(base.x + 4 * i, base.y, base.z + 3, 180, 0);
        try (var fixture = RuntimeFixtures.combat(server, access, events::add)) {
            var spear = new ItemStack(Items.IRON_SPEAR);
            attacker.setItemSlot(EquipmentSlot.MAINHAND, spear);

            // A frozen competition stops the whole jab, its damage-free knockback included, as it stops a sword swing.
            face(attacker, jabbed);
            frozen[0] = true;
            float health = jabbed.getHealth();
            jabbed.setDeltaMovement(Vec3.ZERO);
            spear.get(DataComponents.PIERCING_WEAPON).attack(attacker, EquipmentSlot.MAINHAND);
            test.assertTrue(jabbed.getHealth() == health && jabbed.getDeltaMovement().equals(Vec3.ZERO),
                    "A frozen competition stops a jab's damage and knockback");
            frozen[0] = false;

            // The jab, through the same native weapon component the STAB action calls.
            spear.get(DataComponents.PIERCING_WEAPON).attack(attacker, EquipmentSlot.MAINHAND);
            test.assertTrue(jabbed.getHealth() < health, "A human's spear jab lands natively");
            credited(test, level, jabbed, attacker, round, "A spear jab");

            // The charge, through the use tick's sweep with the attacker running at the target.
            face(attacker, charged);
            attacker.setKnownMovement(new Vec3(0, 0, .5));
            var kinetic = spear.get(DataComponents.KINETIC_WEAPON);
            health = charged.getHealth();
            kinetic.damageEntities(spear, spear.getUseDuration(attacker) - kinetic.delayTicks() - 1, attacker, EquipmentSlot.MAINHAND);
            attacker.setKnownMovement(Vec3.ZERO);
            test.assertTrue(charged.getHealth() < health, "A human's spear charge lands natively");
            credited(test, level, charged, attacker, round, "A spear charge");

            // A mace smash passes through Player.attack, but deals mace_smash: the action's credit must survive it.
            face(attacker, smashed);
            attacker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.MACE));
            attacker.fallDistance = 2;
            health = smashed.getHealth();
            attacker.attack(smashed);
            test.assertTrue(smashed.getHealth() < health, "A human's mace smash lands natively");
            credited(test, level, smashed, attacker, round, "A mace smash");

            // The first dragon's last hit by a spear names its human, never an unknown source.
            attacker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SPEAR));
            var dragon = new EnderDragon(EntityTypes.ENDER_DRAGON, level);
            dragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);
            float dragonHealth = dragon.getHealth();
            attacker.stabAttack(EquipmentSlot.MAINHAND, dragon.head, 4, true, false, false);
            test.assertTrue(dragon.getHealth() < dragonHealth, "A spear stab hurts the dragon natively");
            var hit = events.getLast();
            test.assertValueEqual(hit.rawDamageType(), "minecraft:spear", "The dragon took the spear's own damage type");
            test.assertValueEqual(hit.attribution(), CombatEvent.Attribution.HUMAN, "A spear hit on the dragon is a human's");
            test.assertValueEqual(hit.origin().orElseThrow().actor().playerId(), attacker.getUUID(), "It names the human who struck");
        } finally { everyone.forEach(p -> server.getPlayerList().remove(p)); }
        test.succeed();
    }

    /** Puts the attacker three blocks in front of the target, looking straight at it. */
    private static void face(ServerPlayer attacker, ServerPlayer target) {
        attacker.snapTo(target.getX(), target.getY(), target.getZ() - 3, 0, 0);
        attacker.setYHeadRot(0);
    }

    /** The credit a death right now would resolve, by a later fall: the hit just taken, remembered like a sword's. */
    private static void credited(GameTestHelper test, ServerLevel level, ServerPlayer victim, ServerPlayer attacker,
                                 UUID round, String what) {
        Optional<CombatOrigin> credit = CombatRuntime.peekKillCredit(level, victim, level.damageSources().fall());
        test.assertTrue(credit.filter(origin -> origin.actor().playerId().equals(attacker.getUUID())
                        && origin.kind() == AttackKind.MELEE && origin.isHumanAttack(round)).isPresent(),
                what + " credits its human's melee: " + credit);
    }
}
