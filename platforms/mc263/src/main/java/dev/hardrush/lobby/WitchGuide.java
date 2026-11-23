package dev.hardrush.lobby;

import dev.hardrush.core.lobby.WitchGuideText;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.dialog.DialogClicks;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;

/**
 * 女巫的碎碎念 (docs/plan/复生补给与女巫说明.md 3): a native multi-page dialog the witch shows each player once per
 * round, in place of the shop's first opening. Paging is answered by the server, so the player never types a command;
 * the last page's button opens the shop itself.
 */
public final class WitchGuide {
    static final String ACTIONS = "witch_guide";
    private static final int WIDTH = 300;
    /** Shown in this server run before the commit lands, so a refused or slow commit never repeats the guide at once. */
    private static final Set<List<UUID>> SHOWN = Collections.synchronizedSet(new HashSet<>());

    private WitchGuide() {}

    // Clicks only ever answer a guide this class has shown, so registering on first use is early enough.
    static { DialogClicks.register(ACTIONS, WitchGuide::clicked); }

    /**
     * Whether the guide is still owed. One already shown whose commit was refused (another commit in flight) is
     * recorded again here instead of being shown twice, so a restart does not repeat it either.
     */
    static boolean pending(CampAccess access, RoundSnapshot round, Participant participant) {
        if (participant.witch().guideSeen()) return false;
        if (!SHOWN.contains(List.of(round.roundId(), participant.id()))) return true;
        record(access, round, participant);
        return false;
    }

    /** Shows the first page and records it as seen; closing it unread counts too. */
    static void show(ServerPlayer player, CampAccess access, RoundSnapshot round, Participant participant) {
        // Earlier rounds' entries are never asked about again.
        SHOWN.removeIf(entry -> !entry.getFirst().equals(round.roundId()));
        SHOWN.add(List.of(round.roundId(), participant.id()));
        record(access, round, participant);
        page(player, 0);
    }

    private static void record(CampAccess access, RoundSnapshot round, Participant participant) {
        access.session().requestWitchGuideSeen(round.roundId(), participant.id(), reply -> {});
    }

    private static void clicked(ServerPlayer player, String action) {
        if (action.equals("shop")) { MonsterShop.openShop(player); return; }
        if (!action.startsWith("page/")) return;
        int page;
        try { page = Integer.parseInt(action.substring("page/".length())); } catch (NumberFormatException ignored) { return; }
        if (page >= 0 && page < WitchGuideText.PAGES.size()) page(player, page);
    }

    private static void page(ServerPlayer player, int index) {
        var pages = WitchGuideText.PAGES;
        boolean last = index == pages.size() - 1;
        var buttons = new ArrayList<ActionButton>();
        if (index > 0) buttons.add(button("上一页", "page/" + (index - 1)));
        buttons.add(last ? button("开始购物", "shop") : button("下一页", "page/" + (index + 1)));
        var title = Component.literal(WitchGuideText.TITLE + "（" + (index + 1) + "/" + pages.size() + "）");
        // Paging keeps the screen until the next page arrives; the last button closes it before the shop opens.
        var common = new CommonDialogData(title, Optional.of(Component.literal(WitchGuideText.TITLE)), true, false,
                last ? DialogAction.CLOSE : DialogAction.WAIT_FOR_RESPONSE, List.of(new PlainMessage(body(pages.get(index)), WIDTH)), List.of());
        var dialog = new MultiActionDialog(common, buttons, Optional.empty(), buttons.size());
        player.connection.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
    }

    private static ActionButton button(String label, String action) {
        return new ActionButton(new CommonButtonData(Component.literal(label), 100),
                Optional.of(new StaticAction(DialogClicks.click(ACTIONS, action))));
    }

    static MutableComponent body(List<WitchGuideText.Line> lines) {
        var out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            var text = Component.literal(line.text());
            switch (line.look()) {
                case PLAIN -> {}
                case BOLD -> text.withStyle(ChatFormatting.BOLD);
                case ASIDE -> text.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
            }
            out.append(text);
            if (i < lines.size() - 1) out.append(Component.literal("\n"));
        }
        return out;
    }
}
