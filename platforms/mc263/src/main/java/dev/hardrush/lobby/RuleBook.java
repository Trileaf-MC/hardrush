package dev.hardrush.lobby;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.lobby.RuleBookText;
import java.util.ArrayList;
import java.util.regex.Pattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * The rule lectern opens a book, as a lectern would. The book never enters the inventory: the client is shown it in
 * the selected slot just long enough to open it (vanilla {@code ClientPacketListener.handleOpenBook} copies the held
 * book's pages when the packet arrives), then the real item is restored on the client.
 */
public final class RuleBook {
    private static final Pattern KEY = Pattern.compile("\\{key:([a-zA-Z.]+)}");
    private RuleBook() {}

    public static void open(ServerPlayer player, RulesConfig rules) {
        if (player.connection == null) return;
        var pages = new ArrayList<Filterable<Component>>();
        for (var page : RuleBookText.pages(rules)) pages.add(Filterable.passThrough(page(page)));
        var book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(RuleBookText.TITLE), "HardRush", 0, pages, true));
        int slot = player.getInventory().getSelectedSlot();
        player.closeContainer();
        player.connection.send(new ClientboundSetPlayerInventoryPacket(slot, book));
        player.connection.send(new ClientboundOpenBookPacket(InteractionHand.MAIN_HAND));
        player.connection.send(new ClientboundSetPlayerInventoryPacket(slot, player.getInventory().getItem(slot).copy()));
    }

    /** Plain text, with each {@code {key:…}} shown as the reader's own binding. */
    static Component page(String text) {
        MutableComponent out = Component.empty();
        var matcher = KEY.matcher(text);
        int last = 0;
        while (matcher.find()) {
            out.append(Component.literal(text.substring(last, matcher.start())));
            out.append(Component.keybind(matcher.group(1)));
            last = matcher.end();
        }
        out.append(Component.literal(text.substring(last)));
        return out;
    }
}
