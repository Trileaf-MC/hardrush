package dev.hardrush.world;

import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.lobby.plan.LobbyPlan;
import dev.hardrush.core.lobby.plan.MarkerTags;
import dev.hardrush.world.mixin.TextDisplayAccessor;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

/**
 * The lobby's text slots (design 3, {@code status} and {@code credits}): signs from the plan and text displays tagged
 * {@code hardrush.fn.<id>}, found by tag. Owners say what a function should read; {@link #apply} writes only where
 * the world differs, so it runs every second and also catches text displays that turned up or reloaded since.
 */
final class LobbyTexts {
    /** Four sign lines, and the text for displays. */
    record Content(List<String> sign, String display) {
        Content { sign = List.copyOf(sign); }
    }

    private static final int PERIOD_TICKS = 20;
    private final Map<LobbyFunction, Content> wanted = new EnumMap<>(LobbyFunction.class);
    private int lastApplied = Integer.MIN_VALUE;

    /** Changes what {@code function}'s slots read; written on the next {@link #apply}. */
    void set(LobbyFunction function, Content content) {
        if (content.equals(wanted.put(function, content))) return;
        lastApplied = Integer.MIN_VALUE;
    }

    void forget() { wanted.clear(); lastApplied = Integer.MIN_VALUE; }

    /** Writes every slot that reads otherwise; at most once a second unless something changed. */
    void apply(ServerLevel level, LobbyPlan plan, int tick) {
        if (wanted.isEmpty() || tick - lastApplied < PERIOD_TICKS && lastApplied != Integer.MIN_VALUE) return;
        lastApplied = tick;
        for (var entry : wanted.entrySet())
            for (var slot : plan.texts(entry.getKey()))
                if (slot.sign() != null) sign(level, LobbyWorld.pos(slot.sign()), entry.getValue().sign());
        var b = plan.bounds();
        var box = new AABB(b.min().x(), b.min().y(), b.min().z(), b.max().x() + 1, b.max().y() + 1, b.max().z() + 1);
        for (var display : level.getEntities(EntityTypeTest.forExactClass(Display.TextDisplay.class), box, e -> true)) {
            var content = function(display.entityTags()).map(wanted::get).orElse(null);
            if (content == null) continue;
            var accessor = (TextDisplayAccessor) display;
            if (!accessor.hardrush$text().getString().equals(content.display())) accessor.hardrush$setText(Component.literal(content.display()));
        }
    }

    /** The text function a display's tags bind it to, if any. */
    static Optional<LobbyFunction> function(Set<String> tags) {
        if (!(MarkerTags.parseTrigger(tags).orElse(null) instanceof MarkerTags.Ok ok)) return Optional.empty();
        return Optional.of(ok.spec().function()).filter(f -> f == LobbyFunction.STATUS || f == LobbyFunction.CREDITS);
    }

    private static void sign(ServerLevel level, BlockPos pos, List<String> lines) {
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
        var front = sign.getText(SignTextSlot.FRONT).getMessages(false);
        var back = sign.getText(SignTextSlot.BACK).getMessages(false);
        boolean same = sign.isWaxed();
        for (int i = 0; i < 4 && same; i++)
            same = front.get(i).getString().equals(line(lines, i)) && back.get(i).getString().equals(line(lines, i));
        if (same) return;
        var messages = new ArrayList<Component>();
        for (int i = 0; i < 4; i++) messages.add(Component.literal(line(lines, i)));
        var text = new SignText(messages, messages, DyeColor.BLACK, false);
        sign.setText(text, SignTextSlot.FRONT);
        sign.setText(text, SignTextSlot.BACK);
        sign.setWaxed(true);
        sign.setChanged();
    }
    private static String line(List<String> lines, int i) { return i < lines.size() ? lines.get(i) : ""; }
}
