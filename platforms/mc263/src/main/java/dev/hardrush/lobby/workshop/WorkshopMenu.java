package dev.hardrush.lobby.workshop;

import dev.hardrush.lobby.ButtonMenu;
import dev.hardrush.lobby.ButtonMenu.Button;
import dev.hardrush.notice.Notices;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/**
 * The {@code /hardrush lobby} chest (design 12): the draft list, a draft's page (enter, delete) and the new-draft page.
 * Buttons a player may not use are greyed out with the reason in their lore. The pack library, install and roll-back
 * live in {@link LobbyAdminMenu}; M3b adds the remaining new-draft sources.
 */
final class WorkshopMenu {
    private enum Page { LIST, DRAFT, CONFIRM_DELETE, NEW }

    /** Per opened menu: which page it shows. */
    private static final class View {
        Page page = Page.LIST;
        String selected;
    }

    private WorkshopMenu() {}

    static boolean open(Workshop workshop, ServerPlayer player) {
        var view = new View();
        return ButtonMenu.open(player, Component.literal("大厅工坊"), workshop.throttle, new ButtonMenu.Spec() {
            @Override public boolean valid(ServerPlayer owner) {
                return Workshop.get(owner.level().getServer()).orElse(null) == workshop && Workshop.gamemaster(owner);
            }
            @Override public List<Button> buttons(ServerPlayer owner) { return WorkshopMenu.buttons(workshop, owner, view); }
            @Override public void act(ServerPlayer owner, Button button) { WorkshopMenu.act(workshop, owner, view, button); }
        });
    }

    private static List<Button> buttons(Workshop workshop, ServerPlayer player, View view) {
        if (!workshop.loaded())
            return List.of(new Button(Items.CLOCK, workshop.failure().orElse("工坊正在读取…"), "info", ""));
        var selected = view.selected == null ? null : workshop.draft(view.selected).orElse(null);
        if (selected == null && view.page != Page.LIST && view.page != Page.NEW) view.page = Page.LIST;
        var out = new ArrayList<Button>();
        switch (view.page) {
            case LIST -> {
                for (var draft : workshop.drafts()) out.add(new Button(draft.state() == Draft.State.READY ? Items.FILLED_MAP : Items.MAP,
                        draft.name(), "draft", draft.id(), summary(workshop, draft)));
                out.add(workshop.drafts().size() >= Workshop.MAX_DRAFTS
                        ? Button.disabled("新建草稿", "草稿已满 " + Workshop.MAX_DRAFTS + " 个，先删除一个")
                        : new Button(Items.WRITABLE_BOOK, "新建草稿", "new", ""));
                out.add(Workshop.owner(player) ? new Button(Items.BOOKSHELF, "大厅包库与安装", "admin", "")
                        : Button.disabled("大厅包库与安装", "需要 4 级管理员权限"));
                if (workshop.buildingIn(player).isPresent()) out.add(new Button(Items.OAK_DOOR, "离开工坊，回到营地", "leave", ""));
            }
            case DRAFT -> {
                out.add(new Button(Items.PAPER, selected.name(), "info", "", summary(workshop, selected)));
                var refusal = enterRefusal(workshop, player, selected);
                out.add(refusal == null ? new Button(Items.ENDER_PEARL, "进入工坊", "enter", selected.id())
                        : Button.disabled("进入工坊", refusal));
                out.add(Workshop.owner(player) ? new Button(Items.LAVA_BUCKET, "删除草稿", "delete", selected.id())
                        : Button.disabled("删除草稿", "需要 4 级管理员权限"));
                out.add(new Button(Items.ARROW, "返回", "back", ""));
            }
            case CONFIRM_DELETE -> {
                out.add(new Button(Items.PAPER, "删除「" + selected.name() + "」？", "info", "",
                        List.of("草稿里的建筑会永久删除，不能恢复", "正在里面建造的人会回到营地")));
                out.add(new Button(Items.TNT, "确认删除", "delete-confirm", selected.id()));
                out.add(new Button(Items.ARROW, "取消", "back", ""));
            }
            case NEW -> {
                out.add(new Button(Items.CAMPFIRE, "从当前大厅", "create", "current", List.of("复制现在正式大厅的建筑和标记")));
                out.add(new Button(Items.STONE, "空白", "create", "blank", List.of("只有中央一块 5×5 石台")));
                out.add(new Button(Items.ARROW, "返回", "back", ""));
            }
        }
        return out;
    }

    private static List<String> summary(Workshop workshop, Draft draft) {
        var lines = new ArrayList<String>();
        lines.add("来源：" + draft.source());
        lines.add(draft.state() == Draft.State.READY ? "状态：可以进入"
                : "状态：导入中 · " + workshop.importProgress(draft.id()).orElse("…"));
        lines.add("在线建设者：" + workshop.builders(draft.id()).size() + " 人");
        if (draft.publishedVersion() > 0) lines.add("已发布：v" + draft.publishedVersion());
        return lines;
    }

    /** Why the button is greyed; null when the player may enter now. */
    private static String enterRefusal(Workshop workshop, ServerPlayer player, Draft draft) {
        if (draft.state() != Draft.State.READY) return "草稿还在导入，稍等片刻";
        if (workshop.buildingIn(player).isPresent()) return "你已经在工坊里了，先离开当前草稿";
        return dev.hardrush.round.RoundRuntime.buildingRefusal(player).orElse(null);
    }

    private static void act(Workshop workshop, ServerPlayer player, View view, Button button) {
        switch (button.action()) {
            case "draft" -> { view.selected = button.value(); view.page = Page.DRAFT; }
            case "new" -> view.page = Page.NEW;
            case "admin" -> { if (Workshop.owner(player)) LobbyAdminMenu.open(workshop, player); }
            case "back" -> view.page = view.page == Page.CONFIRM_DELETE ? Page.DRAFT : Page.LIST;
            case "leave" -> { player.closeContainer(); workshop.leave(player, null); }
            case "enter" -> {
                var refusal = workshop.enter(player, button.value());
                if (refusal.isPresent()) Notices.refuse(player, refusal.get());
                else player.closeContainer();
            }
            case "delete" -> view.page = Page.CONFIRM_DELETE;
            case "delete-confirm" -> {
                workshop.delete(player, button.value()).ifPresent(reason -> Notices.refuse(player, reason));
                view.page = Page.LIST;
            }
            case "create" -> {
                var refusal = workshop.create(player, button.value().equals("blank") ? new Workshop.Seed.Blank() : new Workshop.Seed.CurrentLobby());
                refusal.ifPresent(reason -> Notices.refuse(player, reason));
                view.page = Page.LIST;
            }
            default -> { }
        }
    }
}
