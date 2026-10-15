package dev.hardrush.gametest;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.PauseReason;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.p0.world.WorldProbe;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.player.ProfileTagged;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Native server death, respawn and reconnect paths; no graphical client or computer-use interaction. */
public final class P2IdentityGameTest {
    private static final TicketType LOOT_FIXTURE = new TicketType(TicketType.NO_TIMEOUT,
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

    @GameTest(maxTicks = 1600)
    public void normalBedRespawnAndBrokenBedFallbackKeepOneHumanHistory(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) { test.succeed(); return; }
        var overworld = roundLevel(test, Level.OVERWORLD);
        var bed = new BlockPos(128, 200, 128);
        platform(overworld, bed);
        var bedState = Blocks.BED.pick(DyeColor.RED).defaultBlockState()
                .setValue(AbstractBedBlock.FACING, Direction.EAST).setValue(AbstractBedBlock.PART, BedPart.HEAD);
        overworld.setBlockAndUpdate(bed, bedState);
        overworld.setBlockAndUpdate(bed.west(), bedState.setValue(AbstractBedBlock.PART, BedPart.FOOT));
        var fixture = new IdentityFixture(test, GameMode.NORMAL, overworld, Vec3.atBottomCenterOf(bed.south(2)));
        var first = fixture.round(ParticipantRole.HUMAN_ACTIVE, 1);
        fixture.profiles.onLogin(fixture.player);
        fixture.profiles.tick(first);
        test.startSequence()
                .thenWaitUntil(() -> fixture.ready(first))
                .thenExecute(() -> {
                    fixture.admit(first);
                    fixture.seedHistory();
                    spawnAt(fixture.player, overworld, bed);
                    fixture.nativeNormalRespawn(fixture.round(ParticipantRole.HUMAN_RESPAWNING, 1));
                    test.assertTrue(fixture.player.level() == overworld && fixture.player.position().distanceTo(Vec3.atBottomCenterOf(bed)) < 4,
                            "A real PlayerList respawn honors the valid round bed");
                })
                .thenWaitUntil(() -> fixture.ready(first))
                .thenExecute(() -> {
                    fixture.confirmNormal(2);
                    overworld.setBlockAndUpdate(bed, Blocks.AIR.defaultBlockState());
                    overworld.setBlockAndUpdate(bed.west(), Blocks.AIR.defaultBlockState());
                    fixture.nativeNormalRespawn(fixture.round(ParticipantRole.HUMAN_RESPAWNING, 2));
                    test.assertTrue(fixture.player.level() == overworld, "A broken bed cannot send a replacement to the vanilla lobby");
                    test.assertTrue(fixture.player.position().distanceTo(Vec3.atBottomCenterOf(overworld.getRespawnData().pos())) < .01,
                            "A broken bed falls back to this round's configured spawn");
                })
                .thenWaitUntil(() -> fixture.ready(first))
                .thenExecute(() -> {
                    fixture.confirmNormal(3);
                    fixture.assertHistory(3);
                    fixture.close();
                }).thenSucceed();
    }

    @GameTest(maxTicks = 1600)
    public void normalAnchorRespawnConsumesOneChargeThenEmptyAnchorReturnsToRound(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) { test.succeed(); return; }
        var nether = roundLevel(test, Level.NETHER);
        var overworld = roundLevel(test, Level.OVERWORLD);
        var anchor = new BlockPos(144, 100, 144);
        platform(nether, anchor);
        nether.setBlockAndUpdate(anchor, Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 1));
        var fixture = new IdentityFixture(test, GameMode.NORMAL, nether, Vec3.atBottomCenterOf(anchor.south(2)));
        var first = fixture.round(ParticipantRole.HUMAN_ACTIVE, 1);
        fixture.profiles.onLogin(fixture.player);
        fixture.profiles.tick(first);
        test.startSequence()
                .thenWaitUntil(() -> fixture.ready(first))
                .thenExecute(() -> {
                    fixture.admit(first);
                    fixture.seedHistory();
                    spawnAt(fixture.player, nether, anchor);
                    fixture.nativeNormalRespawn(fixture.round(ParticipantRole.HUMAN_RESPAWNING, 1));
                    test.assertTrue(fixture.player.level() == nether && fixture.player.position().distanceTo(Vec3.atBottomCenterOf(anchor)) < 4,
                            "A real PlayerList respawn honors the valid round anchor");
                    test.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE), 0,
                            "The native death consumes exactly the anchor's one charge");
                })
                .thenWaitUntil(() -> fixture.ready(first))
                .thenExecute(() -> {
                    fixture.confirmNormal(2);
                    fixture.nativeNormalRespawn(fixture.round(ParticipantRole.HUMAN_RESPAWNING, 2));
                    test.assertTrue(fixture.player.level() == overworld, "An exhausted anchor returns to the same round's overworld");
                    test.assertTrue(fixture.player.position().distanceTo(Vec3.atBottomCenterOf(overworld.getRespawnData().pos())) < .01,
                            "An exhausted anchor uses this round's spawn rather than the global lobby");
                    test.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE), 0,
                            "A failed anchor respawn cannot consume or manufacture another charge");
                })
                .thenWaitUntil(() -> fixture.ready(first))
                .thenExecute(() -> {
                    fixture.confirmNormal(3);
                    fixture.assertHistory(3);
                    fixture.close();
                    nether.setBlockAndUpdate(anchor, Blocks.AIR.defaultBlockState());
                }).thenSucceed();
    }

    @GameTest(maxTicks = 1600)
    public void normalDeathScreenSurvivesCleanStopAndNativeReconnectBeforeRespawn(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) { test.succeed(); return; }
        var overworld = roundLevel(test, Level.OVERWORLD);
        var bed = new BlockPos(176, 200, 176);
        platform(overworld, bed);
        var bedState = Blocks.BED.pick(DyeColor.RED).defaultBlockState()
                .setValue(AbstractBedBlock.FACING, Direction.EAST).setValue(AbstractBedBlock.PART, BedPart.HEAD);
        overworld.setBlockAndUpdate(bed, bedState);
        overworld.setBlockAndUpdate(bed.west(), bedState.setValue(AbstractBedBlock.PART, BedPart.FOOT));
        var fixture = new IdentityFixture(test, GameMode.NORMAL, overworld, Vec3.atBottomCenterOf(bed.south(2)));
        var first = fixture.round(ParticipantRole.HUMAN_ACTIVE, 1);
        var dead = fixture.round(ParticipantRole.HUMAN_RESPAWNING, 1);
        fixture.profiles.onLogin(fixture.player);
        fixture.profiles.tick(first);
        test.startSequence()
                .thenWaitUntil(() -> fixture.ready(first))
                .thenExecute(() -> {
                    fixture.admit(first);
                    fixture.seedHistory();
                    spawnAt(fixture.player, overworld, bed);
                    fixture.player.setHealth(0);
                    fixture.player.die(fixture.player.damageSources().genericKill());
                    fixture.profiles.holdDeath(fixture.player);
                    test.assertTrue(!((ProfileTagged) fixture.player).hardrush$isolated()
                                    && P1PlayerProfiles.allowVanillaSave(fixture.player),
                            "The admitted native death remains saveable rather than selecting the older living suspension");
                    var checkpoint = TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, fixture.player.registryAccess());
                    fixture.player.saveWithoutId(checkpoint);
                    var saved = checkpoint.buildResult();
                    test.assertTrue(saved.getFloatOr("Health", 20) <= 0,
                            "The normal-stop checkpoint contains the actual dead human before native replacement");
                    fixture.restartFromDeadCheckpoint(dead, saved);
                })
                .thenWaitUntil(() -> fixture.ready(dead))
                .thenExecute(() -> {
                    test.assertTrue(!P1PlayerProfiles.isRespawnAllowed(fixture.player), "A reopened death screen cannot bypass committed identity verification");
                    test.assertTrue(fixture.profiles.permitRespawn(fixture.player, dead, WorldProbe::owns),
                            "The reopened service restores the actual native dead checkpoint instead of its older living archive");
                    test.assertTrue(fixture.player.isDeadOrDying() && P1PlayerProfiles.isLocked(fixture.player)
                                    && P1PlayerProfiles.isRespawnAllowed(fixture.player),
                            "The same UUID recovers only a death screen and its permitted native respawn request");
                    var old = fixture.player;
                    old.connection.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                            net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    fixture.player = old.connection.player;
                    test.assertTrue(fixture.player != old && fixture.player.isAlive(), "The verified death-screen packet performs one native replacement");
                    test.assertTrue(fixture.player.level() == overworld && fixture.player.position().distanceTo(Vec3.atBottomCenterOf(bed)) < 4,
                            "Reopening a death screen preserves the round's legitimate bed destination");
                    fixture.profiles.onRespawn(old, fixture.player, dead);
                })
                .thenWaitUntil(() -> fixture.ready(dead))
                .thenExecute(() -> {
                    fixture.confirmNormal(2);
                    fixture.assertHistory(2);
                    fixture.close();
                    overworld.setBlockAndUpdate(bed, Blocks.AIR.defaultBlockState());
                    overworld.setBlockAndUpdate(bed.west(), Blocks.AIR.defaultBlockState());
                }).thenSucceed();
    }

    @GameTest(maxTicks = 1600)
    public void hardcoreNativeDropsPrecedeMonsterLobbyAndUuidReconnectCannotRestoreHumanLife(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) { test.succeed(); return; }
        var overworld = roundLevel(test, Level.OVERWORLD);
        var deathPos = new BlockPos(160, 200, 160);
        platform(overworld, deathPos);
        var fixture = new IdentityFixture(test, GameMode.HARDCORE, overworld, Vec3.atBottomCenterOf(deathPos));
        var first = fixture.round(ParticipantRole.HUMAN_ACTIVE, 1);
        var monster = fixture.round(ParticipantRole.MONSTER_LOBBY, 1);
        var lootArea = new AABB(deathPos).inflate(4);
        var lootChunk = new ChunkPos(deathPos.getX() >> 4, deathPos.getZ() >> 4);
        overworld.getChunkSource().addTicketWithRadius(LOOT_FIXTURE, lootChunk, 2);
        var dropMarker = Component.literal("p2-native-death-" + UUID.randomUUID());
        var staleLivingCheckpoint = new net.minecraft.nbt.CompoundTag[1];
        fixture.profiles.onLogin(fixture.player);
        fixture.profiles.tick(first);
        test.startSequence()
                .thenWaitUntil(() -> fixture.ready(first))
                .thenExecute(() -> {
                    fixture.admit(first);
                    fixture.seedHistory();
                    var diamonds = new ItemStack(Items.DIAMOND, 3);
                    diamonds.set(DataComponents.CUSTOM_NAME, dropMarker);
                    fixture.player.getInventory().setItem(0, diamonds);
                    var checkpoint = TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, fixture.player.registryAccess());
                    fixture.player.saveWithoutId(checkpoint);
                    staleLivingCheckpoint[0] = checkpoint.buildResult();
                    fixture.player.setHealth(0);
                    fixture.player.die(fixture.player.damageSources().genericKill());
                    test.assertValueEqual(diamonds(overworld, lootArea, dropMarker), 3, "Native death drops the human inventory in the round before conversion");
                    test.assertTrue(fixture.player.getInventory().isEmpty(), "Native death emptied carried human inventory");
                    fixture.profiles.holdDeath(fixture.player);
                    test.assertTrue(!((ProfileTagged) fixture.player).hardrush$isolated()
                                    && P1PlayerProfiles.allowVanillaSave(fixture.player),
                            "The native dead checkpoint remains authoritative while the committed extreme death role is pending");
                    test.assertTrue(!fixture.profiles.enterMonsterLobby(fixture.player, first),
                            "The old committed human identity cannot convert before the extreme death transition commits");
                    test.assertTrue(!fixture.profiles.admit(fixture.player, monster, overworld, fixture.spawn, WorldProbe::owns),
                            "A committed monster UUID cannot use the ordinary human admission path");
                    var old = fixture.player;
                    test.assertTrue(fixture.profiles.enterMonsterLobby(old, monster), "Committed extreme death is accepted into the monster lobby");
                    fixture.player = old.connection.player;
                    test.assertTrue(fixture.player != old && fixture.player.isAlive(), "Conversion uses vanilla's actual player replacement");
                    test.assertTrue(fixture.player.level() == test.getLevel()
                                    && fixture.player.position().distanceTo(fixture.lobby) < .01,
                            "The replacement goes directly to the monster lobby rather than bed or round spawn");
                    test.assertTrue(P1PlayerProfiles.isLocked(fixture.player), "A monster in the lobby cannot use human gameplay packets");
                })
                .thenWaitUntil(() -> fixture.ready(monster))
                .thenExecute(() -> {
                    fixture.assertHistory(1);
                    test.assertTrue(fixture.profiles.enterMonsterLobby(fixture.player, monster), "Repeated committed lobby application is idempotent");
                    fixture.reconnectFromLivingCheckpoint(monster, staleLivingCheckpoint[0]);
                })
                .thenWaitUntil(() -> fixture.ready(monster))
                .thenExecute(() -> {
                    test.assertTrue(!fixture.profiles.admit(fixture.player, monster, overworld, fixture.spawn, WorldProbe::owns),
                            "A distinct native player object with the same UUID cannot revive a dead extreme human");
                    test.assertTrue(fixture.profiles.enterMonsterLobby(fixture.player, monster), "The reconnect recovers the committed monster lobby");
                    test.assertTrue(fixture.player.level() == test.getLevel()
                                    && fixture.player.position().distanceTo(fixture.lobby) < .01,
                            "Reconnect remains in the lobby");
                    test.assertTrue(fixture.player.getInventory().isEmpty(), "Reconnect cannot restore or duplicate the dropped human inventory");
                    test.assertValueEqual(diamonds(overworld, lootArea, dropMarker), 3, "Lobby conversion and reconnect leave exactly one native drop set");
                    fixture.assertHistory(1);
                    fixture.close();
                    for (var item : overworld.getEntitiesOfClass(ItemEntity.class, lootArea)) {
                        if (dropMarker.equals(item.getItem().get(DataComponents.CUSTOM_NAME))) item.discard();
                    }
                    overworld.getChunkSource().removeTicketWithRadius(LOOT_FIXTURE, lootChunk, 2);
                }).thenSucceed();
    }

    private static int diamonds(ServerLevel level, AABB area, Component dropMarker) {
        return level.getEntitiesOfClass(ItemEntity.class, area).stream().map(ItemEntity::getItem)
                .filter(item -> item.is(Items.DIAMOND) && dropMarker.equals(item.get(DataComponents.CUSTOM_NAME)))
                .mapToInt(ItemStack::getCount).sum();
    }

    private static void spawnAt(ServerPlayer player, ServerLevel level, BlockPos pos) {
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(level.dimension(), pos, 0, 0), false), false);
    }

    private static ServerLevel roundLevel(GameTestHelper test, ResourceKey<Level> semantic) {
        var server = test.getLevel().getServer();
        WorldProbe.create(server, 12345);
        for (var level : server.getAllLevels()) {
            if (WorldProbe.owns(level) && WorldProbe.semanticDimension(level).equals(semantic)) return level;
        }
        throw new IllegalStateException("Missing round level: " + semantic);
    }

    private static void platform(ServerLevel level, BlockPos center) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                level.setBlockAndUpdate(center.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 3; y++) level.setBlockAndUpdate(center.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static final class IdentityFixture {
        final GameTestHelper test;
        final GameMode mode;
        final ServerLevel spawnLevel;
        final Vec3 spawn;
        final Vec3 lobby;
        final Path profileRoot;
        P1PlayerProfiles profiles;
        final UUID roundId = UUID.randomUUID();
        ServerPlayer player;

        IdentityFixture(GameTestHelper test, GameMode mode, ServerLevel spawnLevel, Vec3 spawn) {
            this.test = test;
            this.mode = mode;
            this.spawnLevel = spawnLevel;
            this.spawn = spawn;
            lobby = Vec3.atBottomCenterOf(test.absolutePos(new BlockPos(1, 2, 1)));
            player = test.makeMockServerPlayerInLevel();
            profileRoot = test.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("hardrush/p2-identity-tests/" + UUID.randomUUID());
            profiles = new P1PlayerProfiles(test.getLevel().getServer(), profileRoot, test::getLevel, () -> lobby);
        }

        RoundSnapshot round(ParticipantRole role, int life) {
            var participant = new Participant(player.getUUID(), player.getScoreboardName(), role, false, false,
                    123_000_000_000L, 10_000_000_000L, life, false);
            return new RoundSnapshot(roundId, 1, 12345, 1, RulesConfig.defaults().withMode(mode), RoundPhase.RUNNING,
                    PauseReason.NO_ONLINE_HUMANS, 123_000_000_000L, 0, Map.of(player.getUUID(), participant), false, false, null);
        }

        void ready(RoundSnapshot round) {
            test.assertTrue(profiles.isReady(player.getUUID(), round), "Identity snapshot is durably ready: " + profiles.failure(player.getUUID()));
        }

        void admit(RoundSnapshot round) {
            test.assertTrue(profiles.admit(player, round, spawnLevel, spawn, WorldProbe::owns), "Committed human identity enters the round");
        }

        void seedHistory() {
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 6));
            var advancement = test.getLevel().getServer().getAdvancements().get(Identifier.parse("minecraft:story/mine_stone"));
            advancement.value().criteria().keySet().forEach(criterion -> player.getAdvancements().award(advancement, criterion));
            player.awardStat(Stats.CUSTOM.get(Stats.JUMP), 11);
        }

        void nativeNormalRespawn(RoundSnapshot dead) {
            var old = player;
            old.setHealth(0);
            old.die(old.damageSources().genericKill());
            profiles.holdDeath(old);
            player = test.getLevel().getServer().getPlayerList().respawn(old, false, Entity.RemovalReason.KILLED);
            profiles.onRespawn(old, player, dead);
            test.assertTrue(P1PlayerProfiles.isLocked(player), "Native replacement remains locked until its next human life commits");
        }

        void confirmNormal(int life) {
            var destination = player.position();
            test.assertTrue(profiles.confirmRespawn(player, round(ParticipantRole.HUMAN_ACTIVE, life)), "Committed next human life unlocks its native replacement");
            test.assertTrue(!P1PlayerProfiles.isLocked(player) && player.position().distanceTo(destination) < .01,
                    "Identity confirmation preserves the native respawn destination");
            assertHistory(life);
        }

        void assertHistory(int life) {
            test.assertValueEqual(((ProfileTagged) player).hardrush$lifeSequence(), life, "Identity preserves the authoritative life sequence");
            test.assertTrue(player.getEnderChestInventory().getItem(0).is(Items.EMERALD)
                            && player.getEnderChestInventory().getItem(0).getCount() == 6,
                    "Native death and identity conversion retain this round's ender storage");
            var advancement = test.getLevel().getServer().getAdvancements().get(Identifier.parse("minecraft:story/mine_stone"));
            test.assertTrue(player.getAdvancements().getOrStartProgress(advancement).isDone(), "Completed round advancement survives identity conversion");
            test.assertValueEqual(player.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP)), 11, "Cumulative vanilla statistics survive identity conversion");
        }

        void reconnectFromLivingCheckpoint(RoundSnapshot round, net.minecraft.nbt.CompoundTag saved) {
            reconnectFromCheckpoint(round, saved, false);
        }

        void restartFromDeadCheckpoint(RoundSnapshot round, net.minecraft.nbt.CompoundTag saved) {
            try { profiles.close(); }
            catch (Exception error) { throw new IllegalStateException(error); }
            reconnectFromCheckpoint(round, saved, true);
        }

        private void reconnectFromCheckpoint(RoundSnapshot round, net.minecraft.nbt.CompoundTag saved, boolean reopenService) {
            var server = test.getLevel().getServer();
            var old = player;
            profiles.onDisconnect(old);
            server.getPlayerList().remove(old);
            player = new ServerPlayer(server, test.getLevel(), old.getGameProfile(), ClientInformation.createDefault());
            test.assertValueEqual(player.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP)), 11,
                    "The replacement native player loads cumulative statistics from its separate UUID file");
            player.load(TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, player.registryAccess(), saved));
            if (reopenService) {
                test.assertTrue(player.isDeadOrDying(), "The native reconnect loads the dead normal-stop checkpoint");
                profiles = new P1PlayerProfiles(server, profileRoot, test::getLevel, () -> lobby);
            } else {
                test.assertTrue(player.isAlive() && player.getInventory().getItem(0).is(Items.DIAMOND),
                        "The stale vanilla checkpoint still contains the living human and already-dropped inventory");
            }
            // Production quarantine precedes registration, while the loaded Dimension still
            // identifies the round instead of the safe lobby used by native registration.
            profiles.onLogin(player);
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            new io.netty.channel.embedded.EmbeddedChannel(connection);
            P1PlayerProfiles.runUnmanagedFixture(() -> server.getPlayerList().placeNewPlayer(connection, player,
                    net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)));
            ((ProfileTagged) player).hardrush$isolated(true);
            profiles.onConnected(player);
            profiles.tick(round);
            test.assertTrue(player != old && player.getUUID().equals(old.getUUID()), "Reconnect uses a distinct native object under the same UUID");
        }

        void close() {
            try { profiles.close(); }
            catch (Exception error) { throw new IllegalStateException(error); }
            ((ProfileTagged) player).hardrush$isolated(false);
            player.teleportTo(test.getLevel(), lobby.x, lobby.y, lobby.z, Set.of(), 0, 0, true);
            test.getLevel().getServer().getPlayerList().remove(player);
        }
    }
}
