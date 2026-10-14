package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.PauseReason;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.player.ProfileJournal;
import dev.hardrush.p1.player.ProfileTagged;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Server-only fixtures; no graphical clients, screenshots or computer-use interaction. */
public final class P1ProfileGameTest {
    @GameTest(maxTicks = 100)
    public void shutdownRejectsAProfileWriteFailureBeforeItsMainThreadCallback(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) { test.succeed(); return; }
        var server = test.getLevel().getServer();
        var player = test.makeMockServerPlayerInLevel();
        var position = Vec3.atBottomCenterOf(test.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)));
        var root = server.getWorldPath(LevelResource.ROOT).resolve("hardrush/profile-tests/" + UUID.randomUUID());
        var blockedPlayerDirectory = root.resolve(player.getUUID().toString());
        try {
            java.nio.file.Files.createDirectories(root);
            java.nio.file.Files.writeString(blockedPlayerDirectory, "retain-this-failed-profile-fixture");
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        var profiles = new P1PlayerProfiles(server, root, test::getLevel, () -> position);
        try {
            profiles.onLogin(player);
            profiles.tick(round(UUID.randomUUID(), 1, player, GameMode.NORMAL));
            // The same server-thread call enters the shutdown barrier before the worker's failure callback can run.
            test.assertTrue(profiles.failure(player.getUUID()).isEmpty(), "Main-thread failure callback has not run before shutdown starts");
            boolean refused = false;
            try { profiles.close(); }
            catch (java.io.IOException expected) { refused = expected.getMessage().contains("后台写入失败"); }
            test.assertTrue(refused, "A drained failed archive write forbids a normal-stop success even before its callback runs");
            test.assertValueEqual(java.nio.file.Files.readString(blockedPlayerDirectory), "retain-this-failed-profile-fixture",
                    "A failed player directory remains intact for recovery");
        } catch (Exception error) { throw new IllegalStateException(error); }
        finally {
            ((ProfileTagged) player).hardrush$isolated(false);
            server.getPlayerList().remove(player);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 1000)
    public void celebrationReconnectRestoresOneSuspensionAndClosesTheOldContainer(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) { test.succeed(); return; }
        var server = test.getLevel().getServer();
        var player = test.makeMockServerPlayerInLevel();
        var lobby = Vec3.atBottomCenterOf(test.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)));
        var position = Vec3.atBottomCenterOf(test.absolutePos(new net.minecraft.core.BlockPos(4, 2, 4)));
        var root = server.getWorldPath(LevelResource.ROOT).resolve("hardrush/profile-tests/" + UUID.randomUUID());
        var profiles = new P1PlayerProfiles(server, root, test::getLevel, () -> lobby);
        var initial = round(UUID.randomUUID(), 1, player, GameMode.NORMAL);
        var celebrating = new RoundSnapshot(initial.roundId(), initial.sequence(), initial.seed(), initial.version(), initial.rules(),
                RoundPhase.CELEBRATING, PauseReason.SETTLED, 123_000_000_000L, 500_000_000_000L,
                initial.participants(), false, false, new dev.hardrush.core.round.RoundResult(
                        dev.hardrush.core.round.RoundResult.Kind.COMPLETED, player.getUUID(), "可信首龙攻击"));
        var shared = new net.minecraft.world.SimpleContainer(27);
        shared.setItem(0, new ItemStack(Items.EMERALD, 5));
        profiles.onLogin(player);
        profiles.tick(initial);
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(profiles.isReady(player.getUUID(), initial), "Initial profile ready"))
                .thenExecute(() -> {
                    test.assertTrue(!profiles.admitCelebration(player, celebrating, test.getLevel(), position, level -> level == test.getLevel()),
                            "A fresh lobby entrant cannot be admitted to a finished round");
                    test.assertTrue(profiles.admit(player, initial, test.getLevel(), position, level -> level == test.getLevel()), "Human entered during the live race");
                    player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                    player.containerMenu = net.minecraft.world.inventory.ChestMenu.threeRows(42, player.getInventory(), shared);
                    player.containerMenu.setCarried(new ItemStack(Items.GOLD_INGOT, 2));
                    profiles.returnToLobby(player);
                    test.assertTrue(player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty(),
                            "Suspension closes the old container synchronously before world evacuation");
                    test.assertTrue(shared.getItem(0).is(Items.EMERALD) && shared.getItem(0).getCount() == 5,
                            "Shared chest contents remain in their old world rather than entering the player snapshot");
                    profiles.tick(celebrating);
                })
                .thenWaitUntil(() -> test.assertTrue(profiles.isReady(player.getUUID(), celebrating), "Same-round celebration suspension is durable"))
                .thenExecute(() -> {
                    test.assertTrue(profiles.admitCelebration(player, celebrating, test.getLevel(), lobby, level -> level == test.getLevel()),
                            "An existing active human may restore to inspect celebration results");
                    test.assertTrue(!P1PlayerProfiles.isLocked(player) && player.position().distanceTo(position) < .01,
                            "Celebration restoration unlocks movement at the original same-round position");
                    test.assertTrue(player.getInventory().getItem(0).is(Items.DIAMOND) && player.getInventory().getItem(0).getCount() == 3,
                            "One same-round human inventory is restored");
                    test.assertTrue(player.inventoryMenu.getCarried().is(Items.GOLD_INGOT) && player.inventoryMenu.getCarried().getCount() == 2,
                            "The captured cursor stack is restored once after its old menu was closed");
                    test.assertValueEqual(celebrating.challengeNanos(), 123_000_000_000L, "Profile restoration does not mutate settled race time");
                    profiles.returnToLobby(player);
                    profiles.tick(celebrating);
                })
                .thenWaitUntil(() -> test.assertTrue(profiles.isReady(player.getUUID(), celebrating), "Second suspension is durable"))
                .thenExecute(() -> {
                    test.assertTrue(profiles.admitCelebration(player, celebrating, test.getLevel(), lobby, level -> level == test.getLevel()), "Repeated celebration reentry stays in this life");
                    test.assertTrue(player.inventoryMenu.getCarried().getCount() == 2, "Repeated suspension cannot duplicate the cursor item");
                    try { profiles.close(); } catch (Exception error) { throw new IllegalStateException(error); }
                    server.getPlayerList().remove(player);
                }).thenSucceed();
    }

    @GameTest(maxTicks = 1200)
    public void resetSuspensionReconnectAndThreeOfflineRounds(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) { test.succeed(); return; }
        var server = test.getLevel().getServer();
        var player = test.makeMockServerPlayerInLevel();
        var lobbyPosition = Vec3.atBottomCenterOf(test.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)));
        var spawnPosition = Vec3.atBottomCenterOf(test.absolutePos(new net.minecraft.core.BlockPos(4, 2, 4)));
        var root = server.getWorldPath(LevelResource.ROOT).resolve("hardrush/profile-tests/" + UUID.randomUUID());
        var profiles = new P1PlayerProfiles(server, root, test::getLevel, () -> lobbyPosition);
        var first = round(UUID.randomUUID(), 1, player, GameMode.NORMAL);
        var fourth = round(UUID.randomUUID(), 4, player, GameMode.NORMAL);
        var progress = server.getAdvancements().get(Identifier.parse("minecraft:story/mine_stone"));
        var criterion = progress.value().criteria().keySet().iterator().next();
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 7));
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        player.getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND, 9));
        player.inventoryMenu.setCarried(new ItemStack(Items.GOLD_INGOT, 2));
        player.inventoryMenu.getCraftSlots().setItem(0, new ItemStack(Items.OAK_LOG, 3));
        player.giveExperiencePoints(100);
        player.getFoodData().setFoodLevel(4);
        player.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.6);
        player.getAdvancements().award(progress, criterion);
        player.awardRecipes(server.getRecipeManager().getRecipes());
        player.awardStat(Stats.CUSTOM.get(Stats.JUMP), 11);
        profiles.onLogin(player);
        test.assertTrue(P1PlayerProfiles.isLocked(player), "Isolation opens before any asynchronous file result");
        test.assertTrue(!P1PlayerProfiles.allowVanillaSave(player), "Unsealed state cannot overwrite vanilla files with lobby coordinates");
        player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS, player.blockPosition(), Direction.UP, 0));
        test.assertTrue(player.getInventory().getItem(0).is(Items.EMERALD), "An old item cannot be dropped during archive preparation");
        profiles.tick(first);
        var firstFailure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        test.startSequence()
                .thenWaitUntil(await(firstFailure, () -> test.assertTrue(profiles.isReady(player.getUUID(), first), "Archive/reset journal verified: " + profiles.failure(player.getUUID()))))
                .thenExecute(guarded(firstFailure, () -> {
                    test.assertTrue(player.getInventory().isEmpty() && player.getEnderChestInventory().isEmpty(), "Cross-round inventory and ender storage cleared");
                    test.assertTrue(player.inventoryMenu.getCarried().isEmpty() && player.inventoryMenu.getCraftSlots().isEmpty(), "Cursor and crafting slots cleared");
                    test.assertValueEqual(player.totalExperience, 0, "Cross-round experience cleared");
                    test.assertTrue(player.getActiveEffects().isEmpty() && !player.getAbilities().mayfly && !player.getAbilities().flying, "Effects and flight privileges reset");
                    test.assertTrue(Math.abs(player.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue() - .1) < .00001,
                            "Human base movement matches vanilla float default");
                    test.assertValueEqual(player.getFoodData().getFoodLevel(), 20, "Human hunger restored");
                    test.assertTrue(!player.getAdvancements().getOrStartProgress(progress).hasProgress(), "Cross-round advancement cache cleared");
                    test.assertTrue(player.getRecipeBook().pack().known().isEmpty() && player.getRecipeBook().pack().highlight().isEmpty(), "Recipe and notification caches cleared");
                    test.assertValueEqual(player.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP)), 11, "Cumulative vanilla statistics retained");
                    test.assertTrue(!profiles.admit(player, round(first.roundId(), 1, player, GameMode.HARDCORE), test.getLevel(), spawnPosition, level -> level == test.getLevel()), "P2 hardcore identity cannot enter P1");
                    test.assertTrue(profiles.admit(player, first, test.getLevel(), spawnPosition, level -> level == test.getLevel()), "Committed normal human enters only after profile verification");
                    test.assertTrue(!P1PlayerProfiles.isLocked(player), "Admitted participant is unlocked");
                    player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                    player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 5));
                    player.giveExperiencePoints(42);
                    player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(test.getLevel().dimension(), player.blockPosition(), 0, 0), false), false);
                    player.getAdvancements().award(progress, criterion);
                    test.assertTrue(!((ProfileTagged) player).hardrush$isolated(), "Admitted profile remains active before suspension: " + profiles.diagnostic(player.getUUID()));
                    profiles.returnToLobby(player);
                    profiles.returnToLobby(player);
                    profiles.tick(first);
                }))
                .thenWaitUntil(await(firstFailure, () -> test.assertTrue(profiles.isReady(player.getUUID(), first), "Same-generation suspension is durable")))
                .thenExecute(guarded(firstFailure, () -> {
                    try {
                        var stored = new ProfileJournal(root).suspended(player.getUUID(), new ProfileJournal.Generation(first.roundId(), 1)).orElseThrow();
                        var snapshot = net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(stored), net.minecraft.nbt.NbtAccounter.defaultQuota());
                        test.assertTrue(snapshot.getListOrEmpty("Inventory").toString().contains("minecraft:diamond"),
                                "Durable same-round snapshot includes the current inventory: " + snapshot.getListOrEmpty("Inventory")
                                + "; fresh=" + snapshot.getBooleanOr("hardrush_fresh_profile", true)
                                + "; stored-id=" + snapshot.getStringOr("hardrush_suspension_id", "")
                                + "; live-id=" + ((ProfileTagged) player).hardrush$suspensionId()
                                + "; " + profiles.diagnostic(player.getUUID()));
                    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                    test.assertTrue(profiles.admit(player, first, test.getLevel(), lobbyPosition, level -> level == test.getLevel()), "Same-round reentry restores suspension");
                    test.assertTrue(player.getInventory().getItem(0).is(Items.DIAMOND) && player.getInventory().getItem(0).getCount() == 3, "Same-round inventory survives repeated lobby calls");
                    test.assertTrue(player.getEnderChestInventory().getItem(0).is(Items.EMERALD), "Same-round ender chest survives");
                    test.assertValueEqual(player.totalExperience, 42, "Same-round experience is preserved without duplicate advancement rewards");
                    test.assertTrue(player.getRespawnConfig() != null && player.position().distanceTo(spawnPosition) < .01, "Same-round bed configuration and original position survive");
                    test.assertTrue(player.getAdvancements().getOrStartProgress(progress).isDone(), "Same-round exact advancement state restored");
                    profiles.onDisconnect(player);
                    profiles.onLogin(player);
                    profiles.tick(fourth);
                }))
                .thenWaitUntil(await(firstFailure, () -> test.assertTrue(profiles.isReady(player.getUUID(), fourth), "Offline player converts directly from round 1 to round 4: " + profiles.failure(player.getUUID()))))
                .thenExecute(guarded(firstFailure, () -> {
                    test.assertTrue(player.getInventory().isEmpty() && player.getEnderChestInventory().isEmpty(), "No old item survives three offline rounds");
                    test.assertTrue(player.getRespawnConfig() == null && player.getActiveEffects().isEmpty(), "Old spawn and effects cannot leak");
                    test.assertTrue(!player.getAdvancements().getOrStartProgress(progress).hasProgress(), "No old advancement leaks");
                    test.assertValueEqual(((ProfileTagged) player).hardrush$generation().orElseThrow(),
                            new ProfileJournal.Generation(fourth.roundId(), 4), "Persisted player marker uses the new generation");
                    try {
                        var archive = new ProfileJournal(root).archived(player.getUUID(), java.util.Optional.of(new ProfileJournal.Generation(first.roundId(), 1)));
                        test.assertTrue(archive.length > 0, "Old first-round player state is sealed for recovery");
                        profiles.close();
                    } catch (Exception error) { throw new IllegalStateException(error); }
                    ((ProfileTagged) player).hardrush$isolated(false);
                    server.getPlayerList().remove(player);
                })).thenSucceed();
    }

    @GameTest(maxTicks = 100)
    public void loginCaptureBeforeConnectionHasNoNetworkingDependency(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) { test.succeed(); return; }
        var server = test.getLevel().getServer();
        var player = new ServerPlayer(server, test.getLevel(), new GameProfile(UUID.randomUUID(), "profile-login-test"), ClientInformation.createDefault());
        var position = Vec3.atBottomCenterOf(test.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)));
        var profiles = new P1PlayerProfiles(server, server.getWorldPath(LevelResource.ROOT)
                .resolve("hardrush/profile-tests/" + UUID.randomUUID()), test::getLevel, () -> position);
        try {
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
            player.inventoryMenu.setCarried(new ItemStack(Items.GOLD_INGOT, 3));
            profiles.onLogin(player);
            test.assertTrue(player.connection == null, "Fixture exercises the actual pre-connection stage");
            test.assertTrue(((ProfileTagged) player).hardrush$isolated(), "Connectionless login is already gated");
            test.assertTrue(player.position().distanceTo(position) < .01, "Login packet will initially use the safe lobby location");
            test.assertTrue(player.getInventory().getItem(0).is(Items.DIAMOND), "Old state remains intact until its archive is sealed");
            test.assertTrue(player.inventoryMenu.getCarried().is(Items.GOLD_INGOT), "Connectionless capture defers menu listeners until onConnected rather than sending recipe packets");
            profiles.close();
        } catch (Exception error) { throw new IllegalStateException(error); }
        test.succeed();
    }

    @GameTest(maxTicks = 1000)
    public void suspendedStopRestoresLatestStateInsteadOfOlderVanillaFile(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) { test.succeed(); return; }
        var server = test.getLevel().getServer();
        var player = test.makeMockServerPlayerInLevel();
        var position = Vec3.atBottomCenterOf(test.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2)));
        var root = server.getWorldPath(LevelResource.ROOT).resolve("hardrush/profile-tests/" + UUID.randomUUID());
        var service = new P1PlayerProfiles[]{new P1PlayerProfiles(server, root, test::getLevel, () -> position)};
        var round = round(UUID.randomUUID(), 1, player, GameMode.NORMAL);
        var oldVanilla = new net.minecraft.nbt.CompoundTag[1];
        service[0].onLogin(player);
        service[0].tick(round);
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(service[0].isReady(player.getUUID(), round), "Initial profile verified"))
                .thenExecute(() -> {
                    test.assertTrue(service[0].admit(player, round, test.getLevel(), position, level -> level == test.getLevel()), "Initial committed identity admitted");
                    player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                    var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, player.registryAccess());
                    player.saveWithoutId(output);
                    oldVanilla[0] = output.buildResult().copy();
                    player.getInventory().setItem(1, new ItemStack(Items.IRON_INGOT, 7));
                    service[0].returnToLobby(player);
                })
                .thenWaitUntil(() -> test.assertTrue(service[0].isReady(player.getUUID(), round), "Latest suspension captured before stop"))
                .thenExecute(() -> {
                    try { service[0].close(); } catch (Exception error) { throw new IllegalStateException(error); }
                    // Models a vanilla playerdata checkpoint from before the lobby save was permitted.
                    player.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, player.registryAccess(), oldVanilla[0]));
                    test.assertTrue(player.getInventory().getItem(1).isEmpty(), "Fixture loaded an older vanilla inventory");
                    service[0] = new P1PlayerProfiles(server, root, test::getLevel, () -> position);
                    service[0].onLogin(player);
                    service[0].tick(round);
                })
                .thenWaitUntil(() -> test.assertTrue(service[0].isReady(player.getUUID(), round), "Persisted suspension wins over stale vanilla marker: " + service[0].failure(player.getUUID())))
                .thenExecute(() -> {
                    test.assertTrue(service[0].admit(player, round, test.getLevel(), position, level -> level == test.getLevel()), "Reopened normal identity restores durable snapshot");
                    test.assertTrue(player.getInventory().getItem(1).is(Items.IRON_INGOT) && player.getInventory().getItem(1).getCount() == 7, "Latest items survive a normal stop while isolated");
                    try { service[0].close(); } catch (Exception error) { throw new IllegalStateException(error); }
                    server.getPlayerList().remove(player);
                }).thenSucceed();
    }

    @GameTest(maxTicks = 1000)
    public void normalRespawnWaitsForCommittedLifeAndKeepsVanillaDestination(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) { test.succeed(); return; }
        var server = test.getLevel().getServer();
        var player = new ServerPlayer[]{test.makeMockServerPlayerInLevel()};
        var position = Vec3.atBottomCenterOf(test.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2)));
        var profiles = new P1PlayerProfiles(server, server.getWorldPath(LevelResource.ROOT)
                .resolve("hardrush/profile-tests/" + UUID.randomUUID()), test::getLevel, () -> position);
        var initial = round(UUID.randomUUID(), 1, player[0], GameMode.NORMAL);
        var destination = new Vec3[1];
        profiles.onLogin(player[0]);
        profiles.tick(initial);
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(profiles.isReady(player[0].getUUID(), initial), "Initial archive verified"))
                .thenExecute(() -> {
                    test.assertTrue(profiles.admit(player[0], initial, test.getLevel(), position, level -> level == test.getLevel()), "Initial human admitted");
                    player[0].getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 6));
                    test.setBlock(2, 1, 2, net.minecraft.world.level.block.Blocks.STONE);
                    player[0].setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(
                            test.getLevel().dimension(), test.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2)), 0, 0), true), false);
                    var previous = player[0];
                    player[0] = server.getPlayerList().respawn(previous, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                    destination[0] = player[0].position();
                    profiles.onRespawn(previous, player[0], life(initial, ParticipantRole.HUMAN_RESPAWNING, 1, false));
                    test.assertTrue(P1PlayerProfiles.isLocked(player[0]), "Native respawn remains isolated before replacement life commits");
                })
                .thenWaitUntil(() -> test.assertTrue(profiles.isReady(player[0].getUUID(), initial), "Native newborn suspension is durable"))
                .thenExecute(() -> {
                    test.assertTrue(!profiles.confirmRespawn(player[0], life(initial, ParticipantRole.HUMAN_RESPAWNING, 1, false)), "Uncommitted respawn role cannot unlock");
                    test.assertTrue(!profiles.confirmRespawn(player[0], life(initial, ParticipantRole.HUMAN_ACTIVE, 1, false)), "An old life callback cannot unlock the replacement");
                    test.assertTrue(profiles.confirmRespawn(player[0], life(initial, ParticipantRole.HUMAN_ACTIVE, 2, false)), "Committed replacement life unlocks before online time is recorded");
                    test.assertTrue(!P1PlayerProfiles.isLocked(player[0]), "Replacement unlocks only after durable identity");
                    test.assertTrue(player[0].position().distanceTo(destination[0]) < .001, "Confirmation leaves the vanilla bed/anchor destination intact");
                    test.assertTrue(player[0].getEnderChestInventory().getItem(0).is(Items.EMERALD)
                            && player[0].getEnderChestInventory().getItem(0).getCount() == 6, "Ordinary death keeps this round's ender storage");
                    try { profiles.close(); } catch (Exception error) { throw new IllegalStateException(error); }
                    server.getPlayerList().remove(player[0]);
                }).thenSucceed();
    }

    private static RoundSnapshot life(RoundSnapshot original, ParticipantRole role, int life, boolean online) {
        var source = original.participants().values().iterator().next();
        var participant = new Participant(source.id(), source.name(), role, online, false, 0, 0, life, false);
        return new RoundSnapshot(original.roundId(), original.sequence(), original.seed(), original.version(), original.rules(),
                original.phase(), online ? PauseReason.NONE : PauseReason.NO_ONLINE_HUMANS, 0, 0,
                Map.of(participant.id(), participant), false, false, null);
    }

    @GameTest(maxTicks = 1000)
    public void deadHumanReconnectAllowsOnlyNativeRespawnThenWaitsForNewLife(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) { test.succeed(); return; }
        var server = test.getLevel().getServer();
        var player = new ServerPlayer[]{test.makeMockServerPlayerInLevel()};
        var position = Vec3.atBottomCenterOf(test.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2)));
        var profiles = new P1PlayerProfiles(server, server.getWorldPath(LevelResource.ROOT)
                .resolve("hardrush/profile-tests/" + UUID.randomUUID()), test::getLevel, () -> position);
        var initial = round(UUID.randomUUID(), 1, player[0], GameMode.NORMAL);
        var dead = life(initial, ParticipantRole.HUMAN_RESPAWNING, 1, false);
        profiles.onLogin(player[0]);
        profiles.tick(initial);
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(profiles.isReady(player[0].getUUID(), initial), "Initial profile verified"))
                .thenExecute(() -> {
                    test.assertTrue(profiles.admit(player[0], initial, test.getLevel(), position, level -> level == test.getLevel()), "Initial committed human admitted");
                    player[0].getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 4));
                    test.setBlock(2, 1, 2, net.minecraft.world.level.block.Blocks.STONE);
                    player[0].setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(
                            test.getLevel().dimension(), test.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2)), 0, 0), true), false);
                    player[0].setHealth(0);
                    player[0].die(player[0].damageSources().genericKill());
                    profiles.onDisconnect(player[0]);
                    profiles.onLogin(player[0]);
                    profiles.tick(dead);
                })
                .thenWaitUntil(() -> test.assertTrue(profiles.isReady(player[0].getUUID(), dead), "Reconnected dead profile verified"))
                .thenExecute(() -> {
                    test.assertTrue(!P1PlayerProfiles.isRespawnAllowed(player[0]), "A login cannot respawn before committed dead identity is checked");
                    test.assertTrue(profiles.permitRespawn(player[0], dead, level -> level == test.getLevel()), "Committed dead human regains this round's respawn context");
                    test.assertTrue(P1PlayerProfiles.isLocked(player[0]) && P1PlayerProfiles.isRespawnAllowed(player[0]), "Only respawn is permitted; gameplay remains locked");
                    var previous = player[0];
                    previous.connection.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                            net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    player[0] = previous.connection.player;
                    test.assertTrue(player[0] != previous, "The permitted packet follows vanilla's actual replacement path");
                    profiles.onRespawn(previous, player[0], dead);
                })
                .thenWaitUntil(() -> test.assertTrue(profiles.isReady(player[0].getUUID(), dead), "Reconnected player's replacement snapshot is durable"))
                .thenExecute(() -> {
                    test.assertTrue(P1PlayerProfiles.isLocked(player[0]) && !P1PlayerProfiles.isRespawnAllowed(player[0]), "Newborn cannot request a second respawn while its identity commits");
                    test.assertTrue(profiles.confirmRespawn(player[0], life(initial, ParticipantRole.HUMAN_ACTIVE, 2, false)), "Committed next life unlocks the native replacement");
                    test.assertTrue(player[0].getEnderChestInventory().getItem(0).is(Items.EMERALD)
                            && ((ProfileTagged) player[0]).hardrush$lifeSequence() == 2, "Same-round storage and authoritative life survive the death-screen reconnect");
                    try { profiles.close(); } catch (Exception error) { throw new IllegalStateException(error); }
                    server.getPlayerList().remove(player[0]);
                }).thenSucceed();
    }

    @GameTest(maxTicks = 1000)
    public void persistedNativeNewbornFinishesExistingLifeWithoutRespawningAgain(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) { test.succeed(); return; }
        var server = test.getLevel().getServer();
        var player = new ServerPlayer[]{test.makeMockServerPlayerInLevel()};
        var position = Vec3.atBottomCenterOf(test.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2)));
        var root = server.getWorldPath(LevelResource.ROOT).resolve("hardrush/profile-tests/" + UUID.randomUUID());
        var profiles = new P1PlayerProfiles[]{new P1PlayerProfiles(server, root, test::getLevel, () -> position)};
        var initial = round(UUID.randomUUID(), 1, player[0], GameMode.NORMAL);
        var dead = life(initial, ParticipantRole.HUMAN_RESPAWNING, 1, false);
        var bornPosition = new Vec3[1];
        profiles[0].onLogin(player[0]);
        profiles[0].tick(initial);
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(profiles[0].isReady(player[0].getUUID(), initial), "Initial profile verified"))
                .thenExecute(() -> {
                    test.assertTrue(profiles[0].admit(player[0], initial, test.getLevel(), position, level -> level == test.getLevel()), "Initial human admitted");
                    var previous = player[0];
                    player[0] = server.getPlayerList().respawn(previous, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                    bornPosition[0] = player[0].position();
                    profiles[0].onRespawn(previous, player[0], dead);
                })
                .thenWaitUntil(() -> test.assertTrue(profiles[0].isReady(player[0].getUUID(), dead), "Native newborn stamped and persisted"))
                .thenExecute(() -> {
                    try { profiles[0].close(); } catch (Exception error) { throw new IllegalStateException(error); }
                    profiles[0] = new P1PlayerProfiles(server, root, test::getLevel, () -> position);
                    profiles[0].onLogin(player[0]);
                    profiles[0].tick(dead);
                })
                .thenWaitUntil(() -> test.assertTrue(profiles[0].isReady(player[0].getUUID(), dead), "Saved newborn reopened against the older durable dead role"))
                .thenExecute(() -> {
                    test.assertTrue(profiles[0].permitRespawn(player[0], dead, level -> level == test.getLevel()), "Stamped newborn can resume its pending identity commit");
                    test.assertTrue(profiles[0].respawnCommitPending(player[0], dead) && !P1PlayerProfiles.isRespawnAllowed(player[0]), "Existing life needs one durable commit rather than a second native respawn");
                    test.assertTrue(profiles[0].confirmRespawn(player[0], life(initial, ParticipantRole.HUMAN_ACTIVE, 2, false)), "Existing second life is confirmed");
                    test.assertValueEqual(((ProfileTagged) player[0]).hardrush$lifeSequence(), 2, "Restart does not allocate a third life");
                    test.assertTrue(player[0].position().distanceTo(bornPosition[0]) < .01, "Existing vanilla newborn position survives recovery");
                    try { profiles[0].close(); } catch (Exception error) { throw new IllegalStateException(error); }
                    server.getPlayerList().remove(player[0]);
                }).thenSucceed();
    }

    @GameTest(maxTicks = 1000)
    public void preConnectionFallbackRetainsSavedDimensionPositionAndRotation(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) { test.succeed(); return; }
        var server = test.getLevel().getServer();
        var identity = UUID.randomUUID();
        var profile = new GameProfile(identity, "profile-pending-world");
        var player = new ServerPlayer(server, test.getLevel(), profile, ClientInformation.createDefault());
        var root = server.getWorldPath(LevelResource.ROOT).resolve("hardrush/profile-tests/" + UUID.randomUUID());
        var round = round(UUID.randomUUID(), 1, player, GameMode.NORMAL);
        var generation = new ProfileJournal.Generation(round.roundId(), 1);
        var suspensionId = UUID.randomUUID().toString();
        var savedPosition = Vec3.atBottomCenterOf(test.absolutePos(new net.minecraft.core.BlockPos(4, 3, 4)));
        var lobbyPosition = Vec3.atBottomCenterOf(test.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)));
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        ((ProfileTagged) player).hardrush$generation(generation);
        ((ProfileTagged) player).hardrush$lifeSequence(1);
        ((ProfileTagged) player).hardrush$suspensionId(suspensionId);
        player.setPos(savedPosition);
        player.setYRot(73);
        player.setXRot(14);
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, player.registryAccess());
        player.saveWithoutId(output);
        var saved = output.buildResult().copy();
        saved.putString("Dimension", "hardrush:profile_pending_world");
        var seed = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                var journal = new ProfileJournal(root);
                journal.prepare(identity, java.util.Optional.empty(), generation, new byte[]{1});
                journal.advance(identity, generation, ProfileJournal.Phase.PREPARED, ProfileJournal.Phase.APPLIED);
                journal.advance(identity, generation, ProfileJournal.Phase.APPLIED, ProfileJournal.Phase.VERIFIED);
                var bytes = new java.io.ByteArrayOutputStream();
                net.minecraft.nbt.NbtIo.writeCompressed(saved, bytes);
                journal.suspend(identity, generation, bytes.toByteArray());
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        });
        var profiles = new P1PlayerProfiles(server, root, test::getLevel, () -> lobbyPosition);
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(seed.isDone(), "Previous generation's verified file fixture prepared"))
                .thenExecute(() -> {
                    seed.join();
                    test.assertTrue(server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                            Identifier.parse("hardrush:profile_pending_world"))) == null, "Saved round dimension has not reopened yet");
                    player.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, player.registryAccess(), saved));
                    // Models PrepareSpawnTask's safe-level fallback before placeNewPlayer.
                    player.setServerLevel(test.getLevel());
                    player.setPos(lobbyPosition);
                    player.setYRot(0);
                    player.setXRot(0);
                    profiles.onLogin(player);
                    var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
                    new io.netty.channel.embedded.EmbeddedChannel(connection);
                    P1PlayerProfiles.runUnmanagedFixture(() -> server.getPlayerList().placeNewPlayer(connection, player,
                            net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false)));
                    ((ProfileTagged) player).hardrush$isolated(true);
                    profiles.onConnected(player);
                    profiles.tick(round);
                })
                .thenWaitUntil(() -> test.assertTrue(profiles.isReady(identity, round), "Login state sealed while the saved dimension is still unopened"))
                .thenExecute(() -> {
                    try {
                        var payload = new ProfileJournal(root).suspended(identity, generation).orElseThrow();
                        var captured = net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(payload), net.minecraft.nbt.NbtAccounter.defaultQuota());
                        test.assertValueEqual(captured.getStringOr("Dimension", ""), "hardrush:profile_pending_world", "Lobby fallback cannot overwrite the saved round dimension");
                        var position = captured.getListOrEmpty("Pos");
                        test.assertTrue(Math.abs(position.getDoubleOr(0, 0) - savedPosition.x) < .001
                                && Math.abs(position.getDoubleOr(1, 0) - savedPosition.y) < .001
                                && Math.abs(position.getDoubleOr(2, 0) - savedPosition.z) < .001, "Lobby fallback cannot overwrite the saved round coordinates");
                        test.assertValueEqual(captured.getListOrEmpty("Rotation").getFloatOr(0, 0), 73f, "Saved yaw preserved");
                        test.assertTrue(captured.getListOrEmpty("Inventory").toString().contains("minecraft:diamond"), "Selective coordinate recovery preserves current loaded inventory");
                        profiles.close();
                    } catch (Exception error) { throw new IllegalStateException(error); }
                    ((ProfileTagged) player).hardrush$isolated(false);
                    server.getPlayerList().remove(player);
                }).thenSucceed();
    }

    private static RoundSnapshot round(UUID roundId, long sequence, ServerPlayer player, GameMode mode) {
        var participant = new Participant(player.getUUID(), player.getScoreboardName(), ParticipantRole.HUMAN_ACTIVE,
                true, false, 0, 0, 1, false);
        return new RoundSnapshot(roundId, sequence, 17, 1, RulesConfig.defaults().withMode(mode), RoundPhase.RUNNING,
                PauseReason.NONE, 0, 0, Map.of(player.getUUID(), participant), false, false, null);
    }

    private static Runnable guarded(java.util.concurrent.atomic.AtomicReference<RuntimeException> failure, Runnable action) {
        return () -> {
            if (failure.get() != null) throw failure.get();
            try { action.run(); }
            catch (RuntimeException error) { failure.compareAndSet(null, error); throw failure.get(); }
        };
    }

    private static Runnable await(java.util.concurrent.atomic.AtomicReference<RuntimeException> failure, Runnable condition) {
        return () -> {
            if (failure.get() != null) throw failure.get();
            condition.run();
        };
    }
}
