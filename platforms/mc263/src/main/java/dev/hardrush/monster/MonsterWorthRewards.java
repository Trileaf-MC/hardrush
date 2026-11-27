package dev.hardrush.monster;

import dev.hardrush.core.round.MonsterSpoils;
import dev.hardrush.core.round.MonsterWorth;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

/**
 * A monster slain with a player's recent help drops its native form's loot, sprays experience for its worth like the
 * dragon's death, and leaves what {@link MonsterSpoils} rolls for that worth: a book naming whose body it came from,
 * iron and brewing materials. This class only turns the rolled spoils into the world; the amounts are core's. While
 * deployed, the monster's own experience bar shows the worth's level, which is the book's enchanting level.
 * All callbacks run on the server thread.
 */
public final class MonsterWorthRewards {
    /** Small orbs read as a fountain; the dragon's large ones would arrive as a handful. */
    static final int ORB_VALUE = 7;
    static final int WAVES = 10;
    static final int WAVE_INTERVAL_TICKS = 5;
    /** Like the dragon's closing award, the last wave carries a fifth of the total. */
    static final int FINAL_WAVE_PERCENT = 20;

    private record Fountain(ServerLevel level, Vec3 pos, int[] waves, int startTick) {}
    private static final Map<MinecraftServer, List<Fountain>> FOUNTAINS = new IdentityHashMap<>();

    private MonsterWorthRewards() {}

    /**
     * Called from the cancelled native death loot, while the body is still this sortie's. A death no other player took
     * part in, or a creeper's own blast, leaves nothing; the native loot drops whether or not the round has worth.
     */
    public static void slain(ServerPlayer player, DamageSource source) {
        // A creeper spent in its own blast leaves nothing, like a native creeper that explodes. MonsterCreeper.explode
        // marks the body detonating before its fatal hurtServer, so the flag holds while this death loot runs; litBy is
        // put only around a fuse a human lit (explode's forced path and disconnected), and such a death still pays.
        if (MonsterCreeper.detonating(player) && MonsterCreeper.litBy(player).isEmpty()) return;
        if (!MonsterRoles.recentlyHurtByOtherPlayer(player)) return;
        MonsterNativeLoot.drop(player, source);
        var worth = MonsterRoundRuntime.worth(player);
        var rules = MonsterRoundRuntime.rules(player);
        if (worth.isEmpty() || rules.isEmpty() || !(player.level() instanceof ServerLevel level)) return;
        leave(level, player, MonsterSpoils.roll(rules.get(), worth.getAsInt(), bound -> player.getRandom().nextInt(bound)));
    }

    /**
     * Turns rolled spoils into the world at the body: the experience fountain, the signed book, iron and each brewing
     * material as one stack. Public only so GameTests can check the items a known roll leaves.
     */
    public static void leave(ServerLevel level, ServerPlayer player, MonsterSpoils.Spoils spoils) {
        var server = level.getServer();
        if (spoils.experience() > 0) FOUNTAINS.computeIfAbsent(server, ignored -> new ArrayList<>()).add(new Fountain(level,
                player.position(), waves(spoils.experience()), server.getTickCount()));
        if (spoils.bookLevel() > 0) {
            player.spawnAtLocation(level, signedBook(level, player.getScoreboardName(), player.getRandom(), spoils.bookLevel()));
        }
        if (spoils.ironIngots() > 0) player.spawnAtLocation(level, new ItemStack(Items.IRON_INGOT, spoils.ironIngots()));
        spoils.brewing().forEach((material, count) -> player.spawnAtLocation(level, new ItemStack(item(material), count)));
    }

    /** The native item of each brewing material; a switch so a new material cannot be forgotten here. */
    public static Item item(MonsterSpoils.Material material) {
        return switch (material) {
            case NETHER_WART -> Items.NETHER_WART;
            case GLASS_BOTTLE -> Items.GLASS_BOTTLE;
            case MAGMA_CREAM -> Items.MAGMA_CREAM;
            case GLISTERING_MELON_SLICE -> Items.GLISTERING_MELON_SLICE;
            case GOLDEN_CARROT -> Items.GOLDEN_CARROT;
            case RABBIT_FOOT -> Items.RABBIT_FOOT;
            case PUFFERFISH -> Items.PUFFERFISH;
            case FERMENTED_SPIDER_EYE -> Items.FERMENTED_SPIDER_EYE;
            case GHAST_TEAR -> Items.GHAST_TEAR;
            case PHANTOM_MEMBRANE -> Items.PHANTOM_MEMBRANE;
            case BREEZE_ROD -> Items.BREEZE_ROD;
            case GLOWSTONE_DUST -> Items.GLOWSTONE_DUST;
            case REDSTONE -> Items.REDSTONE;
        };
    }

    /** Equal waves share four fifths of the experience; the last wave takes the rest. */
    static int[] waves(int xp) {
        int[] waves = new int[WAVES];
        int each = xp * (100 - FINAL_WAVE_PERCENT) / 100 / (WAVES - 1);
        for (int i = 0; i < WAVES - 1; i++) waves[i] = each;
        waves[WAVES - 1] = xp - each * (WAVES - 1);
        return waves;
    }

    /** Vanilla enchant_with_levels over #on_random_loot, named after the body it came from. */
    public static ItemStack signedBook(ServerLevel level, String owner, RandomSource random, int cost) {
        var options = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(EnchantmentTags.ON_RANDOM_LOOT);
        var book = EnchantmentHelper.enchantItem(random, new ItemStack(Items.BOOK), cost, level.registryAccess(), Optional.of(options));
        book.set(DataComponents.ITEM_NAME, Component.literal("从 " + owner + " 身上找到的书"));
        return book;
    }

    public static void tick(MinecraftServer server) {
        var fountains = FOUNTAINS.get(server);
        if (fountains == null) return;
        int now = server.getTickCount();
        fountains.removeIf(fountain -> {
            int elapsed = now - fountain.startTick;
            if (elapsed % WAVE_INTERVAL_TICKS == 0 && elapsed / WAVE_INTERVAL_TICKS < WAVES
                    && server.getLevel(fountain.level.dimension()) == fountain.level) {
                spray(fountain.level, fountain.pos, fountain.waves[elapsed / WAVE_INTERVAL_TICKS]);
            }
            return elapsed >= (WAVES - 1) * WAVE_INTERVAL_TICKS;
        });
        if (fountains.isEmpty()) FOUNTAINS.remove(server);
    }

    /**
     * Launches each orb from about head height in a random direction, so the wave bursts outward and
     * lands in a ring a few blocks wide before vanilla pulls the orbs toward nearby humans.
     */
    private static void spray(ServerLevel level, Vec3 pos, int xp) {
        var random = level.getRandom();
        for (int left = xp; left > 0; ) {
            int value = Math.min(ORB_VALUE, left);
            left -= value;
            var orb = new ExperienceOrb(level, pos.add(0, 1.0, 0), Vec3.ZERO, value);
            double angle = random.nextDouble() * Math.PI * 2;
            double speed = 0.12 + random.nextDouble() * 0.18;
            orb.setDeltaMovement(Math.cos(angle) * speed, 0.35 + random.nextDouble() * 0.2, Math.sin(angle) * speed);
            level.addFreshEntity(orb);
        }
    }

    /**
     * Mirrors the worth's level onto the monster's own experience bar. Like
     * Player.giveExperienceLevels it chimes on each fifth level, but only the monster hears it, so
     * nearby humans learn nothing.
     */
    static void showWorth(ServerPlayer player) {
        var worth = MonsterRoundRuntime.worth(player);
        var rules = MonsterRoundRuntime.rules(player);
        if (worth.isEmpty() || rules.isEmpty()) return;
        int milliLevels = worth.getAsInt();
        int level = MonsterWorth.level(milliLevels);
        float progress = MonsterWorth.progress(milliLevels);
        if (player.experienceLevel == level && player.experienceProgress == progress) return;
        int before = player.experienceLevel;
        player.totalExperience = MonsterSpoils.experience(rules.get(), milliLevels);
        player.experienceLevel = level;
        player.experienceProgress = progress;
        // ServerPlayer.doTick only resends the bar when the total changes; the fill can move alone.
        player.connection.send(new ClientboundSetExperiencePacket(progress, player.totalExperience, level));
        // Level 0 is the camp's cleared bar: the first deployed tick sets the base level silently.
        if (before > 0 && player.experienceLevel / 5 > before / 5) {
            float volume = player.experienceLevel > 30 ? 1.0F : player.experienceLevel / 30.0F;
            player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP),
                    SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(), volume * 0.75F, 1.0F,
                    player.getRandom().nextLong()));
        }
    }

    public static void stop(MinecraftServer server) { FOUNTAINS.remove(server); }
}
