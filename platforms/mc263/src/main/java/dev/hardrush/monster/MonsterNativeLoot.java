package dev.hardrush.monster;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jspecify.annotations.Nullable;

/**
 * A monster player slain by a player drops what its current native form would (issue #106): the form's own loot
 * table, settled as a player kill. The parameters copy 26.3 {@code LivingEntity.dropFromLootTable(ServerLevel,
 * DamageSource, boolean, ResourceKey, Consumer)}, with the native model as "this" entity so conditions such as the
 * zombie's {@code is_on_fire} read the form, and looting applies through the attacker as natively. Worth never adds to
 * it. The creeper's music disc pool is conditioned on its {@code attacker} being in {@code #minecraft:skeletons}: a
 * creeper player shot dead by a skeleton player drops none, since that attacker is a {@code ServerPlayer}, while one a
 * native skeleton shot drops a disc as vanilla does.
 */
public final class MonsterNativeLoot {
    private MonsterNativeLoot() {}

    /** Called once the caller knows another player recently hurt {@code player}; items land where the body fell. */
    static void drop(ServerPlayer player, DamageSource source) {
        var model = MonsterRoles.nativeModel(player);
        if (model == null || !(player.level() instanceof ServerLevel level)) return;
        // Vanilla's dropAllDeathLoot rolls the table only when shouldDropLoot, which Monster narrows to this rule alone.
        if (!level.getGameRules().get(GameRules.MOB_DROPS)) return;
        MonsterRoles.syncNativeContext(player);
        // The model never enters a level, so the player's body spawns the items.
        for (var stack : roll(level, player, model, source, player.getLastHurtByPlayer(), model.getLootTableSeed())) {
            player.spawnAtLocation(level, stack);
        }
    }

    /**
     * The native loot of {@code model} for {@code player}'s death, spawning nothing. {@code killer} is the player
     * credited with the kill, or null for a death no player took part in; a seed of 0 draws from the level's random,
     * like a mob that has no loot table seed. Public only so GameTests can roll it with fixed seeds.
     */
    public static List<ItemStack> roll(ServerLevel level, ServerPlayer player, Mob model, DamageSource source,
                                       @Nullable Player killer, long seed) {
        var table = model.getLootTable();
        if (table.isEmpty()) return List.of();
        var builder = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, model)
                .withParameter(LootContextParams.ORIGIN, player.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, source)
                .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, source.getEntity())
                .withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, source.getDirectEntity());
        if (killer != null) builder = builder.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, killer).withLuck(killer.getLuck());
        var params = builder.create(LootContextParamSets.ENTITY);
        var items = new ArrayList<ItemStack>();
        // The same call as vanilla's, which also splits stacks above their native size. A count rolled to zero (rotten
        // flesh's 0–2) comes out as an empty stack, which vanilla's spawnAtLocation silently skips; drop it here.
        level.getServer().reloadableRegistries().getLootTable(table.get()).getRandomItems(params, seed, stack -> {
            if (!stack.isEmpty()) items.add(stack);
        });
        return List.copyOf(items);
    }
}
