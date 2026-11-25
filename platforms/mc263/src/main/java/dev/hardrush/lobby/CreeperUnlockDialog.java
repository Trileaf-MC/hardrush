package dev.hardrush.lobby;

import dev.hardrush.dialog.DialogClicks;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
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
 * A creeper booth bound to a block or a tagged entity shows no price of its own, so paying for the ticket
 * (docs/plan/苦力怕付费解锁.md §1) goes through this native dialog's button rather than the click itself.
 */
final class CreeperUnlockDialog {
    private static final String ACTIONS = "creeper_unlock";

    private CreeperUnlockDialog() {}

    static { DialogClicks.register(ACTIONS, (player, action) -> { if (action.equals("pay")) LobbyRuntime.confirmCreeperUnlock(player); }); }

    static void show(ServerPlayer player, int price, int coins) {
        var title = Component.literal("解锁苦力怕");
        var body = Component.literal("花 " + price + " 怪物币解锁一次苦力怕出击（现有 " + coins + "）。\n\n"
                + "苦力怕只追三星以上的人类；返回营地不用重新付，自爆或倒下后要重新解锁。");
        var common = new CommonDialogData(title, Optional.of(title), true, false, DialogAction.CLOSE,
                List.of(new PlainMessage(body, 250)), List.of());
        var pay = new ActionButton(new CommonButtonData(Component.literal("付 " + price + " 怪物币解锁"), 150),
                Optional.of(new StaticAction(DialogClicks.click(ACTIONS, "pay"))));
        var cancel = new ActionButton(new CommonButtonData(Component.literal("算了"), 150), Optional.empty());
        player.connection.send(new ClientboundShowDialogPacket(Holder.direct(new MultiActionDialog(common, List.of(pay, cancel), Optional.empty(), 2))));
    }
}
