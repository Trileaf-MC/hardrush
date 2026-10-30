package dev.hardrush.monster;

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
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

/**
 * A monster slain with a player's recent help sprays its worth like the dragon's death, and a
 * worthy one leaves an enchanted book naming whose body it came from. While deployed, the monster's
 * own experience bar shows that worth. All callbacks run on the server thread.
 */
public final class MonsterWorthRewards {
    /** Small orbs read as a fountain; the dragon's large ones would arrive as a handful. */
    static final int ORB_VALUE = 7;
    static final int WAVES = 6;
    static final int WAVE_INTERVAL_TICKS = 5;

    private record Fountain(ServerLevel level, Vec3 pos, int[] waves, int startTick) {}
    private static final Map<MinecraftServer, List<Fountain>> FOUNTAINS = new IdentityHashMap<>();

    private MonsterWorthRewards() {}

    /** Called from the cancelled native death loot, while the body is still this sortie's. */
    public static void slain(ServerPlayer player) {
        if (!MonsterRoles.recentlyHurtByOtherPlayer(player)) return;
        var worth = MonsterRoundRuntime.worth(player);
        var rules = MonsterRoundRuntime.rules(player);
        if (worth.isEmpty() || rules.isEmpty() || !(player.level() instanceof ServerLevel level)) return;
        int xp = worth.getAsInt();
        var server = level.getServer();
        FOUNTAINS.computeIfAbsent(server, ignored -> new ArrayList<>())
                .add(new Fountain(level, player.position(), waves(xp), server.getTickCount()));
        int cost = MonsterWorth.bookCost(rules.get(), xp);
        if (cost > 0) player.spawnAtLocation(level, signedBook(level, player.getScoreboardName(), player.getRandom(), cost));
    }

    /** Splits the worth into equal waves of small orbs; the remainder rides in the last wave. */
    static int[] waves(int xp) {
        int[] waves = new int[WAVES];
        int each = xp / WAVES;
        for (int i = 0; i < WAVES; i++) waves[i] = each;
        waves[WAVES - 1] += xp - each * WAVES;
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

    private static void spray(ServerLevel level, Vec3 pos, int xp) {
        var random = level.getRandom();
        for (int left = xp; left > 0; ) {
            int value = Math.min(ORB_VALUE, left);
            left -= value;
            var orb = new ExperienceOrb(level, pos.add(0, 0.5, 0), Vec3.ZERO, value);
            orb.setDeltaMovement((random.nextDouble() - 0.5) * 0.3, 0.3 + random.nextDouble() * 0.25,
                    (random.nextDouble() - 0.5) * 0.3);
            level.addFreshEntity(orb);
        }
    }

    /**
     * Mirrors the worth onto the monster's own experience bar. Like Player.giveExperienceLevels it
     * chimes on each fifth level, but only the monster hears it, so nearby humans learn nothing.
     */
    static void showWorth(ServerPlayer player) {
        var worth = MonsterRoundRuntime.worth(player);
        if (worth.isEmpty() || player.totalExperience == worth.getAsInt()) return;
        int xp = worth.getAsInt();
        int before = player.experienceLevel;
        player.totalExperience = xp;
        player.experienceLevel = MonsterWorth.level(xp);
        player.experienceProgress = MonsterWorth.progress(xp);
        // Level 0 is the camp's cleared bar: the first deployed tick sets the base silently.
        if (before > 0 && player.experienceLevel / 5 > before / 5) {
            float volume = player.experienceLevel > 30 ? 1.0F : player.experienceLevel / 30.0F;
            player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP),
                    SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(), volume * 0.75F, 1.0F,
                    player.getRandom().nextLong()));
        }
    }

    public static void stop(MinecraftServer server) { FOUNTAINS.remove(server); }
}
