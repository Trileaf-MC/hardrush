package dev.hardrush.lobby.workshop;

import dev.hardrush.lobby.ButtonMenu;
import dev.hardrush.lobby.ButtonMenu.Button;
import dev.hardrush.lobby.pack.LobbyAdmin;
import dev.hardrush.notice.Notices;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/**
 * The workshop chest (design 12), opened by the camp's {@link WorkshopTable} or {@code /hardrush lobby}: the draft
 * list, a draft's page (enter, publish, delete), its publish page and the new-draft page. Buttons a player may not
 * use are greyed out with the reason in their lore.
 * The pack library, install and roll-back live in {@link LobbyAdminMenu}.
 */
final class WorkshopMenu {
    private enum Page { LIST, DRAFT, CONFIRM_DELETE, NEW, PUBLISH }

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
                var job = workshop.publishJob(selected.id()).orElse(null);
                if (!Workshop.owner(player)) out.add(Button.disabled("发布", "需要 4 级管理员权限"));
                else if (job != null) out.add(new Button(Items.WRITTEN_BOOK, "发布：" + job.progress(), "publish-page", selected.id()));
                else if (selected.state() != Draft.State.READY) out.add(Button.disabled("发布", "草稿还在导入"));
                else out.add(new Button(Items.WRITTEN_BOOK, "发布", "publish", selected.id(),
                        List.of("检查整个范围、打包成 " + selected.id() + "-v" + (selected.publishedVersion() + 1) + ".hrlobby",
                                "写入前会让你确认作者")));
                out.add(Workshop.owner(player) ? new Button(Items.LAVA_BUCKET, "删除草稿", "delete", selected.id())
                        : Button.disabled("删除草稿", "需要 4 级管理员权限"));
                out.add(new Button(Items.ARROW, "返回", "back", ""));
            }
            case PUBLISH -> publishPage(workshop, player, selected, out);
            case CONFIRM_DELETE -> {
                out.add(new Button(Items.PAPER, "删除「" + selected.name() + "」？", "info", "",
                        List.of("草稿里的建筑会永久删除，不能恢复", "正在里面建造的人会回到营地")));
                out.add(new Button(Items.TNT, "确认删除", "delete-confirm", selected.id()));
                out.add(new Button(Items.ARROW, "取消", "back", ""));
            }
            case NEW -> {
                out.add(new Button(Items.CAMPFIRE, "从当前大厅", "create", "current", List.of("复制现在正式大厅的建筑和标记")));
                out.add(new Button(Items.STONE, "空白", "create", "blank", List.of("只有中央一块 5×5 石台")));
                for (var pack : workshop.libraryPacks())
                    out.add(new Button(Items.BOOKSHELF, "包库：" + pack, "create-pack", pack, List.of("复制这个大厅包的建筑和标记")));
                boolean owner = Workshop.owner(player);
                for (var entry : workshop.importEntries()) {
                    var label = (entry.kind() == ImportFolder.Kind.SAVE ? "存档：" : "建筑文件：") + entry.name();
                    var lore = entry.kind() == ImportFolder.Kind.SAVE
                            ? List.of("复制这个存档主世界的区块，坐标不变", "不清洗；发布后安装时才清洗")
                            : List.of("水平居中放在 (0, 64, 0)，底面在 Y 64", "只导入方块和方块实体");
                    out.add(owner ? new Button(entry.kind() == ImportFolder.Kind.SAVE ? Items.GRASS_BLOCK : Items.STRUCTURE_BLOCK,
                            label, "create-import", entry.name(), lore) : Button.disabled(label, "需要 4 级管理员权限"));
                }
                out.add(new Button(Items.ARROW, "返回", "back", ""));
            }
        }
        return out;
    }

    private static void publishPage(Workshop workshop, ServerPlayer player, Draft draft, List<Button> out) {
        var job = workshop.publishJob(draft.id()).orElse(null);
        if (job == null) {
            out.add(new Button(Items.PAPER, "没有进行中的发布", "info", ""));
            out.add(new Button(Items.ARROW, "返回", "back", ""));
            return;
        }
        out.add(new Button(Items.CLOCK, "发布「" + draft.name() + "」：" + job.progress(), "info", ""));
        switch (job.stage()) {
            case CONFIRM -> {
                for (var p : draft.participants()) {
                    boolean in = !job.excluded.contains(p.id());
                    out.add(new Button(in ? Items.PLAYER_HEAD : Items.SKELETON_SKULL, (in ? "作者：" : "不署名：") + p.name(), "author",
                            p.id().toString(), List.of(in ? "点击从作者里去掉" : "点击加回作者")));
                }
                if (!job.warnings().isEmpty()) out.add(new Button(Items.GLOWSTONE_DUST,
                        "提醒 " + job.warnings().size() + " 条（不影响发布）", "info", "", wrap(PublishJob.shown(job.warnings()))));
                out.add(new Button(Items.WRITABLE_BOOK, "确认发布", "publish-confirm", draft.id()));
                out.add(new Button(Items.BARRIER, "放弃", "publish-dismiss", draft.id()));
            }
            case FAILED -> {
                out.add(new Button(Items.BARRIER, "没能发布，问题 " + job.errors().size() + " 条", "info", "", wrap(PublishJob.shown(job.errors()))));
                out.add(new Button(Items.ARROW, "知道了", "publish-dismiss", draft.id()));
            }
            case DONE -> {
                var file = job.written();
                out.add(new Button(Items.EMERALD, "已发布为 " + file.getFileName(), "info", "", List.of("在 hardrush/lobby/packs 里")));
                out.add(new Button(Items.BEACON, "现在安装", "install", draft.id(), List.of("换成这个大厅；本轮回到准备阶段时切换")));
                out.add(new Button(Items.ARROW, "完成", "publish-dismiss", draft.id()));
            }
            default -> out.add(new Button(Items.ARROW, "返回", "back", ""));
        }
    }

    private static List<String> wrap(List<String> lines) {
        var out = new ArrayList<String>();
        for (var line : lines) for (int i = 0; i < line.length(); i += 28) out.add((i == 0 ? "· " : "  ") + line.substring(i, Math.min(line.length(), i + 28)));
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
            case "back" -> view.page = view.page == Page.CONFIRM_DELETE || view.page == Page.PUBLISH ? Page.DRAFT : Page.LIST;
            case "admin" -> { if (Workshop.owner(player)) LobbyAdminMenu.open(workshop, player); }
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
            case "publish" -> {
                var refusal = workshop.publish(player, button.value());
                if (refusal.isPresent()) Notices.refuse(player, refusal.get()); else view.page = Page.PUBLISH;
            }
            case "publish-page" -> view.page = Page.PUBLISH;
            case "author" -> workshop.toggleAuthor(player, view.selected, UUID.fromString(button.value()));
            case "publish-confirm" -> workshop.confirmPublish(player, button.value()).ifPresent(reason -> Notices.refuse(player, reason));
            case "publish-dismiss" -> { workshop.dismissPublish(button.value()); view.page = Page.DRAFT; }
            case "install" -> {
                var job = workshop.publishJob(button.value()).orElse(null);
                if (job == null || job.written() == null || !Workshop.owner(player)) return;
                var result = LobbyAdmin.install(player.level().getServer(), job.written(), player);
                if (result.accepted()) Notices.hint(player, result.message()); else Notices.refuse(player, result.message());
            }
            case "create", "create-pack", "create-import" -> {
                var seed = switch (button.action()) {
                    case "create-pack" -> new Workshop.Seed.Pack(button.value());
                    case "create-import" -> new Workshop.Seed.Import(button.value());
                    default -> button.value().equals("blank") ? new Workshop.Seed.Blank() : new Workshop.Seed.CurrentLobby();
                };
                workshop.create(player, seed).ifPresent(reason -> Notices.refuse(player, reason));
                view.page = Page.LIST;
            }
            default -> { }
        }
    }
}
