package dev.hardrush.lobby;

import dev.hardrush.dialog.DialogClicks;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import net.minecraft.world.phys.Vec3;

/**
 * A creeper booth bound to a block or a tagged entity shows no price of its own, so paying for the ticket
 * (docs/plan/苦力怕付费解锁.md §1) goes through this native dialog's button rather than the click itself. Each dialog
 * carries a one-off offer: its button pays only for the round, price and spot the player was shown, and only once.
 */
final class CreeperUnlockDialog {
    private static final String ACTIONS = "creeper_unlock";
    /** How far the player may wander from the booth between seeing the price and paying it. */
    static final double REACH = 8;
    /** Ticks an offer stays good; long enough to read the dialog. */
    private static final long LIFETIME_TICKS = 60 * 20;
    /** The latest offer shown to each player; a newer dialog replaces it. */
    private static final Map<UUID, Offer> OFFERS = new ConcurrentHashMap<>();

    record Offer(UUID id, UUID round, int price, Vec3 at, long shownAt) {}

    private CreeperUnlockDialog() {}

    static { DialogClicks.register(ACTIONS, CreeperUnlockDialog::clicked); }

    static void show(ServerPlayer player, Offer offer, int coins, Optional<String> discarded) {
        OFFERS.put(player.getUUID(), offer);
        var title = Component.literal("解锁苦力怕");
        var body = Component.literal("花 " + offer.price() + " 怪物币解锁一次苦力怕出击（现有 " + coins + "）。\n\n"
                + "苦力怕只追三星以上的人类；返回营地不用重新付，自爆或倒下后要重新解锁。"
                + discarded.map(parked -> "\n\n暂存的 " + parked + " 连同装备和身价会作废。").orElse(""));
        var common = new CommonDialogData(title, Optional.of(title), true, false, DialogAction.CLOSE,
                List.of(new PlainMessage(body, 250)), List.of());
        var pay = new ActionButton(new CommonButtonData(Component.literal("付 " + offer.price() + " 怪物币解锁"), 150),
                Optional.of(new StaticAction(DialogClicks.click(ACTIONS, "pay/" + offer.id()))));
        var cancel = new ActionButton(new CommonButtonData(Component.literal("算了"), 150), Optional.empty());
        player.connection.send(new ClientboundShowDialogPacket(Holder.direct(new MultiActionDialog(common, List.of(pay, cancel), Optional.empty(), 2))));
    }

    private static void clicked(ServerPlayer player, String action) {
        if (!action.startsWith("pay/")) return;
        var offer = OFFERS.get(player.getUUID());
        // Spent on first use; an older dialog's button, or one left open too long, pays for nothing.
        if (offer == null || !action.equals("pay/" + offer.id())) return;
        OFFERS.remove(player.getUUID(), offer);
        if (player.level().getServer().getTickCount() - offer.shownAt() > LIFETIME_TICKS) {
            dev.hardrush.notice.Notices.refuse(player, "确认已过期，请重新右键苦力怕展位"); return;
        }
        LobbyRuntime.confirmCreeperUnlock(player, offer);
    }

    static void disconnected(ServerPlayer player) { OFFERS.remove(player.getUUID()); }
}
