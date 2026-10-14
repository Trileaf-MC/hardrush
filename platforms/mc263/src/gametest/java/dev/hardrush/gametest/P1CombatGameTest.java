package dev.hardrush.gametest;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.*;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.p1.combat.P1CombatRuntime;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Headless authoritative game engine fixtures; no game client or final scoring assertion. */
public final class P1CombatGameTest {
    @GameTest(maxTicks=100)
    public void removedShooterKeepsAnActualHumanArrow(GameTestHelper test) {
        var level = test.getLevel(); var server = level.getServer(); var player = test.makeMockServerPlayerInLevel();
        var round = UUID.randomUUID(); var events = new ArrayList<CombatEvent>();
        var access = new P1CombatRuntime.RoundAccess() {
            @Override public Optional<UUID> roundOf(ServerLevel any) { return Optional.of(round); }
            @Override public Optional<CombatActor> actor(UUID id, UUID playerId) {
                return playerId.equals(player.getUUID()) ? Optional.of(new CombatActor(playerId, ParticipantRole.HUMAN_ACTIVE, 1, 0)) : Optional.empty();
            }
        };
        try (var fixture = P1CombatRuntime.temporaryConfiguration(server, access, events::add)) {
            var arrow = new Arrow(level, player, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            var captured = P1CombatRuntime.originOf(arrow).orElseThrow();
            server.getPlayerList().remove(player);
            player.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            test.assertTrue(arrow.getOwner() == null, "Native owner reference resolves empty after shooter was removed");
            var dragon = new EnderDragon(EntityTypes.ENDER_DRAGON, level);
            dragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);
            float before = dragon.getHealth();
            dragon.hurt(level, dragon.head, level.damageSources().arrow(arrow, arrow.getOwner()), 4);
            test.assertTrue(dragon.getHealth() < before, "Actual original human arrow can hurt dragon after shooter logout");
            test.assertValueEqual(events.getLast().origin().orElseThrow(), captured, "No current identity lookup rewrites the offline arrow");
            test.assertValueEqual(events.getLast().rawDamageType(), "minecraft:arrow", "Source is still a native arrow");
        } finally { server.getPlayerList().remove(player); }
        test.succeed();
    }

    @GameTest(maxTicks=100)
    public void creationSnapshotsAndNativeDamageStayCausal(GameTestHelper test) {
        var level = test.getLevel(); var server = level.getServer();
        var player = test.makeMockServerPlayerInLevel();
        var round = new UUID[]{UUID.randomUUID()};
        var identity = new ParticipantRole[]{ParticipantRole.HUMAN_ACTIVE};
        var events = new ArrayList<CombatEvent>();
        var access = new P1CombatRuntime.RoundAccess() {
            @Override public Optional<UUID> roundOf(ServerLevel any) { return Optional.of(round[0]); }
            @Override public Optional<CombatActor> actor(UUID id, UUID playerId) {
                return playerId.equals(player.getUUID()) ? Optional.of(new CombatActor(playerId, identity[0], 3, 2)) : Optional.empty();
            }
        };
        try (var fixture = P1CombatRuntime.temporaryConfiguration(server, access, events::add)) {
            var arrow = new Arrow(level, player, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            var captured = P1CombatRuntime.originOf(arrow).orElseThrow();
            test.assertValueEqual(captured.kind(), AttackKind.ARROW, "Native arrow constructor captures source");
            identity[0] = ParticipantRole.MONSTER_DEPLOYED;
            arrow.setOwner(player);
            test.assertValueEqual(P1CombatRuntime.originOf(arrow).orElseThrow(), captured, "Identity changes never rewrite an existing arrow");

            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            arrow.saveWithoutId(output);
            var reloaded = new Arrow(EntityTypes.ARROW, level);
            reloaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
            reloaded.setOwner(player);
            test.assertValueEqual(P1CombatRuntime.originOf(reloaded).orElseThrow(), captured, "Entity NBT preserves life and creation identity after reload");
            var mismatched = new Arrow(EntityTypes.ARROW, level);
            mismatched.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult().copy()));
            mismatched.setUUID(UUID.randomUUID());
            test.assertTrue(P1CombatRuntime.originOf(mismatched).isEmpty(), "A snapshot with another direct entity UUID is not trusted");

            var legacy = new Arrow(EntityTypes.ARROW, level);
            var legacyTag = output.buildResult().copy(); legacyTag.remove("hardrush_combat_origin");
            legacy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), legacyTag));
            legacy.setOwner(player);
            test.assertTrue(P1CombatRuntime.originOf(legacy).isEmpty(), "Legacy arrows stay unknown after owner reassignment");

            var dragon = new EnderDragon(EntityTypes.ENDER_DRAGON, level);
            dragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);
            dragon.hurt(level, dragon.getSubEntities()[0], level.damageSources().arrow(reloaded, player), 4);
            test.assertTrue(!events.isEmpty(), "Native dragon part damage recorded under parent dragon UUID");
            test.assertValueEqual(events.getLast().victimId(), dragon.getUUID(), "Parts share one dragon identity");
            test.assertValueEqual(events.getLast().attribution(), CombatEvent.Attribution.HUMAN, "Dead or changed human shooter keeps original human attack");

            var monsterArrow = new Arrow(level, player, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            identity[0] = ParticipantRole.HUMAN_ACTIVE;
            float beforeRejected = dragon.getHealth(); int countRejected = events.size();
            dragon.hurt(level, dragon.getSubEntities()[0], level.damageSources().arrow(monsterArrow, player), 4);
            test.assertValueEqual(dragon.getHealth(), beforeRejected, "Monster arrow cannot hurt dragon after owner revival");
            test.assertValueEqual(events.size(), countRejected, "Rejected monster arrow does not fabricate actual damage evidence");

            // Simulate a disconnected owner reference without introducing a replacement player or source type.
            reloaded.setOwner((net.minecraft.world.entity.Entity)null);
            test.assertTrue(reloaded.getOwner() == null, "Arrow has no live owner after disconnect");
            var offlineDragon = new EnderDragon(EntityTypes.ENDER_DRAGON, level);
            float offlineBefore = offlineDragon.getHealth();
            offlineDragon.hurt(level, offlineDragon.head, level.damageSources().arrow(reloaded, reloaded.getOwner()), 4);
            test.assertTrue(offlineDragon.getHealth() < offlineBefore, "Trusted current-round human arrow still causes actual dragon damage offline");
            test.assertValueEqual(events.getLast().attribution(), CombatEvent.Attribution.HUMAN, "Offline human source is attributed from its creation snapshot");
            test.assertValueEqual(events.getLast().rawDamageType(), "minecraft:arrow", "Offline damage preserves the native damage type");

            round[0] = UUID.randomUUID();
            beforeRejected = dragon.getHealth(); countRejected = events.size();
            dragon.hurt(level, dragon.getSubEntities()[0], level.damageSources().arrow(reloaded, player), 4);
            test.assertValueEqual(dragon.getHealth(), beforeRejected, "Late old-round arrow cannot damage current-round dragon");
            test.assertValueEqual(events.size(), countRejected, "Rejected old-round arrow does not create current-round damage evidence");
            var unknownDragon = new EnderDragon(EntityTypes.ENDER_DRAGON, level);
            unknownDragon.hurt(level, unknownDragon.head, level.damageSources().explosion(null, null), 4);
            test.assertValueEqual(events.getLast().attribution(), CombatEvent.Attribution.PENDING_VERIFICATION, "Unknown actual explosion does not guess the recent player");

            var meleeDragon = new EnderDragon(EntityTypes.ENDER_DRAGON, level);
            meleeDragon.hurt(level, meleeDragon.getSubEntities()[0], level.damageSources().playerAttack(player), 4);
            test.assertValueEqual(events.getLast().origin().orElseThrow().kind(), AttackKind.MELEE, "Actual melee source uses current creation identity");
            // Return to the arrow's original round to exercise crystal trigger inheritance.
            round[0] = captured.roundId();
            var crystal = new EndCrystal(EntityTypes.END_CRYSTAL, level);
            crystal.setPos(test.absoluteVec(new Vec3(0, 5, 0)));
            crystal.hurtServer(level, level.damageSources().arrow(reloaded, player), 1);
            var crystalSource = P1CombatRuntime.originOf(crystal).orElseThrow();
            test.assertValueEqual(crystalSource.kind(), AttackKind.END_CRYSTAL, "Actual crystal destruction captures its trigger");
            test.assertValueEqual(crystalSource.actor(), captured.actor(), "Crystal trigger inherits the original human arrow identity");
            test.assertTrue(crystalSource.causalParents().contains(captured.originId()), "Crystal evidence links its actual parent");
            var linkedDragon = new EnderDragon(EntityTypes.ENDER_DRAGON, level);
            linkedDragon.nearestCrystal = crystal;
            linkedDragon.onCrystalDestroyed(level, crystal, crystal.blockPosition(), level.damageSources().arrow(reloaded, player));
            test.assertValueEqual(events.getLast().origin().orElseThrow(), crystalSource, "Linked healing crystal damage uses captured crystal source");
            var unknownCrystal = new EndCrystal(EntityTypes.END_CRYSTAL, level);
            unknownCrystal.hurtServer(level, level.damageSources().explosion(null, null), 1);
            var unknownLinkedDragon = new EnderDragon(EntityTypes.ENDER_DRAGON, level);
            unknownLinkedDragon.nearestCrystal = unknownCrystal;
            unknownLinkedDragon.onCrystalDestroyed(level, unknownCrystal, unknownCrystal.blockPosition(), level.damageSources().explosion(null, player));
            test.assertValueEqual(events.getLast().attribution(), CombatEvent.Attribution.PENDING_VERIFICATION, "Vanilla player credit for an unknown crystal is never trusted as causality");
            test.assertValueEqual(events.getLast().rawDirectEntityId().orElseThrow(), unknownCrystal.getUUID(), "Unknown evidence retains the actual crystal UUID");
            test.assertValueEqual(events.getLast().rawCausingEntityId().orElseThrow(), player.getUUID(), "Untrusted vanilla player credit is kept only as raw evidence");

            var dyingDragon = new EnderDragon(EntityTypes.ENDER_DRAGON, level);
            dyingDragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN); dyingDragon.setHealth(.1f);
            dyingDragon.hurt(level, dyingDragon.head, level.damageSources().playerAttack(player), 100);
            test.assertValueEqual(dyingDragon.getHealth(), 1f, "Vanilla flight death restores one health during its animation");
            test.assertTrue(events.getLast().lethal(), "Entering DYING records irreversible damage despite restored health");
            int deathCount = events.size();
            dyingDragon.hurt(level, dyingDragon.head, level.damageSources().playerAttack(player), 100);
            test.assertValueEqual(events.size(), deathCount, "Later damage during DYING does not create a second lethal event");

            var pos = test.absolutePos(new BlockPos(1, 5, 1));
            var tnt = new PrimedTnt(level, pos.getX(), pos.getY(), pos.getZ(), player);
            test.assertValueEqual(P1CombatRuntime.originOf(tnt).orElseThrow().kind(), AttackKind.TNT, "Direct TNT ignition captures actor at creation");
            var chainedPos = pos.offset(3, 0, 0);
            var explosion = new ServerExplosion(level, tnt, null, null, Vec3.atCenterOf(pos), 4, false, Explosion.BlockInteraction.KEEP);
            ((TntBlock)Blocks.TNT).wasExploded(level, chainedPos, explosion);
            var chained = level.getEntitiesOfClass(PrimedTnt.class, new AABB(chainedPos).inflate(1));
            test.assertTrue(!chained.isEmpty(), "Native explosion creates a chained TNT entity");
            var chainedOrigin = P1CombatRuntime.originOf(chained.getLast()).orElseThrow();
            test.assertTrue(chainedOrigin.causalParents().contains(P1CombatRuntime.originOf(tnt).orElseThrow().originId()), "TNT chain preserves the parent origin");
            for (var entity : chained) entity.discard();
        } finally { server.getPlayerList().remove(player); }
        test.succeed();
    }

    @GameTest(maxTicks=3000)
    public void bedAndAnchorUseCaptureTheActualInteractor(GameTestHelper test) {
        var server = test.getLevel().getServer(); var player = test.makeMockServerPlayerInLevel();
        var id = UUID.randomUUID(); var events = new ArrayList<CombatEvent>();
        var access = new P1CombatRuntime.RoundAccess() {
            @Override public Optional<UUID> roundOf(ServerLevel any) { return Optional.of(id); }
            @Override public Optional<CombatActor> actor(UUID round, UUID playerId) {
                return playerId.equals(player.getUUID()) ? Optional.of(new CombatActor(playerId, ParticipantRole.HUMAN_ACTIVE, 1, 0)) : Optional.empty();
            }
        };
        var arenas = new ArrayList<BlastArena>();
        for (boolean bed : new boolean[]{false, true}) {
            var level = bed ? server.getLevel(Level.NETHER) : test.getLevel();
            test.assertTrue(level != null, "Native Nether exists for bed explosion");
            var pos = new BlockPos(2800, 200, bed ? 30 : 0);
            for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
                level.setChunkForced((pos.getX() >> 4) + dx, (pos.getZ() >> 4) + dz, true);
            }
            var dragon = new EnderDragon(EntityTypes.ENDER_DRAGON, level);
            dragon.setNoAi(true); dragon.setPos(Vec3.atCenterOf(pos).add(0, 0, 2)); level.addFreshEntity(dragon);
            arenas.add(new BlastArena(bed, level, pos, dragon));
        }
        test.startSequence()
                .thenWaitUntil(() -> {
                    for (var arena : arenas) {
                        test.assertTrue(arena.level.isPositionEntityTicking(arena.pos), "Native blast arena reaches entity ticking");
                        test.assertTrue(arena.level.getEntitiesOfClass(EnderDragon.class, new AABB(arena.pos).inflate(15)).contains(arena.dragon),
                                "Exact new dragon is visible to native entity queries before blast");
                    }
                })
                .thenExecute(() -> {
                    try (var fixture = P1CombatRuntime.temporaryConfiguration(server, access, events::add)) {
                        for (var arena : arenas) {
                            boolean bed = arena.bed; var level = arena.level; var pos = arena.pos; var dragon = arena.dragon;
                            for (var old : level.getEntitiesOfClass(EnderDragon.class, new AABB(pos).inflate(15))) if (old != dragon) old.discard();
                            player.teleportTo(level, pos.getX()+20, pos.getY(), pos.getZ(), Set.of(), 0, 0, true);
                            for (var part : dragon.getSubEntities()) part.setPos(dragon.position());
                            var state = bed ? Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD).setValue(BedBlock.FACING, Direction.EAST)
                                    : Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 1);
                            level.setBlock(pos, state, 2);
                            if (bed) level.setBlock(pos.west(), state.setValue(BedBlock.PART, BedPart.FOOT), 2);
                            int count = events.size();
                            var result = state.useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                            test.assertTrue(events.size()>count, "Native "+(bed?"bed":"anchor")+" explosion reaches exact dragon; remaining="
                                    +level.getBlockState(pos)+"; health="+dragon.getHealth()+"; result="+result);
                            var event = events.getLast();
                            test.assertValueEqual(event.victimId(), dragon.getUUID(), "Explosion evidence refers to exact fixture dragon");
                            test.assertValueEqual(event.origin().orElseThrow().kind(), bed?AttackKind.BED:AttackKind.RESPAWN_ANCHOR, "Explosion retains actual interaction source");
                            test.assertValueEqual(event.origin().orElseThrow().actor().playerId(), player.getUUID(), "Interactor owns the blast");
                        }
                    } finally {
                        for (var arena : arenas) {
                            arena.dragon.discard();
                            for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
                                arena.level.setChunkForced((arena.pos.getX()>>4)+dx, (arena.pos.getZ()>>4)+dz, false);
                            }
                        }
                        server.getPlayerList().remove(player);
                    }
                })
                .thenSucceed();
    }
    private record BlastArena(boolean bed, ServerLevel level, BlockPos pos, EnderDragon dragon) {}
}
