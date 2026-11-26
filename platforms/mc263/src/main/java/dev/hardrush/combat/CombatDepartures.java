package dev.hardrush.combat;

import dev.hardrush.celebration.CelebrationRuntime;
import dev.hardrush.core.round.CombatDeparture;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.player.PlayerDisconnects;
import dev.hardrush.revival.mixin.TotemDeathProtectionInvoker;
import dev.hardrush.world.RoundWorldBundle;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;

/**
 * A hardcore human who leaves mid-fight takes the lethal hit the departure stands for (docs/plan/战斗中断线.md): as if
 * whoever holds the kill credit had landed it, where the body stands. Everything after is the native death, so the
 * belongings drop there, the revival totem is released by the same rule as any death, and the player comes back as a
 * camp monster. A held revival totem (or a totem of undying) stops it as it would stop any lethal hit: the human pays
 * one, releases one, and leaves alive.
 */
public final class CombatDepartures {
    // Main thread only; a body is marked from its lethal departure to the end of that death.
    private static final Set<ServerPlayer> DEPARTING = Collections.newSetFromMap(new IdentityHashMap<>());

    private CombatDepartures() {}

    /**
     * {@code player} is leaving {@code round}; runs before the offline report, so the death or the in-place revival
     * reaches the round first. A disconnect the server made itself never counts.
     */
    public static void disconnected(ServerPlayer player, PlayerDisconnects.Cause cause, RoundSnapshot round) {
        if (cause != PlayerDisconnects.Cause.PLAYER || round == null || !player.isAlive()
                || !RoundWorldBundle.owns(player.level()) || CelebrationRuntime.successful(round)) return;
        var level = player.level();
        var lethal = player.damageSources().genericKill();
        if (!CombatDeparture.countsAsLethal(round, player.getUUID(), CombatRuntime.peekKillCredit(level, player, lethal))) return;
        DEPARTING.add(player);
        // Like the creeper whose lit fuse runs out on leaving: no provenance of its own, so the remembered hit is credited.
        try (var credit = CombatRuntime.rememberedCredit(level)) {
            // A lethal hit totems can stop, asked the way vanilla asks once health reaches zero.
            if (((TotemDeathProtectionInvoker) player).hardrush$checkTotemDeathProtection(player.damageSources().generic())) {
                // No damage wrapper runs around this save to read the mark.
                CombatRuntime.takeRevivedInPlace(player);
                return;
            }
            player.hurtServer(level, lethal, Float.MAX_VALUE);
        } finally {
            DEPARTING.remove(player);
        }
    }

    /** Whether {@code player}'s death right now is a departure mid-fight; its death message says so. */
    public static boolean departing(ServerPlayer player) { return DEPARTING.contains(player); }
}
