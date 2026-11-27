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
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.contents.objects.PlayerSprite;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import dev.hardrush.disguise.OwnerSkin;

/**
 * 女巫的碎碎念 (docs/plan/复生补给与女巫说明.md 3): a native multi-page dialog the witch shows each player once per
 * round, in place of the shop's first opening. Paging is answered by the server, so the player never types a command;
 * the last page's button opens the shop itself.
 */
public final class WitchGuide {
    static final String ACTIONS = "witch_guide";
    private static final int WIDTH = 300;
    private static final int RULE_SPACES = 40;
    private static final int RULE_COLOR = 0xA48BC8;
    private static final FontDescription RUNES = new FontDescription.Resource(Identifier.withDefaultNamespace("illageralt"));
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
        if (action.equals("shop")) {
            sound(player, SoundEvents.WITCH_DRINK);
            MonsterShop.openShop(player);
            return;
        }
        if (!action.startsWith("page/")) return;
        int page;
        try { page = Integer.parseInt(action.substring("page/".length())); } catch (NumberFormatException ignored) { return; }
        if (page >= 0 && page < WitchGuideText.PAGES.size()) page(player, page);
    }

    private static void page(ServerPlayer player, int index) {
        player.connection.send(new ClientboundShowDialogPacket(Holder.direct(dialog(index))));
        WitchGuideText.PAGES.get(index).cue().ifPresent(cue -> sound(player, switch (cue) { case GIGGLE -> SoundEvents.WITCH_CELEBRATE; }));
    }

    static MultiActionDialog dialog(int index) {
        var pages = WitchGuideText.PAGES;
        var page = pages.get(index);
        boolean last = index == pages.size() - 1;
        var buttons = new ArrayList<ActionButton>();
        if (index > 0) buttons.add(button("上一页", "page/" + (index - 1)));
        buttons.add(last ? button("开始购物", "shop") : button("下一页", "page/" + (index + 1)));
        var title = Component.empty().append(runes("hex ")).append(WitchGuideText.TITLE + "（" + (index + 1) + "/" + pages.size() + "）")
                .append(runes(" hex"));
        var body = page.blocks().stream().map(WitchGuide::element).toList();
        // Paging keeps the screen until the next page arrives; the last button closes it before the shop opens.
        var common = new CommonDialogData(title, Optional.of(Component.literal(WitchGuideText.TITLE)), true, false,
                last ? DialogAction.CLOSE : DialogAction.WAIT_FOR_RESPONSE, body, List.of());
        return new MultiActionDialog(common, buttons, Optional.empty(), buttons.size());
    }

    private static ActionButton button(String label, String action) {
        return new ActionButton(new CommonButtonData(Component.literal(label), 100),
                Optional.of(new StaticAction(DialogClicks.click(ACTIONS, action))));
    }

    /** Heard by this player alone: the witch reacting to what they are reading. */
    private static void sound(ServerPlayer player, SoundEvent sound) {
        player.connection.send(new ClientboundSoundPacket(Holder.direct(sound), SoundSource.NEUTRAL,
                player.getX(), player.getY(), player.getZ(), 1.0f, 1.0f, player.getRandom().nextLong()));
    }

    /** Letters drawn in the vanilla illager rune font, framing the title like a spell. */
    private static MutableComponent runes(String letters) {
        return Component.literal(letters).withStyle(style -> style.withFont(RUNES).withColor(ChatFormatting.DARK_PURPLE));
    }

    private static DialogBody element(WitchGuideText.Block block) {
        return switch (block) {
            // Strikethrough spaces draw a plain horizontal rule with the default font.
            case WitchGuideText.Divider ignored -> new PlainMessage(Component.literal(" ".repeat(RULE_SPACES))
                    .withStyle(style -> style.withStrikethrough(true).withColor(RULE_COLOR)), WIDTH);
            case WitchGuideText.Paragraph paragraph -> paragraph.icon()
                    .<DialogBody>map(icon -> new ItemBody(item(icon), Optional.of(new PlainMessage(lines(paragraph.lines()), WIDTH - 18)),
                            false, true, 16, 16))
                    .orElseGet(() -> new PlainMessage(lines(paragraph.lines()), WIDTH));
        };
    }

    private static ItemStackTemplate item(WitchGuideText.Icon icon) {
        return switch (icon) {
            case TOTEM -> new ItemStackTemplate(Items.TOTEM_OF_UNDYING);
            // The coin itself, so hovering it shows the same name the camp backpack and the shop use.
            case MONSTER_COIN -> ItemStackTemplate.fromNonEmptyStack(CampKit.coin(1));
            case BREAD -> new ItemStackTemplate(Items.BREAD);
        };
    }

    private static MutableComponent lines(List<WitchGuideText.Line> lines) {
        var out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            for (var span : lines.get(i).spans()) out.append(span(span));
            if (i < lines.size() - 1) out.append(Component.literal("\n"));
        }
        return out;
    }

    private static MutableComponent span(WitchGuideText.Span span) {
        return switch (span) {
            case WitchGuideText.Face face -> Component.object(new PlayerSprite(OwnerSkin.head(face.head().name().toLowerCase(Locale.ROOT)), true));
            case WitchGuideText.Words words -> {
                var text = Component.literal(words.text());
                yield switch (words.look()) {
                    case PLAIN -> text;
                    case BOLD -> text.withStyle(ChatFormatting.BOLD);
                    case KEY -> text.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD, ChatFormatting.UNDERLINE);
                    case ASIDE -> text.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
                };
            }
        };
    }
}
