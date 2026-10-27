package dev.hardrush.gametest;

import dev.hardrush.development.world.DevelopmentWorlds;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.clock.PackedClockStates;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.levelgen.feature.EndPodiumFeature;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;

/** Runs against vanilla server code and real round levels; mock connections are not client acceptance. */
public final class HardRushWorldBoundaryGameTest {
    @GameTest(maxTicks = 1200)
    public void roundLocationPredicatesPreserveCustomKeys(GameTestHelper test) {
        for (var semantic : java.util.List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            ServerLevel level = roundLevel(test, semantic);
            test.assertTrue(LocationPredicate.Builder.inDimension(semantic).build().matches(level, 0, 100, 0), "Vanilla location dimension matches round");
            test.assertTrue(LocationPredicate.Builder.inDimension(level.dimension()).build().matches(level, 0, 100, 0), "Explicit custom dimension remains supported");
            for (var other : java.util.List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                if (!other.equals(semantic)) test.assertTrue(!LocationPredicate.Builder.inDimension(other).build().matches(level, 0, 100, 0), "Wrong vanilla dimension does not match");
            }
        }
        var lobby = test.getLevel().getServer().overworld();
        test.assertTrue(LocationPredicate.Builder.inDimension(Level.OVERWORLD).build().matches(lobby, 0, 100, 0), "Vanilla location predicate is unchanged");
        test.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void roundClockAdvancesOncePerTick(GameTestHelper test) {
        ServerLevel overworld = roundLevel(test, Level.OVERWORLD);
        long initialTime = overworld.getOverworldClockTime();
        int initialTick = test.getLevel().getServer().getTickCount();
        test.runAfterDelay(5, () -> {
            long elapsed = test.getLevel().getServer().getTickCount() - initialTick;
            test.assertValueEqual(overworld.getOverworldClockTime() - initialTime, elapsed, "Shared clock advances once, not three times");
            test.assertValueEqual(roundLevel(test, Level.NETHER).getOverworldClockTime(), overworld.getOverworldClockTime(), "Nether time stays synchronized");
            test.succeed();
        });
    }

    @GameTest(maxTicks = 1200)
    public void pearlsAndMountedEntitiesStayInRound(GameTestHelper test) {
        ServerPlayer player = test.makeMockServerPlayerInLevel();
        Entity vehicle = null;
        try {
            for (var semantic : java.util.List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                ServerLevel level = roundLevel(test, semantic);
                Vec3 landing = new Vec3(80.5, semantic.equals(Level.NETHER) ? 100 : 200, 80.5);
                move(player, level, landing.add(5, 0, 0));
                var pearl = new ImpactPearl(level, player);
                pearl.snapTo(landing);
                pearl.setOldPosAndRot();
                pearl.impact();
                test.assertTrue(player.level() == level && player.position().distanceTo(landing) < 0.01, "Pearl lands in the current round dimension");
            }
            ServerLevel overworld = roundLevel(test, Level.OVERWORLD);
            ServerLevel end = roundLevel(test, Level.END);
            move(player, overworld, new Vec3(80.5, 200, 80.5));
            vehicle = EntityTypes.PIG.create(overworld, EntitySpawnReason.COMMAND);
            vehicle.snapTo(player.position());
            overworld.addFreshEntity(vehicle);
            test.assertTrue(player.startRiding(vehicle, true, false), "Player mounts a vanilla vehicle");
            var transition = ((Portal) Blocks.END_PORTAL).getPortalDestination(overworld, vehicle, vehicle.blockPosition());
            vehicle = vehicle.teleport(transition);
            test.assertTrue(vehicle != null && vehicle.level() == end && player.level() == end, "Mounted End transfer carries player to round End");
            test.assertTrue(player.getVehicle() == vehicle, "Passenger relationship survives cross-dimension transfer");
            player.stopRiding();
            // A pearl thrown before its owner changes dimension still returns to its own round world.
            move(player, overworld, new Vec3(80.5, 200, 80.5));
            var oldPearl = new ImpactPearl(overworld, player);
            oldPearl.snapTo(new Vec3(84.5, 200, 80.5));
            oldPearl.setOldPosAndRot();
            move(player, end, new Vec3(100.5, 80, 0.5));
            oldPearl.impact();
            test.assertTrue(player.level() == overworld && DevelopmentWorlds.owns(player.level()), "Late pearl stays within the same round");
        } finally {
            player.stopRiding();
            remove(player);
            if (vehicle != null) vehicle.discard();
        }
        test.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void sameUuidReconnectRestoresRoundState(GameTestHelper test) {
        ServerPlayer player = test.makeMockServerPlayerInLevel();
        GameProfile profile = player.getGameProfile();
        ServerLevel nether = roundLevel(test, Level.NETHER);
        Vec3 position = new Vec3(96.5, 100, 96.5);
        try {
            move(player, nether, position);
            spawnAt(player, nether, new BlockPos(96, 100, 96));
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            var advancement = test.getLevel().getServer().getAdvancements().get(Identifier.parse("minecraft:story/enter_the_nether"));
            player.getAdvancements().award(advancement, advancement.value().criteria().keySet().iterator().next());
            remove(player);
        } finally {
            if (!player.isRemoved()) remove(player);
        }
        var server = test.getLevel().getServer();
        // 26.3 loads player data during configuration, before PlayerList.placeNewPlayer.
        var prepare = new PrepareSpawnTask(server, new NameAndId(profile));
        prepare.start(packet -> {});
        test.startSequence().thenWaitUntil(() -> test.assertTrue(prepare.tick(), "Native login spawn preparation is ready"))
                .thenExecute(() -> {
                    var cookie = CommonListenerCookie.createInitial(profile, false);
                    var connection = new Connection(PacketFlow.SERVERBOUND);
                    var channel = new EmbeddedChannel(connection);
                    var reconnectFixture = new ServerPlayer[1];
                    dev.hardrush.gametest.UnmanagedProfileFixtures.run(() ->
                            reconnectFixture[0] = prepare.spawnPlayer(connection, cookie));
                    ServerPlayer reconnected = reconnectFixture[0];
                    try {
                        test.assertTrue(reconnected.level() == nether && reconnected.position().distanceTo(position) < 0.01, "Same UUID loads the persisted round location");
                        test.assertTrue(reconnected.getInventory().getItem(0).is(Items.DIAMOND)
                                && reconnected.getInventory().getItem(0).getCount() == 3, "Same-round inventory is preserved");
                        test.assertTrue(reconnected.getRespawnConfig().respawnData().dimension().equals(nether.dimension()), "Respawn dimension survives reconnect");
                        assertAdvancement(test, reconnected, "story/enter_the_nether");
                    } finally {
                        remove(reconnected);
                        channel.finishAndReleaseAll();
                        prepare.close();
                    }
                }).thenSucceed();
    }

    /** Low health and sitting pose are fixtures; player attack, death, exit and crystal respawn are vanilla. */
    @GameTest(maxTicks = 1800)
    public void ordinaryDragonDeathAndCrystalRespawnStayInRound(GameTestHelper test) {
        ServerLevel end = roundLevel(test, Level.END);
        ServerPlayer player = test.makeMockServerPlayerInLevel();
        move(player, end, new Vec3(0.5, 128, 0.5));
        var originalFight = end.getDragonFight();
        BlockPos portal = new BlockPos(0, 64, 0);
        var fight = new EnderDragonFight(false, false, false, java.util.Optional.empty(), 0,
                java.util.Optional.empty(), java.util.Optional.of(portal), java.util.List.of(), java.util.List.of());
        fight.init(end, end.getSeed(), BlockPos.ZERO);
        end.setDragonFight(fight);
        new EndPodiumFeature(false).place(end, end.getChunkSource().getGenerator(), end.getRandom(), portal);
        // GameTest accelerates ticks and has no real chunk-acknowledging client.
        // This vanilla test hook changes only the arena-loaded precondition.
        fight.skipArenaLoadedCheck();
        var lobbyFight = test.getLevel().getServer().getLevel(Level.END).getDragonFight();
        var lobbyDragon = lobbyFight.dragonUUID();
        end.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(0, 0), 3);
        EnderDragon[] firstDragon = new EnderDragon[1];
        test.startSequence().thenWaitUntil(() -> {
            test.assertTrue(fight.dragonUUID() != null && end.getEntity(fight.dragonUUID()) instanceof EnderDragon,
                    "Native fight creates the first dragon");
        }).thenExecute(() -> {
            firstDragon[0] = (EnderDragon) end.getEntity(fight.dragonUUID());
            test.assertTrue(!fight.hasPreviouslyKilledDragon(), "First dragon has no prior kill");
            test.assertValueEqual(firstDragon[0].getHealth(), 200.0F, "Native first dragon starts at full health");
            firstDragon[0].snapTo(0.5, 68, 0.5, 0, 0);
            firstDragon[0].setOldPosAndRot();
            firstDragon[0].getPhaseManager().setPhase(EnderDragonPhase.SITTING_SCANNING);
            // Even a newly equipped sword's ordinary cooldown-scaled attack can finish this fixture.
            firstDragon[0].setHealth(0.1F);
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        }).thenIdle(5).thenExecute(() -> {
            test.assertValueEqual(player.getAttribute(Attributes.ATTACK_DAMAGE).getBaseValue(), 1.0,
                    "Fixture preserves ordinary player base attack");
            System.out.println("HardRush low-health fixture head=" + firstDragon[0].head.position()
                    + "; body=" + firstDragon[0].position());
            // Uses Player.attack, not /kill, direct damage, or setDragonKilled.
            player.attack(firstDragon[0].head);
            test.assertValueEqual(firstDragon[0].getHealth(), 0.0F, "Ordinary sword attack is lethal");
        }).thenIdle(20).thenExecute(() -> {
            test.assertTrue(firstDragon[0].dragonDeathTime > 0 && firstDragon[0].dragonDeathTime < 200,
                    "Native death animation is in progress");
            test.assertTrue(!firstDragon[0].isRemoved() && !fight.hasPreviouslyKilledDragon(),
                    "Fight does not finish before the death animation");
            test.assertTrue(exitBlock(end, portal) == null, "Exit stays inactive during the death animation");
        }).thenWaitUntil(() -> {
            test.assertTrue(firstDragon[0].isRemoved() && fight.hasPreviouslyKilledDragon(),
                    "Native death animation finishes and records the first kill");
            test.assertTrue(exitBlock(end, portal) != null, "Native first kill creates an active exit portal");
        }).thenExecute(() -> {
            var state = EnderDragonFight.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, fight)
                    .getOrThrow().getAsJsonObject();
            test.assertTrue(state.get("dragon_killed").getAsBoolean()
                    && state.get("previously_killed").getAsBoolean(), "Dragon fight serializes both kill flags");
            var reloaded = EnderDragonFight.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, state).getOrThrow();
            test.assertTrue(reloaded.hasPreviouslyKilledDragon()
                    && java.util.Objects.equals(reloaded.dragonUUID(), firstDragon[0].getUUID()),
                    "Serialized fight preserves first kill and dragon UUID");
            // Mock connection cannot validate the credits screen or client collision/input.
            // The clean vanilla client separately covers those integration boundaries.
            player.seenCredits = true;
            var returning = ((Portal) Blocks.END_PORTAL).getPortalDestination(end, player, exitBlock(end, portal));
            test.assertTrue(returning != null && returning.newLevel() == roundLevel(test, Level.OVERWORLD),
                    "Naturally created portal routes the player to the same round overworld");
            player.teleport(returning);
            test.assertTrue(player.level() == roundLevel(test, Level.OVERWORLD), "Exit transition returns within round");
            move(player, end, new Vec3(0.5, 128, 0.5));
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                var crystal = EntityTypes.END_CRYSTAL.create(end, EntitySpawnReason.COMMAND);
                crystal.snapTo(Vec3.atBottomCenterOf(portal.above().relative(direction, 3)));
                test.assertTrue(end.addFreshEntity(crystal), "Respawn crystal is added");
            }
        }).thenWaitUntil(() -> {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                test.assertTrue(!end.getEntitiesOfClass(EndCrystal.class, new AABB(portal.above().relative(direction, 3))).isEmpty(), "Respawn crystal is visible to vanilla lookup");
            }
        }).thenExecute(() -> {
            fight.tryRespawn();
            var state = EnderDragonFight.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, fight).getOrThrow().getAsJsonObject();
            test.assertTrue(state.has("respawn_stage"), "Four crystals begin vanilla respawn: " + state);
        }).thenWaitUntil(() -> {
            test.assertTrue(fight.dragonUUID() != null
                    && end.getEntity(fight.dragonUUID()) instanceof EnderDragon, "Four crystals create a new round dragon; state="
                    + EnderDragonFight.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, fight).getOrThrow()
                    + "; playerAlive=" + player.isAlive() + "; players=" + end.players().size());
        }).thenExecute(() -> {
            try {
                test.assertTrue(fight.hasPreviouslyKilledDragon(), "Prior kill state survives respawn");
                test.assertTrue(java.util.Objects.equals(lobbyFight.dragonUUID(), lobbyDragon), "Lobby End fight is unchanged");
                int dragons = 0;
                for (Entity entity : end.getAllEntities()) if (entity instanceof EnderDragon && entity.isAlive()) dragons++;
                test.assertValueEqual(dragons, 1, "Exactly one authoritative round dragon");
            } finally {
                remove(player);
                if (fight.dragonUUID() != null && end.getEntity(fight.dragonUUID()) != null) end.getEntity(fight.dragonUUID()).discard();
                end.setDragonFight(originalFight);
                end.getChunkSource().removeTicketWithRadius(TicketType.PORTAL, new ChunkPos(0, 0), 3);
            }
        }).thenSucceed();
    }

    @GameTest(maxTicks = 1200)
    public void timeAndWeatherCommandsPersistOnlyRoundState(GameTestHelper test) throws java.io.IOException {
        var overworld = roundLevel(test, Level.OVERWORLD);
        var server = overworld.getServer();
        var clock = overworld.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
        var clockState = overworld.clockManager().packState().clocks().get(clock);
        var lobbyTime = server.overworld().getOverworldClockTime();
        var weather = overworld.getWeatherData();
        var roundWeather = weatherSnapshot(weather);
        var lobbyWeather = weatherSnapshot(server.getWeatherData());
        var commands = server.getCommands();
        var source = server.createCommandSourceStack().withLevel(overworld).withSuppressedOutput();
        try {
            commands.performPrefixedCommand(source, "time set 17000");
            commands.performPrefixedCommand(source, "weather thunder 600s");
            test.assertValueEqual(overworld.getOverworldClockTime(), 17000L, "Time command targets the source round");
            test.assertValueEqual(server.overworld().getOverworldClockTime(), lobbyTime, "Round command leaves lobby time unchanged");
            test.assertTrue(weather.isRaining() && weather.isThundering(), "Round weather command takes effect");
            test.assertValueEqual(weatherSnapshot(server.getWeatherData()), lobbyWeather, "Round weather command leaves lobby unchanged");
            overworld.getDataStorage().saveAndJoin();
            var folder = DimensionType.getStorageFolder(overworld.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("data");
            var ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
            var clockFile = NbtIo.readCompressed(folder.resolve("hardrush/p0_clocks.dat"), NbtAccounter.unlimitedHeap());
            var reloadedClock = PackedClockStates.CODEC.parse(ops, clockFile.getCompoundOrEmpty("data")).getOrThrow();
            test.assertValueEqual(reloadedClock.clocks().get(clock).totalTicks(), 17000L, "Clock persisted to owned dimension file");
            var weatherFile = NbtIo.readCompressed(folder.resolve("minecraft/weather.dat"), NbtAccounter.unlimitedHeap());
            var reloadedWeather = WeatherData.CODEC.parse(ops, weatherFile.getCompoundOrEmpty("data")).getOrThrow();
            test.assertValueEqual(weatherSnapshot(reloadedWeather), weatherSnapshot(weather), "Weather persisted to owned dimension file");
        } finally {
            overworld.clockManager().setTotalTicks(clock, clockState.totalTicks());
            restoreWeather(weather, roundWeather);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void roundClockAndWeatherAreIsolated(GameTestHelper test) {
        ServerLevel overworld = roundLevel(test, Level.OVERWORLD);
        ServerLevel nether = roundLevel(test, Level.NETHER);
        ServerLevel end = roundLevel(test, Level.END);
        ServerLevel lobby = test.getLevel().getServer().overworld();
        test.assertTrue(overworld.clockManager() != lobby.clockManager(), "Round clock is independent of lobby");
        test.assertTrue(nether.clockManager() == overworld.clockManager()
                && end.clockManager() == overworld.clockManager(), "Round dimensions share one vanilla day cycle");
        var clock = overworld.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
        long lobbyTime = lobby.clockManager().getInstance(clock).totalTicks();
        long roundTime = overworld.clockManager().getInstance(clock).totalTicks();
        try {
            overworld.clockManager().setTotalTicks(clock, 17000);
            test.assertValueEqual(lobby.clockManager().getInstance(clock).totalTicks(), lobbyTime, "Round time does not alter lobby");
            test.assertValueEqual(nether.clockManager().getInstance(clock).totalTicks(), 17000L, "Nether uses round clock");
            DevelopmentWorlds.create(test.getLevel().getServer(), 12345);
            test.assertValueEqual(overworld.clockManager().getInstance(clock).totalTicks(), 17000L, "Idempotent create preserves time");
        } finally {
            overworld.clockManager().setTotalTicks(clock, roundTime);
        }
        var weather = overworld.getWeatherData();
        test.assertTrue(weather != lobby.getWeatherData(), "Weather SavedData belongs to round level");
        boolean previousRain = weather.isRaining();
        boolean lobbyRain = lobby.getWeatherData().isRaining();
        try {
            weather.setRaining(!lobbyRain);
            test.assertValueEqual(lobby.getWeatherData().isRaining(), lobbyRain, "Round rain does not alter lobby");
        } finally {
            weather.setRaining(previousRain);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void vanillaDimensionAdvancementsInRound(GameTestHelper test) {
        ServerPlayer player = test.makeMockServerPlayerInLevel();
        try {
            move(player, roundLevel(test, Level.OVERWORLD), new Vec3(0.5, 200, 0.5));
            move(player, roundLevel(test, Level.NETHER), new Vec3(0.5, 100, 0.5));
            assertAdvancement(test, player, "story/enter_the_nether");
            move(player, roundLevel(test, Level.OVERWORLD), new Vec3(7101.5, 200, 0.5));
            assertAdvancement(test, player, "nether/fast_travel");
            move(player, roundLevel(test, Level.END), new Vec3(100.5, 80, 0.5));
            assertAdvancement(test, player, "story/enter_the_end");
        } finally {
            remove(player);
        }
        test.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void bedsAnchorsAndDeathStayInRound(GameTestHelper test) {
        ServerLevel overworld = roundLevel(test, Level.OVERWORLD);
        ServerLevel nether = roundLevel(test, Level.NETHER);
        ServerLevel end = roundLevel(test, Level.END);
        BlockPos bed = new BlockPos(48, 200, 48);
        BlockPos anchor = new BlockPos(48, 100, 48);
        platform(overworld, bed);
        platform(nether, anchor);
        overworld.setBlockAndUpdate(bed, Blocks.BED.pick(DyeColor.RED).defaultBlockState()
                .setValue(AbstractBedBlock.FACING, Direction.EAST).setValue(AbstractBedBlock.PART, BedPart.HEAD));
        overworld.setBlockAndUpdate(bed.west(), Blocks.BED.pick(DyeColor.RED).defaultBlockState()
                .setValue(AbstractBedBlock.FACING, Direction.EAST).setValue(AbstractBedBlock.PART, BedPart.FOOT));
        nether.setBlockAndUpdate(anchor, Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 2));
        ServerPlayer player = test.makeMockServerPlayerInLevel();
        try {
            move(player, end, new Vec3(100.5, 80, 0.5));
            spawnAt(player, overworld, bed);
            var bedRespawn = player.findRespawnPositionAndUseSpawnBlock(true, TeleportTransition.DO_NOTHING);
            test.assertTrue(bedRespawn.newLevel() == overworld && !bedRespawn.missingRespawnBlock(), "Valid round bed is honored");
            var endExit = ((Portal) Blocks.END_PORTAL).getPortalDestination(end, player, ServerLevel.END_SPAWN_POINT);
            test.assertTrue(endExit.newLevel() == overworld && endExit.position().equals(bedRespawn.position()), "End exit honors bed");
            obstruct(overworld, bed);
            assertFallback(test, player, overworld, "Obstructed bed");
            unobstruct(overworld, bed);
            overworld.setBlockAndUpdate(bed, Blocks.AIR.defaultBlockState());
            overworld.setBlockAndUpdate(bed.west(), Blocks.AIR.defaultBlockState());
            assertFallback(test, player, overworld, "Broken bed");

            spawnAt(player, nether, anchor);
            var anchorExit = player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
            test.assertTrue(anchorExit.newLevel() == nether && !anchorExit.missingRespawnBlock(), "End exit honors round anchor");
            test.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE), 2, "Exit does not consume anchor");
            var anchorRespawn = player.findRespawnPositionAndUseSpawnBlock(true, TeleportTransition.DO_NOTHING);
            test.assertTrue(anchorRespawn.newLevel() == nether, "Death honors round anchor");
            test.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE), 1, "Death consumes one charge");
            obstruct(nether, anchor);
            assertFallback(test, player, overworld, "Obstructed charged anchor");
            test.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE), 1, "Blocked anchor does not consume charge");
            unobstruct(nether, anchor);
            nether.setBlockAndUpdate(anchor, Blocks.RESPAWN_ANCHOR.defaultBlockState());
            assertFallback(test, player, overworld, "Empty anchor");

            player.setRespawnPosition(null, false);
            player.die(player.damageSources().genericKill());
            player = test.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            test.assertTrue(player.level() == overworld && player.isAlive(), "Actual player death/respawn stays in round");
        } finally {
            remove(player);
            nether.setBlockAndUpdate(anchor, Blocks.AIR.defaultBlockState());
        }
        test.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void foreignSpawnsAndVanillaWorldsAreUnaffected(GameTestHelper test) {
        ServerLevel round = roundLevel(test, Level.END);
        ServerLevel roundOverworld = roundLevel(test, Level.OVERWORLD);
        ServerLevel lobby = test.getLevel().getServer().overworld();
        ServerLevel vanillaNether = test.getLevel().getServer().getLevel(Level.NETHER);
        BlockPos anchor = new BlockPos(64, 100, 64);
        platform(vanillaNether, anchor);
        vanillaNether.setBlockAndUpdate(anchor, Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 2));
        ServerPlayer player = test.makeMockServerPlayerInLevel();
        try {
            move(player, round, new Vec3(100.5, 80, 0.5));
            spawnAt(player, vanillaNether, anchor);
            assertFallback(test, player, roundOverworld, "Foreign anchor");
            test.assertValueEqual(vanillaNether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE), 2, "Foreign anchor charge is untouched");
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(
                    ResourceKey.create(Registries.DIMENSION, Identifier.parse("hardrush:missing_round")), anchor, 0, 0), false), false);
            assertFallback(test, player, roundOverworld, "Missing dimension");

            move(player, lobby, new Vec3(64.5, 200, 64.5));
            spawnAt(player, vanillaNether, anchor);
            var vanillaRespawn = player.findRespawnPositionAndUseSpawnBlock(true, TeleportTransition.DO_NOTHING);
            test.assertTrue(vanillaRespawn.newLevel() == vanillaNether, "Vanilla anchor route unchanged");
            test.assertValueEqual(vanillaNether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE), 1, "Vanilla anchor consumption unchanged");
            move(player, vanillaNether, new Vec3(64.5, 100, 64.5));
            assertAdvancement(test, player, "story/enter_the_nether");
            move(player, test.getLevel().getServer().getLevel(Level.END), new Vec3(100.5, 80, 0.5));
            assertAdvancement(test, player, "story/enter_the_end");
            test.assertTrue(DevelopmentWorlds.portalDestination(lobby, Level.END) == test.getLevel().getServer().getLevel(Level.END), "Vanilla End target unchanged");
            var pig = EntityTypes.PIG.create(test.getLevel().getServer().getLevel(Level.END), EntitySpawnReason.COMMAND);
            var exit = ((Portal) Blocks.END_PORTAL).getPortalDestination(pig.level() instanceof ServerLevel level ? level : null, pig, ServerLevel.END_SPAWN_POINT);
            test.assertTrue(exit.newLevel() == lobby, "Vanilla End portal still returns to vanilla overworld");
        } finally {
            remove(player);
            vanillaNether.setBlockAndUpdate(anchor, Blocks.AIR.defaultBlockState());
        }
        test.succeed();
    }

    private static ServerLevel roundLevel(GameTestHelper test, ResourceKey<Level> semantic) {
        var server = test.getLevel().getServer();
        DevelopmentWorlds.create(server, 12345);
        for (ServerLevel level : server.getAllLevels()) {
            if (DevelopmentWorlds.owns(level) && DevelopmentWorlds.semanticDimension(level).equals(semantic)) return level;
        }
        throw new IllegalStateException("Missing round level: " + semantic);
    }

    private static void move(ServerPlayer player, ServerLevel level, Vec3 position) {
        player.teleport(new TeleportTransition(level, position, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
    }

    private static BlockPos exitBlock(ServerLevel end, BlockPos portal) {
        for (BlockPos pos : BlockPos.betweenClosed(portal.offset(-3, 0, -3), portal.offset(3, 0, 3))) {
            if (end.getBlockState(pos).is(Blocks.END_PORTAL)) return pos.immutable();
        }
        return null;
    }

    private static void spawnAt(ServerPlayer player, ServerLevel level, BlockPos pos) {
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(level.dimension(), pos, 0, 0), false), false);
    }

    private static void assertFallback(GameTestHelper test, ServerPlayer player, ServerLevel overworld, String reason) {
        var transition = player.findRespawnPositionAndUseSpawnBlock(true, TeleportTransition.DO_NOTHING);
        test.assertTrue(transition.newLevel() == overworld, reason + " falls back to round overworld");
        test.assertValueEqual(transition.position(), Vec3.atBottomCenterOf(overworld.getRespawnData().pos()), reason + " uses round spawn");
    }

    private static void assertAdvancement(GameTestHelper test, ServerPlayer player, String id) {
        var advancement = test.getLevel().getServer().getAdvancements().get(Identifier.parse("minecraft:" + id));
        test.assertTrue(advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone(), "Vanilla advancement: " + id);
    }

    private static void platform(ServerLevel level, BlockPos center) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                level.setBlockAndUpdate(center.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 3; y++) level.setBlockAndUpdate(center.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void obstruct(ServerLevel level, BlockPos center) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 0; y <= 2; y++) {
                    BlockPos pos = center.offset(x, y, z);
                    if ((x != 0 || z != 0 || y != 0) && !(level.getBlockState(pos).getBlock() instanceof AbstractBedBlock)) {
                        level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
    }

    private static void unobstruct(ServerLevel level, BlockPos center) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 0; y <= 2; y++) {
                    BlockPos pos = center.offset(x, y, z);
                    if ((x != 0 || z != 0 || y != 0) && !(level.getBlockState(pos).getBlock() instanceof AbstractBedBlock)) {
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    private static void remove(ServerPlayer player) {
        player.level().getServer().getPlayerList().remove(player);
    }

    private static java.util.List<Object> weatherSnapshot(WeatherData weather) {
        return java.util.List.of(weather.getClearWeatherTime(), weather.getRainTime(), weather.getThunderTime(),
                weather.isRaining(), weather.isThundering());
    }

    private static void restoreWeather(WeatherData weather, java.util.List<Object> snapshot) {
        weather.setClearWeatherTime((Integer) snapshot.get(0));
        weather.setRainTime((Integer) snapshot.get(1));
        weather.setThunderTime((Integer) snapshot.get(2));
        weather.setRaining((Boolean) snapshot.get(3));
        weather.setThundering((Boolean) snapshot.get(4));
    }

    private static final class ImpactPearl extends ThrownEnderpearl {
        ImpactPearl(ServerLevel level, ServerPlayer owner) {
            super(level, owner, new ItemStack(Items.ENDER_PEARL));
        }

        void impact() {
            onHit(new BlockHitResult(position(), Direction.UP, blockPosition(), false));
        }
    }
}
