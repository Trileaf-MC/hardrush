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

/** Headless native combat, first-dragon scoring and transaction fixtures; no game client. */
public final class P1CombatGameTest {
    @GameTest(maxTicks=24000)
    public void actualFirstDragonAttackCommitsOneTerminalAndItsEvidence(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var player = test.makeMockServerPlayerInLevel();
        java.nio.file.Path database;
        try {
            var root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toRealPath()
                    .resolve("hardrush/terminal-game-tests/" + UUID.randomUUID());
            java.nio.file.Files.createDirectories(root);
            database = root.resolve("terminal.sqlite");
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        var rules = dev.hardrush.core.config.RulesConfig.defaults().withMode(dev.hardrush.core.config.GameMode.NORMAL);
        var snapshot = new dev.hardrush.core.round.RoundSnapshot(UUID.randomUUID(), 1, 12345, 0, rules,
                dev.hardrush.core.round.RoundPhase.RUNNING, dev.hardrush.core.round.PauseReason.SERVER_STOPPED,
                0, 0, Map.of(player.getUUID(), new dev.hardrush.core.round.Participant(player.getUUID(), player.getScoreboardName(),
                    ParticipantRole.HUMAN_ACTIVE, false, false, 0, 0, 1, false)), true, false, null);
        var seeded = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try (var store = new dev.hardrush.persistence.SqliteRoundStore(database)) {
                store.savePendingRules(rules.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8), UUID.randomUUID(), "native-terminal-fixture");
                store.commit(new dev.hardrush.persistence.SnapshotWrite(snapshot.roundId(), -1, 0,
                        dev.hardrush.core.round.RoundSnapshotCodec.encode(snapshot), UUID.randomUUID(), "INITIAL"));
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        });
        var session = new dev.hardrush.p1.P1LobbySession[1];
        var bundle = new dev.hardrush.p1.world.P1RoundWorldBundle[1];
        var fight = new net.minecraft.world.level.dimension.end.EnderDragonFight[1];
        var end = new ServerLevel[1];
        var first = new EnderDragon[1];
        var lethal = new CombatEvent[1];
        var closing = new java.util.concurrent.CompletableFuture<?>[1];
        var checked = new java.util.concurrent.CompletableFuture<?>[1];
        test.startSequence()
                .thenWaitUntil(() -> {
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    if (seeded.isCompletedExceptionally()) seeded.join();
                    test.assertTrue(seeded.isDone(), "Independent native fixture database was seeded");
                })
                .thenExecute(() -> {
                    bundle[0] = dev.hardrush.p1.world.P1RoundWorldBundle.open(server, snapshot);
                    session[0] = new dev.hardrush.p1.P1LobbySession(database, dev.hardrush.core.api.MonotonicClock.system(), server::execute);
                })
                .thenWaitUntil(() -> {
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    bundle[0].tick();
                    if (bundle[0].failure().isPresent()) throw new IllegalStateException(bundle[0].failure().orElseThrow());
                    test.assertTrue(bundle[0].ready() && session[0].state() == dev.hardrush.p1.P1LobbySession.State.OPEN,
                            "The real three-dimension bundle and isolated session are ready");
                })
                .thenExecute(() -> session[0].adaptersReady(snapshot.roundId()))
                .thenWaitUntil(() -> test.assertTrue(!session[0].mutationPending(), "Clean restart is marked dirty before play"))
                .thenExecute(() -> session[0].onOnline(player.getUUID(), player.getScoreboardName(), true))
                .thenWaitUntil(() -> test.assertTrue(!session[0].mutationPending()
                        && session[0].snapshot().orElseThrow().participants().get(player.getUUID()).online(), "Original human timing is durably online"))
                .thenExecute(() -> {
                    end[0] = bundle[0].level(Level.END);
                    player.teleportTo(end[0], .5, 128, .5, Set.of(), 0, 0, true);
                    // Only the arena-loaded precondition is bypassed: first-dragon creation and hurt are native.
                    fight[0] = new net.minecraft.world.level.dimension.end.EnderDragonFight(false, false, false,
                            Optional.empty(), 0, Optional.empty(), Optional.of(new BlockPos(0, 64, 0)), List.of(), List.of());
                    fight[0].init(end[0], end[0].getSeed(), BlockPos.ZERO);
                    fight[0].skipArenaLoadedCheck();
                    end[0].setDragonFight(fight[0]);
                    end[0].getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL,
                            new net.minecraft.world.level.ChunkPos(0, 0), 3);
                })
                .thenWaitUntil(() -> {
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    test.assertTrue(fight[0].dragonUUID() != null && end[0].getEntity(fight[0].dragonUUID()) instanceof EnderDragon,
                            "Native fight creates its authoritative first dragon");
                })
                .thenExecute(() -> {
                    first[0] = (EnderDragon)end[0].getEntity(fight[0].dragonUUID());
                    var source = new CombatOrigin(snapshot.roundId(), UUID.randomUUID(),
                            new CombatActor(player.getUUID(), ParticipantRole.HUMAN_ACTIVE, 1, 0), AttackKind.MELEE,
                            Optional.of(player.getUUID()), List.of());
                    var valid = fixtureDragonEvent(snapshot.roundId(), first[0].getUUID(), source, true);
                    test.assertTrue(dev.hardrush.p1.P1LobbyRuntime.verifiedFirstDragon(bundle[0], valid).isPresent(), "Actual first-fight UUID is accepted");
                    test.assertTrue(dev.hardrush.p1.P1LobbyRuntime.verifiedFirstDragon(bundle[0],
                            fixtureDragonEvent(snapshot.roundId(), UUID.randomUUID(), source, true)).isEmpty(), "A separately spawned dragon cannot borrow the native fight identity");
                    test.assertTrue(dev.hardrush.p1.P1LobbyRuntime.verifiedFirstDragon(bundle[0],
                            fixtureDragonEvent(snapshot.roundId(), first[0].getUUID(), source, false)).isEmpty(), "Nonlethal native damage cannot finish a round");
                    test.assertTrue(dev.hardrush.p1.P1LobbyRuntime.verifiedFirstDragon(bundle[0],
                            fixtureDragonEvent(UUID.randomUUID(), first[0].getUUID(), source, true)).isEmpty(), "Old-round evidence cannot refer to this native first dragon");
                    var previouslyKilled = new net.minecraft.world.level.dimension.end.EnderDragonFight(false, false, true,
                            Optional.empty(), 0, Optional.of(first[0].getUUID()), Optional.of(new BlockPos(0, 64, 0)), List.of(), List.of());
                    previouslyKilled.init(end[0], end[0].getSeed(), BlockPos.ZERO);
                    end[0].setDragonFight(previouslyKilled);
                    test.assertTrue(dev.hardrush.p1.P1LobbyRuntime.verifiedFirstDragon(bundle[0], valid).isEmpty(), "A native respawn fight with the prior-kill flag cannot score again");
                    end[0].setDragonFight(fight[0]);
                    first[0].snapTo(.5, 68, .5, 0, 0); first[0].setOldPosAndRot(); first[0].setNoAi(true);
                    first[0].getPhaseManager().setPhase(EnderDragonPhase.SITTING_SCANNING);
                    first[0].setHealth(.1f);
                    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
                    var access = new P1CombatRuntime.RoundAccess() {
                        @Override public Optional<UUID> roundOf(ServerLevel level) {
                            return bundle[0].ready() && dev.hardrush.p1.world.P1RoundWorldBundle.sameRound(end[0], level)
                                    ? Optional.of(snapshot.roundId()) : Optional.empty();
                        }
                        @Override public Optional<CombatActor> actor(UUID round, UUID id) {
                            return id.equals(player.getUUID()) ? Optional.of(new CombatActor(id, ParticipantRole.HUMAN_ACTIVE, 1, 0)) : Optional.empty();
                        }
                        @Override public boolean competitionFrozen(ServerLevel level) { return session[0].competitionLocked(); }
                        @Override public boolean mayDamage(ServerLevel level, UUID batch) {
                            return !session[0].competitionLocked() || session[0].terminalBatchId().filter(id -> id.equals(batch)).isPresent();
                        }
                        @Override public void batchClosed(UUID round, UUID batch) { session[0].finishDamageBatch(round, batch); }
                    };
                    try (var fixture = dev.hardrush.gametest.RuntimeFixtures.combat(server, access, event -> {
                        var verified = dev.hardrush.p1.P1LobbyRuntime.verifiedFirstDragon(bundle[0], event);
                        if (verified.isPresent()) {
                            lethal[0] = event;
                            session[0].captureDragonDeath(event, verified.orElseThrow());
                        }
                    })) {
                        player.attack(first[0].head);
                        test.assertTrue(lethal[0] != null && lethal[0].lethal(), "Actual native player attack creates the lethal event");
                        test.assertTrue(session[0].competitionLocked(), "New attacks are frozen at the actual native lethal callback");
                    }
                })
                .thenWaitUntil(() -> {
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    test.assertTrue(!session[0].mutationPending() && session[0].snapshot().orElseThrow().result() != null,
                            "Native lethal event and result finish their atomic SQLite transaction");
                })
                .thenExecute(() -> {
                    var settled = session[0].snapshot().orElseThrow();
                    test.assertValueEqual(settled.phase(), dev.hardrush.core.round.RoundPhase.SETTLING, "The lethal event commits SETTLING before public celebration");
                    test.assertValueEqual(settled.result().winner(), player.getUUID(), "The actual human attack owns the stored winner");
                    try { session[0].close(); } catch (Exception error) { throw new IllegalStateException(error); }
                    checked[0] = java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try (var store = new dev.hardrush.persistence.SqliteRoundStore(database)) {
                            test.assertTrue(store.terminal(snapshot.roundId()).isPresent(), "One unique terminal is persisted for the native round");
                            var evidence = store.evidence(snapshot.roundId(), "COMBAT", 10);
                            test.assertValueEqual(evidence.size(), 1, "The same atomic terminal transaction stores one lethal evidence record");
                            test.assertValueEqual(CombatEventCodec.decode(evidence.getFirst().payload()), lethal[0], "Stored causal evidence matches the actual dragon callback");
                            var stored = dev.hardrush.core.round.RoundSnapshotCodec.decode(store.load(snapshot.roundId()).orElseThrow().payload());
                            test.assertValueEqual(stored.result().winner(), player.getUUID(), "SQLite round and terminal retain the same actual winner after normal close");
                        } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                    });
                    player.teleportTo(test.getLevel(), test.absoluteVec(new Vec3(1, 2, 1)).x,
                            test.absoluteVec(new Vec3(1, 2, 1)).y, test.absoluteVec(new Vec3(1, 2, 1)).z, Set.of(), 0, 0, true);
                    server.getPlayerList().remove(player);
                    closing[0] = bundle[0].saveAndClose().toCompletableFuture();
                })
                .thenWaitUntil(() -> {
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    bundle[0].tick();
                    if (checked[0].isCompletedExceptionally()) checked[0].join();
                    if (closing[0].isCompletedExceptionally()) closing[0].join();
                    test.assertTrue(checked[0].isDone() && closing[0].isDone(), "Stored terminal verification and native world release complete");
                })
                .thenSucceed();
    }

    private static CombatEvent fixtureDragonEvent(UUID round, UUID dragon, CombatOrigin origin, boolean lethal) {
        return new CombatEvent(round, UUID.randomUUID(), UUID.randomUUID(), 0, dragon, Optional.of(origin),
                CombatEvent.classify(round, Optional.of(origin)), "minecraft:player_attack", Optional.of(origin.actor().playerId()),
                Optional.of(origin.actor().playerId()), 1, lethal, System.nanoTime());
    }

    @GameTest(maxTicks=100)
    public void terminalCaptureFinishesItsBatchThenSealsNativeCombat(GameTestHelper test) {
        var level = test.getLevel(); var server = level.getServer(); var player = test.makeMockServerPlayerInLevel();
        var round = UUID.randomUUID(); var events = new ArrayList<CombatEvent>();
        var frozen = new boolean[]{false}; var terminalBatch = new UUID[1]; var closed = new ArrayList<UUID>();
        var access = new P1CombatRuntime.RoundAccess() {
            @Override public Optional<UUID> roundOf(ServerLevel any) { return any == level ? Optional.of(round) : Optional.empty(); }
            @Override public Optional<CombatActor> actor(UUID id, UUID playerId) {
                return playerId.equals(player.getUUID()) ? Optional.of(new CombatActor(playerId, ParticipantRole.HUMAN_ACTIVE, 1, 0)) : Optional.empty();
            }
            @Override public boolean mayDamage(ServerLevel any, UUID batch) {
                return !frozen[0] || batch != null && batch.equals(terminalBatch[0]);
            }
            @Override public boolean competitionFrozen(ServerLevel any) { return frozen[0]; }
            @Override public void batchClosed(UUID id, UUID batch) {
                test.assertTrue(P1CombatRuntime.currentBatch(level).isEmpty(), "Terminal is dispatched after its entire native batch exits");
                closed.add(batch);
            }
        };
        var dragon = new EnderDragon(EntityTypes.ENDER_DRAGON, level);
        dragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN); dragon.setHealth(.1f);
        var zombie = new net.minecraft.world.entity.monster.zombie.Zombie(EntityTypes.ZOMBIE, level);
        try (var fixture = dev.hardrush.gametest.RuntimeFixtures.combat(server, access, event -> {
            events.add(event);
            if (event.victimId().equals(dragon.getUUID()) && event.lethal()) { frozen[0] = true; terminalBatch[0] = event.batchId(); }
        })) {
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.setPermanentlyInvulnerable(false);
            player.setInvulnerableTime(0);
            player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
            test.assertTrue(player.connection.hasClientLoaded(), "Native client-loaded admission is complete before the fixture applies damage");
            // A native dragon death occurs midway through this explosion batch; later victims must still be processed.
            try (var batch = P1CombatRuntime.explosion(level, null)) {
                dragon.hurt(level, dragon.head, level.damageSources().explosion(null, null), 100);
                test.assertTrue(frozen[0] && closed.isEmpty(), "Irreversible dragon death freezes admission before the batch has closed");
                float before = player.getHealth();
                test.assertTrue(player.hurtServer(level, level.damageSources().explosion(null, null), 1), "Same terminal batch can still hurt its human victim");
                test.assertTrue(player.getHealth() < before, "Human damage after the dragon was actually applied");
                test.assertTrue(!P1CombatRuntime.rejectDamage(level), "Inherited terminal batch remains admitted until scope exit");
            }
            test.assertValueEqual(closed, List.of(terminalBatch[0]), "The terminal batch closes once despite nested damage scopes");
            float before = player.getHealth();
            test.assertTrue(!player.hurtServer(level, level.damageSources().generic(), 100), "New environment damage is rejected during celebration");
            test.assertValueEqual(player.getHealth(), before, "Rejected damage cannot change human health");
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            player.getInventory().setSelectedSlot(0);
            test.assertTrue(!dev.hardrush.p1.player.P1PlayerProfiles.isLocked(player), "Celebration does not impose the login movement lock");
            player.connection.handlePlayerAction(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                    net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS,
                    player.blockPosition(), Direction.UP, 0));
            test.assertTrue(player.getInventory().getItem(0).is(Items.DIAMOND) && player.getInventory().getItem(0).getCount() == 3,
                    "An admitted player's actual drop packet is rejected by the celebration gate");
            var item = new net.minecraft.world.entity.item.ItemEntity(level, player.getX(), player.getY(), player.getZ(),
                    new ItemStack(Items.LAPIS_LAZULI, 2));
            item.setNoPickUpDelay();
            item.playerTouch(player);
            test.assertTrue(!player.getInventory().contains(stack -> stack.is(Items.LAPIS_LAZULI))
                            && !item.isRemoved() && item.getItem().getCount() == 2,
                    "Native collision pickup cannot transfer an item or consume its entity during celebration");
            var orb = new net.minecraft.world.entity.ExperienceOrb(level, player.getX(), player.getY(), player.getZ(), 7);
            player.takeXpDelay = 0;
            int experienceBefore = player.totalExperience;
            orb.playerTouch(player);
            test.assertTrue(player.totalExperience == experienceBefore && !orb.isRemoved() && orb.getValue() == 7,
                    "Native experience pickup cannot change experience or consume its orb during celebration");
            float zombieBefore = zombie.getHealth();
            player.attack(zombie);
            test.assertValueEqual(zombie.getHealth(), zombieBefore, "Native melee is sealed before its combat side effects");
            var arrow = new ImpactArrow(level, player);
            arrow.setRemainingFireTicks(100);
            arrow.impact(new net.minecraft.world.phys.EntityHitResult(zombie));
            test.assertValueEqual(zombie.getHealth(), zombieBefore, "Existing arrows cannot damage after the terminal batch");
            test.assertTrue(!zombie.isOnFire(), "A rejected flaming arrow cannot ignite its target");
            var crystal = new EndCrystal(EntityTypes.END_CRYSTAL, level);
            test.assertTrue(!crystal.hurtServer(level, level.damageSources().playerAttack(player), 1) && !crystal.isRemoved(),
                    "A crystal is sealed before native destruction and explosion");
            var pos = test.absolutePos(new BlockPos(1, 2, 1));
            var state = Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 1);
            level.setBlock(pos, state, 2);
            var result = state.useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            test.assertTrue(result == net.minecraft.world.InteractionResult.FAIL && level.getBlockState(pos).is(Blocks.RESPAWN_ANCHOR),
                    "Frozen anchor interaction cannot destroy the anchor or start another explosion");
            test.assertTrue(!P1CombatRuntime.competitionFrozen(server.getLevel(Level.NETHER)), "Unmanaged dimensions retain native combat");
            frozen[0] = false;
            item.playerTouch(player);
            test.assertTrue(item.isRemoved() && player.getInventory().contains(stack -> stack.is(Items.LAPIS_LAZULI)),
                    "The same native item fixture resumes ordinary pickup when competitive play is admitted");
            orb.playerTouch(player);
            test.assertTrue(orb.isRemoved() && player.totalExperience == experienceBefore + 7,
                    "The same native orb fixture resumes ordinary experience pickup when competitive play is admitted");
        } finally { server.getPlayerList().remove(player); }
        test.succeed();
    }

    private static final class ImpactArrow extends Arrow {
        ImpactArrow(ServerLevel level, net.minecraft.server.level.ServerPlayer player) {
            super(level, player, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        }
        void impact(net.minecraft.world.phys.EntityHitResult hit) { super.onHitEntity(hit); }
    }

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
        try (var fixture = dev.hardrush.gametest.RuntimeFixtures.combat(server, access, events::add)) {
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
        try (var fixture = dev.hardrush.gametest.RuntimeFixtures.combat(server, access, events::add)) {
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
                    try (var fixture = dev.hardrush.gametest.RuntimeFixtures.combat(server, access, events::add)) {
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
