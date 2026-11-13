package dev.hardrush.lobby;

import dev.hardrush.core.lobby.LobbyHints;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The action-bar line above the vanilla selected-item name: when a special item comes into the main hand, its usage
 * appears right above its name, like a second tooltip line. The vanilla HUD (26.3 {@code Hud.extractSelectedItemName})
 * shows only the name, for 40 ticks times the client's notification time, and restarts whenever the item or its
 * name changes; this follows the same rule on the server, so being handed an item counts as much as scrolling to it.
 *
 * <p>Each item family states its own usage, the same sentence that opens the item's description; only the bow,
 * compass and soulcatcher vary it with what they can do right now. A changed sentence is shown again.
 */
public final class HeldItemHints {
    static final String SOURCE = "item";
    /** The vanilla selected-item name's default display time: 40 ticks at notification time 1.0. */
    static final long MILLIS = 2_000;
    private static final List<BiFunction<ServerPlayer, ItemStack, Optional<String>>> FAMILIES = List.of(
            dev.hardrush.monster.MonsterCreeper::heldHint,
            dev.hardrush.monster.MonsterBowDisplay::heldHint,
            dev.hardrush.monster.MonsterCompanions::heldHint,
            dev.hardrush.monster.MonsterSoulcatcher::heldHint,
            dev.hardrush.monster.MonsterRoles::heldHint,
            CampKit::heldHint,
            RevivalTotems::heldHint,
            dev.hardrush.observe.Spyglass::heldHint);
    /**
     * The usage last flashed by each viewer's arbiter; absent while the hand holds nothing special. Kept per arbiter,
     * which lives as long as the player's connection, so a reconnect shows the held item again.
     */
    private static final Map<LobbyHints, String> HELD = new WeakHashMap<>();

    private HeldItemHints() {}

    /** Server thread. The usage of the item in the main hand, if it is a special one. */
    public static Optional<String> of(ServerPlayer player) {
        var stack = player.getMainHandItem();
        if (stack.isEmpty()) return Optional.empty();
        for (var family : FAMILIES) {
            var usage = family.apply(player, stack);
            if (usage.isPresent()) return usage;
        }
        return Optional.empty();
    }

    /** Server thread, every tick before the action bar renders. */
    static void update(ServerPlayer player, LobbyHints hints, long now) {
        var usage = of(player).orElse(null);
        if (Objects.equals(usage, HELD.get(hints))) return;
        if (usage == null) { HELD.remove(hints); hints.clear(SOURCE); }
        else { HELD.put(hints, usage); hints.flash(SOURCE, LobbyHints.Priority.ITEM, usage, now + MILLIS); }
    }
}
