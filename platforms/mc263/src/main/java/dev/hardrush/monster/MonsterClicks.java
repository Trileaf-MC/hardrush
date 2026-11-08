package dev.hardrush.monster;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/**
 * One native right-click reaches the server as several packets. The 26.3 client tries the main hand, then
 * the off hand; for each it sends the aimed creature or block, then the item itself, and stops early only on
 * its own predicted success (Minecraft.startUseItem). Bound controls such as the echo shard, the recovery
 * compass and the soulcatcher bag predict nothing, so a click on a block arrives as UseItemOn(main),
 * UseItem(main), UseItemOn(off). Once a control has answered a click, the rest of it is the same press:
 * it must neither act again nor count as "using another item".
 *
 * <p>A packet continues the answered click only when it comes later in that fixed order and arrives in the
 * same or the next server tick. A fresh click starts over from the main hand, so it is never swallowed.
 */
final class MonsterClicks {
    /** The order in which one click's packets leave a vanilla client. */
    enum Step {
        MAIN_TARGET, MAIN_ITEM, OFF_TARGET, OFF_ITEM;

        /** @param item the plain item use (UseItem), as opposed to a creature or block under the crosshair */
        static Step of(InteractionHand hand, boolean item) {
            return hand == InteractionHand.MAIN_HAND ? item ? MAIN_ITEM : MAIN_TARGET : item ? OFF_ITEM : OFF_TARGET;
        }
    }

    private record Answer(int tick, Step step) {}
    private static final Map<ServerPlayer, Answer> ANSWERED = new WeakHashMap<>();

    private MonsterClicks() {}

    /** A control took the click at {@code step}; the packets after it in the same click are swallowed. */
    static void answer(ServerPlayer player, Step step) {
        ANSWERED.put(player, new Answer(tick(player), step));
    }

    /** Whether the packet at {@code step} is the rest of a click a control already answered. */
    static boolean answered(ServerPlayer player, Step step) {
        var answer = ANSWERED.get(player);
        return answer != null && tick(player) - answer.tick <= 1 && step.compareTo(answer.step) > 0;
    }

    private static int tick(ServerPlayer player) { return player.level().getServer().getTickCount(); }
}
