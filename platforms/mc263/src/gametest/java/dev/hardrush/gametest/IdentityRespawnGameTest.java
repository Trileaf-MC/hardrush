package dev.hardrush.gametest;

import dev.hardrush.HardRushProperties;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.PauseReason;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.development.world.DevelopmentWorlds;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.player.ProfileTagged;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Native server death, respawn and reconnect paths; no graphical client or computer-use interaction. */
public final class IdentityRespawnGameTest {
    private static final TicketType LOOT_FIXTURE = new TicketType(TicketType.NO_TIMEOUT,
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

    @GameTest(maxTicks = 1600)
    public void hardcoreNativeDropsPrecedeMonsterLobbyAndUuidReconnectCannotRestoreHumanLife(GameTestHelper test) {
        if (!HardRushProperties.competitionEnabled()) { test.succeed(); return; }
        var overworld = roundLevel(test, Level.OVERWORLD);
        var deathPos = new BlockPos(160, 200, 160);
        platform(overworld, deathPos);
        var fixture = new IdentityFixture(test, overworld, Vec3.atBottomCenterOf(deathPos));
        var first = fixture.round(ParticipantRole.HUMAN_ACTIVE, 1);
        var monster = fixture.round(ParticipantRole.MONSTER_LOBBY, 1);
        var lootArea = new AABB(deathPos).inflate(4);
        var lootChunk = new ChunkPos(deathPos.getX() >> 4, deathPos.getZ() >> 4);
        overworld.getChunkSource().addTicketWithRadius(LOOT_FIXTURE, lootChunk, 2);
        var dropMarker = Component.literal("identity-native-death-" + UUID.randomUUID());
        var staleLivingCheckpoint = new net.minecraft.nbt.CompoundTag[1];
        fixture.profiles.onLogin(fixture.player);
        fixture.profiles.tick(first);
        test.startSequence()
                .thenWaitUntil(() -> fixture.ready(first))
                .thenWaitUntil(() -> test.assertTrue(overworld.areEntitiesActuallyLoadedAndTicking(lootChunk),
                        "Native loot chunk is loaded and ticking before death/drop assertions"))
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
                                    && PlayerProfiles.allowVanillaSave(fixture.player),
                            "The native dead checkpoint remains authoritative while the committed extreme death role is pending");
                    test.assertTrue(!fixture.profiles.enterMonsterLobby(fixture.player, first),
                            "The old committed human identity cannot convert before the extreme death transition commits");
                    test.assertTrue(!fixture.profiles.admit(fixture.player, monster, overworld, fixture.spawn, DevelopmentWorlds::owns),
                            "A committed monster UUID cannot use the ordinary human admission path");
                    var old = fixture.player;
                    test.assertTrue(fixture.profiles.enterMonsterLobby(old, monster), "Committed extreme death is accepted into the monster lobby");
                    fixture.player = old.connection.player;
                    test.assertTrue(fixture.player != old && fixture.player.isAlive(), "Conversion uses vanilla's actual player replacement");
                    test.assertTrue(fixture.player.level() == test.getLevel()
                                    && fixture.player.position().distanceTo(fixture.lobby) < .01,
                            "The replacement goes directly to the monster lobby rather than bed or round spawn");
                    test.assertTrue(PlayerProfiles.isLocked(fixture.player), "A monster in the lobby cannot use human gameplay packets");
                })
                .thenWaitUntil(() -> fixture.ready(monster))
                .thenExecute(() -> {
                    fixture.assertHistory(1);
                    test.assertTrue(fixture.profiles.enterMonsterLobby(fixture.player, monster), "Repeated committed lobby application is idempotent");
                    fixture.reconnectFromLivingCheckpoint(monster, staleLivingCheckpoint[0]);
                })
                .thenWaitUntil(() -> fixture.ready(monster))
                .thenExecute(() -> {
                    test.assertTrue(!fixture.profiles.admit(fixture.player, monster, overworld, fixture.spawn, DevelopmentWorlds::owns),
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

    private static ServerLevel roundLevel(GameTestHelper test, ResourceKey<Level> semantic) {
        var server = test.getLevel().getServer();
        DevelopmentWorlds.create(server, 12345);
        for (var level : server.getAllLevels()) {
            if (DevelopmentWorlds.owns(level) && DevelopmentWorlds.semanticDimension(level).equals(semantic)) return level;
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
        final ServerLevel spawnLevel;
        final Vec3 spawn;
        final Vec3 lobby;
        final Path profileRoot;
        PlayerProfiles profiles;
        final UUID roundId = UUID.randomUUID();
        ServerPlayer player;

        IdentityFixture(GameTestHelper test, ServerLevel spawnLevel, Vec3 spawn) {
            this.test = test;
            this.spawnLevel = spawnLevel;
            this.spawn = spawn;
            spawnLevel.getChunkSource().addTicketWithRadius(LOOT_FIXTURE, new ChunkPos(BlockPos.containing(spawn).getX() >> 4, BlockPos.containing(spawn).getZ() >> 4), 1);
            lobby = Vec3.atBottomCenterOf(test.absolutePos(new BlockPos(1, 2, 1)));
            player = test.makeMockServerPlayerInLevel();
            profileRoot = test.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("hardrush/identity-runtime-tests/" + UUID.randomUUID());
            profiles = new PlayerProfiles(test.getLevel().getServer(), profileRoot, test::getLevel, () -> lobby);
        }

        RoundSnapshot round(ParticipantRole role, int life) {
            var participant = new Participant(player.getUUID(), player.getScoreboardName(), role, false, false,
                    123_000_000_000L, 10_000_000_000L, life, false);
            return new RoundSnapshot(roundId, 1, 12345, 1, RulesConfig.defaults(), RoundPhase.RUNNING,
                    PauseReason.NO_ONLINE_HUMANS, 123_000_000_000L, 0, Map.of(player.getUUID(), participant), false, false, null);
        }

        void ready(RoundSnapshot round) {
            test.assertTrue(dev.hardrush.world.RoundWorldBundle.safeStanding(spawnLevel, BlockPos.containing(spawn)), "Fixture spawn is actually loaded and safe");
            test.assertTrue(profiles.isReady(player.getUUID(), round), "Identity snapshot is durably ready: " + profiles.failure(player.getUUID()));
        }

        void admit(RoundSnapshot round) {
            test.assertTrue(profiles.admit(player, round, spawnLevel, spawn, DevelopmentWorlds::owns), "Committed human identity enters the round");
        }

        void seedHistory() {
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 6));
            var advancement = test.getLevel().getServer().getAdvancements().get(Identifier.parse("minecraft:story/mine_stone"));
            advancement.value().criteria().keySet().forEach(criterion -> player.getAdvancements().award(advancement, criterion));
            player.awardStat(Stats.CUSTOM.get(Stats.JUMP), 11);
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

        private void reconnectFromCheckpoint(RoundSnapshot round, net.minecraft.nbt.CompoundTag saved, boolean reopenService) {
            var server = test.getLevel().getServer();
            var old = player;
            profiles.onDisconnect(old);
            server.getPlayerList().remove(old);
            player = new ServerPlayer(server, test.getLevel(), old.getGameProfile(), TestClients.information());
            test.assertValueEqual(player.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP)), 11,
                    "The replacement native player loads cumulative statistics from its separate UUID file");
            player.load(TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, player.registryAccess(), saved));
            if (reopenService) {
                test.assertTrue(player.isDeadOrDying(), "The native reconnect loads the dead normal-stop checkpoint");
                profiles = new PlayerProfiles(server, profileRoot, test::getLevel, () -> lobby);
            } else {
                test.assertTrue(player.isAlive() && player.getInventory().getItem(0).is(Items.DIAMOND),
                        "The stale vanilla checkpoint still contains the living human and already-dropped inventory");
            }
            // Production quarantine precedes registration, while the loaded Dimension still
            // identifies the round instead of the safe lobby used by native registration.
            profiles.onLogin(player);
            new TestClient(server, player.getGameProfile()).place(player);
            ((ProfileTagged) player).hardrush$isolated(true);
            profiles.onConnected(player);
            profiles.tick(round);
            test.assertTrue(player != old && player.getUUID().equals(old.getUUID()), "Reconnect uses a distinct native object under the same UUID");
        }

        void close() {
            spawnLevel.getChunkSource().removeTicketWithRadius(LOOT_FIXTURE, new ChunkPos(BlockPos.containing(spawn).getX() >> 4, BlockPos.containing(spawn).getZ() >> 4), 1);
            try { profiles.close(); }
            catch (Exception error) { throw new IllegalStateException(error); }
            ((ProfileTagged) player).hardrush$isolated(false);
            player.teleportTo(test.getLevel(), lobby.x, lobby.y, lobby.z, Set.of(), 0, 0, true);
            test.getLevel().getServer().getPlayerList().remove(player);
        }
    }
}
