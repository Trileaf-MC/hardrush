package dev.hardrush.combat;

import dev.hardrush.core.combat.CombatOrigin;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Who put a lingering effect on a player. A monster's cloud hands out effects whose later ticks (poison) hurt without a
 * source of their own; each tick is credited to the cloud that applied it (docs/plan/女巫药水与药水盲盒.md §5).
 * Main thread only, memory only: an effect outliving a restart ticks unattributed, like any native poison.
 */
public final class EffectOrigins {
    private static final Map<LivingEntity, Map<Holder<MobEffect>, CombatOrigin>> APPLIED = new WeakHashMap<>();

    private EffectOrigins() {}

    /** An effect about to be added: a monster cloud's own origin is kept for it, anything else forgets the old one. */
    public static void applied(LivingEntity target, MobEffectInstance effect, Entity acting) {
        if (!(target instanceof Player)) return;
        Optional<CombatOrigin> origin = acting instanceof AreaEffectCloud cloud ? CombatRuntime.originOf(cloud) : Optional.empty();
        if (origin.isPresent()) APPLIED.computeIfAbsent(target, ignored -> new HashMap<>()).put(effect.getEffect(), origin.get());
        else {
            var known = APPLIED.get(target);
            if (known != null) known.remove(effect.getEffect());
        }
    }

    /** Around one tick of {@code effect} on {@code target}. */
    public static CombatRuntime.Scope ticking(ServerLevel level, LivingEntity target, Holder<MobEffect> effect) {
        var known = APPLIED.get(target);
        var origin = known == null ? null : known.get(effect);
        if (origin == null) return CombatRuntime.Scope.NONE;
        if (target.getEffect(effect) == null) { known.remove(effect); return CombatRuntime.Scope.NONE; }
        return CombatRuntime.effectTick(level, Optional.of(origin));
    }
}
