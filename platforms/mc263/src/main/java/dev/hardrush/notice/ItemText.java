package dev.hardrush.notice;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Names and descriptions of HardRush's special items, in the same markup and colours as {@link Notices}.
 *
 * <p>The first description line of a special item is its one-line usage; {@code HeldItemHints} shows that same
 * sentence on the action bar when the item comes into the hand, so each item builds both from one string.
 */
public final class ItemText {
    private ItemText() {}

    /** The native item name: the vanilla HUD and tooltip draw a {@code CUSTOM_NAME} in italics, like an anvil rename. */
    public static ItemStack name(ItemStack stack, String name) {
        stack.set(DataComponents.ITEM_NAME, Component.literal(Notices.plain(name)));
        return stack;
    }

    /** Gray body and white keys instead of the default purple italic lore. */
    public static ItemStack lore(ItemStack stack, String... markup) { return lore(stack, List.of(markup)); }

    public static ItemStack lore(ItemStack stack, List<String> markup) {
        stack.set(DataComponents.LORE, new ItemLore(lines(markup)));
        return stack;
    }

    public static List<Component> lines(List<String> markup) {
        return markup.stream().<Component>map(Notices::format).toList();
    }
}
