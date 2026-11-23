package dev.hardrush.revival;

import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.notice.Notices;
import dev.hardrush.revival.TotemProjection.Holder;
import dev.hardrush.revival.TotemProjection.Identity;
import java.util.*;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DeathProtection;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * The in-place revival (docs/plan/复生图腾掉落.md 1.5, 2.5): an active human holding a revival totem in either hand
 * survives a lethal hit as with vanilla's totem of undying, and one charge is spent. When a human's direct attack forced
 * it out, one revival totem drops at the saved human's feet as it would have at the death ({@link TotemDrops}). The
 * world acts at once and the ledger follows: {@link Access#save} books the charge synchronously (queued as
 * {@code TOTEM_SAVE}) before anything in the world changes, and only then does the totem pop.
 *
 * <p>Runs at the head of {@code LivingEntity.checkTotemDeathProtection}, by vanilla's own rules: a hit that bypasses
 * invulnerability (the void, {@code /kill}) is never stopped; otherwise the main hand, then the off hand, and the first
 * item that can protect wins. A native death guard ({@code DEATH_PROTECTION}) found first is left to vanilla. A revival
 * totem that cannot save (no charge left, competition locked, a copy of another life) is skipped, so vanilla still
 * finds a totem of undying in the other hand; revival totems never carry {@code DEATH_PROTECTION}, which is what makes
 * vanilla's own scan pass over them unchanged.
 */
public final class TotemSave {
    /** The round runtime as an in-place revival sees it. */
    public interface Access {
        /**
         * Books one in-place revival of {@code player}, whose held copy belongs to {@code life} of {@code round}
         * ({@code RoundSession.captureTotemSave}); {@code killCredit} is this lethal hit's credit, resolved as a death's
         * would be, and {@code dropToken} the totem a human's hit forces out ({@link TotemDrops#prepareSave}). The
         * charges left once it commits (the committed balance minus every save in flight), or empty when the player
         * cannot be saved and nothing was booked.
         */
        OptionalInt save(ServerPlayer player, UUID round, int life, Optional<CombatOrigin> killCredit, Optional<UUID> dropToken);
    }

    private static final Map<MinecraftServer, Access> INSTANCES = new IdentityHashMap<>();

    private TotemSave() {}

    public static void configure(MinecraftServer server, Access access) { INSTANCES.put(server, Objects.requireNonNull(access)); }
    public static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); }

    /** What the action bar says when a revival totem saved its holder; {@code {n}} is the charges left. */
    static String savedTip(int left) { return "复生图腾生效 · 还剩 " + Notices.key(left) + " 个"; }

    /**
     * {@code checkTotemDeathProtection}'s answer when a revival totem saved {@code entity}: true, with the native totem
     * effects already applied. False means "not handled": vanilla's own check then runs unchanged.
     */
    public static boolean protect(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof ServerPlayer player) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        var access = INSTANCES.get(player.level().getServer());
        if (access == null) return false;
        Optional<CombatOrigin> killCredit = null;
        for (var hand : InteractionHand.values()) {
            var stack = player.getItemInHand(hand);
            // Vanilla's own guard comes first in this hand: vanilla uses it.
            if (stack.has(DataComponents.DEATH_PROTECTION)) return false;
            var identity = humanCopy(stack);
            if (identity.isEmpty()) continue;
            if (killCredit == null) killCredit = CombatRuntime.peekKillCredit(player.level(), player, source);
            // The save releases a totem exactly as this death would have (守恒 1.1): dropped unless a monster earned it.
            var drop = TotemDrops.prepareSave(player, killCredit);
            var left = access.save(player, identity.get().round(), identity.get().life(), killCredit, drop);
            drop.ifPresent(token -> TotemDrops.captured(player, identity.get().round(), token, left.isPresent()));
            // Cannot save: passed over like any item without DEATH_PROTECTION, so vanilla looks at the other hand.
            if (left.isEmpty()) continue;
            pop(player, stack, source, killCredit, left.getAsInt());
            return true;
        }
        return false;
    }

    /** A copy projected for an active human; floor totems, camp monsters' copies and anything else do not save. */
    private static Optional<Identity> humanCopy(ItemStack stack) {
        return RevivalTotems.identity(stack).filter(identity -> identity.holder() == Holder.HUMAN);
    }

    /** The tail of vanilla's {@code checkTotemDeathProtection} once it found an item, in the same order. */
    private static void pop(ServerPlayer player, ItemStack stack, DamageSource source, Optional<CombatOrigin> killCredit, int left) {
        var used = stack.copy();
        stack.shrink(1);
        player.awardStat(Stats.ITEM_USED.get(used.getItem()));
        CriteriaTriggers.USED_TOTEM.trigger(player, used);
        used.causeUseVibration(player, GameEvent.ITEM_INTERACT_FINISH);
        player.setHealth(1.0F);
        DeathProtection.TOTEM_OF_UNDYING.applyEffects(used, player);
        player.level().broadcastEntityEvent(player, (byte) 35);
        CombatRuntime.revivedInPlace(player);
        Notices.hint(player, savedTip(left));
        dev.hardrush.death.DeathNarration.savedInPlace(player, source, killCredit);
    }
}
