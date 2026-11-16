package dev.hardrush.lobby.workshop;

import dev.hardrush.lobby.ButtonMenu;
import dev.hardrush.lobby.ButtonMenu.Button;
import dev.hardrush.lobby.pack.LobbyAdmin;
import dev.hardrush.lobby.pack.LobbyAdmin.PackSummary;
import dev.hardrush.lobby.pack.LobbyInstall;
import dev.hardrush.notice.Notices;
import dev.hardrush.world.LobbyWorld;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/**
 * The lobby administration chest (design 12), opened from the workshop list: which lobby is live, the pack library,
 * installing a pack, rolling back and cancelling. Level 4 only, checked again on every click. Outcomes reach the
 * operators through {@link Notices} from {@link LobbyAdmin}; the menu only refuses what was not taken.
 */
final class LobbyAdminMenu {
    private enum Page { TOP, LIBRARY, PACK, CONFIRM_INSTALL, CONFIRM_ROLLBACK }

    /** Per opened menu: which page it shows and which pack file it looks at. */
    private static final class View {
        Page page = Page.TOP;
        String selected;
    }

    private static final int WARNINGS_SHOWN = 3;

    private LobbyAdminMenu() {}

    static boolean open(Workshop workshop, ServerPlayer player) {
        var view = new View();
        return ButtonMenu.open(player, Component.literal("大厅包库"), workshop.throttle, new ButtonMenu.Spec() {
            @Override public boolean valid(ServerPlayer owner) {
                return Workshop.get(owner.level().getServer()).orElse(null) == workshop && Workshop.owner(owner);
            }
            @Override public List<Button> buttons(ServerPlayer owner) { return LobbyAdminMenu.buttons(workshop, owner, view); }
            @Override public void act(ServerPlayer owner, Button button) { LobbyAdminMenu.act(workshop, owner, view, button); }
        });
    }

    private static List<Button> buttons(Workshop workshop, ServerPlayer player, View view) {
        var server = player.level().getServer();
        var out = new ArrayList<Button>();
        var library = view.page == Page.LIBRARY || view.page == Page.PACK || view.page == Page.CONFIRM_INSTALL
                ? LobbyAdmin.library(server) : null;
        PackSummary selected = null;
        if (library != null && view.page != Page.LIBRARY) {
            selected = library.packs().stream().filter(p -> p.fileName().equals(view.selected)).findFirst().orElse(null);
            if (selected == null) view.page = Page.LIBRARY;
        }
        switch (view.page) {
            case TOP -> top(workshop, server, out);
            case LIBRARY -> {
                for (var pack : library.packs()) out.add(packButton(pack));
                if (library.packs().isEmpty())
                    out.add(new Button(Items.CLOCK, library.loading() ? "读取中…" : "大厅包库是空的", "info", "",
                            library.loading() ? List.of() : List.of("把 .hrlobby 文件放进服务端的 hardrush/lobby/packs 文件夹")));
                out.add(new Button(Items.ARROW, "返回", "back", ""));
            }
            case PACK -> {
                out.add(new Button(Items.FILLED_MAP, title(selected), "info", "", details(selected)));
                var refusal = installRefusal(server, selected);
                out.add(refusal == null ? new Button(Items.ENDER_PEARL, "安装", "install", selected.fileName())
                        : Button.disabled("安装", refusal));
                out.add(new Button(Items.ARROW, "返回", "back", ""));
            }
            case CONFIRM_INSTALL -> {
                out.add(new Button(Items.PAPER, "安装 " + title(selected) + "？", "info", "", List.of(
                        "先在后台建好新大厅，等本轮回到准备阶段再切换",
                        "切换时大厅里的所有人会被传到新大厅")));
                out.add(new Button(Items.ENDER_PEARL, "确认安装", "install-confirm", selected.fileName()));
                out.add(new Button(Items.ARROW, "取消", "back", ""));
            }
            case CONFIRM_ROLLBACK -> {
                out.add(new Button(Items.PAPER, "回到上一个大厅？", "info", "", List.of(
                        "把上一个大厅重新安装一遍，本轮回到准备阶段时切换",
                        "切换时大厅里的所有人会被传到那里")));
                out.add(new Button(Items.RECOVERY_COMPASS, "确认回到上一个大厅", "rollback-confirm", ""));
                out.add(new Button(Items.ARROW, "取消", "back", ""));
            }
        }
        return out;
    }

    private static void top(Workshop workshop, MinecraftServer server, List<Button> out) {
        var lobby = LobbyWorld.open(server);
        var current = lobby.installation();
        if (current.isPresent()) {
            var pack = current.get().pack();
            out.add(new Button(Items.CAMPFIRE, "当前大厅：" + pack.name() + " v" + pack.version() + "（" + authors(pack.authors()) + "）", "info", ""));
        } else out.add(new Button(Items.CLOCK, "当前大厅：还没准备好", "info", ""));

        var progress = LobbyAdmin.progress(server);
        if (progress.isPresent()) {
            var p = progress.get();
            out.add(p.cancellable()
                    ? new Button(Items.CLOCK, "安装进行中：" + p.pack(), "cancel", "", progressLore(p, "点击取消安装"))
                    : Button.disabled("安装进行中：" + p.pack(), String.join("；", progressLore(p, "正在记录，已经不能取消"))));
        }
        out.add(new Button(Items.BOOKSHELF, "大厅包库", "library", ""));
        var rollback = LobbyAdmin.rollbackRefusal(server);
        out.add(rollback.isEmpty() ? new Button(Items.RECOVERY_COMPASS, "回到上一个大厅", "rollback", "")
                : Button.disabled("回到上一个大厅", rollback.get()));
        out.add(new Button(Items.ARROW, "返回工坊", "workshop", ""));
    }

    private static List<String> progressLore(LobbyAdmin.Progress p, String last) {
        var lines = new ArrayList<String>();
        lines.add("阶段：" + stage(p.stage()));
        if (p.totalTiles() > 0) lines.add("已放置：" + p.placedTiles() + " / " + p.totalTiles() + " 块");
        if (p.waitingForSwitch()) lines.add("已建好，等待本轮回到准备阶段（已等 " + p.waitingSeconds() + " 秒）");
        lines.add(last);
        return lines;
    }

    private static String stage(LobbyInstall.Stage stage) {
        return switch (stage) {
            case READING -> "读取大厅包";
            case LOADING -> "加载新大厅的区块";
            case PLACING -> "放置建筑";
            case SETTLING -> "等待区块稳定";
            case READY -> "已就绪，等待切换";
            case COMMITTING -> "记录并切换";
            case DONE -> "完成";
        };
    }

    private static Button packButton(PackSummary pack) {
        var lore = new ArrayList<String>();
        pack.manifest().ifPresent(m -> {
            lore.add("版本：v" + m.version() + " · 作者：" + authors(m.authors()));
        });
        lore.add("文件：" + pack.fileName() + " · " + size(pack.size()));
        if (pack.installable()) lore.add("可以安装");
        else for (var problem : pack.problems()) lore.add("不能安装：" + problem);
        if (!pack.fallbacks().isEmpty()) lore.add("会放临时设施：" + names(pack));
        return new Button(pack.installable() ? Items.FILLED_MAP : Items.BARRIER, title(pack), "pack", pack.fileName(), lore);
    }

    private static List<String> details(PackSummary pack) {
        var lines = new ArrayList<String>();
        pack.manifest().ifPresent(m -> {
            lines.add("作者：" + authors(m.authors()));
            lines.add("版本：v" + m.version() + " · 创建于 " + m.created());
            lines.add("制作时的模组版本：" + m.modVersion());
        });
        lines.add("文件：" + pack.fileName() + " · " + size(pack.size()));
        for (var problem : pack.problems()) lines.add("不能安装：" + problem);
        if (!pack.fallbacks().isEmpty()) lines.add("缺少的功能会放临时设施：" + names(pack));
        int shown = Math.min(WARNINGS_SHOWN, pack.warnings().size());
        for (int i = 0; i < shown; i++) lines.add("警告：" + pack.warnings().get(i));
        if (pack.warnings().size() > shown) lines.add("……还有 " + (pack.warnings().size() - shown) + " 条警告");
        return lines;
    }

    /** Why the button is greyed; null when the pack may be installed now. */
    private static String installRefusal(MinecraftServer server, PackSummary pack) {
        if (!pack.installable()) return pack.problems().isEmpty() ? "这个大厅包不能安装" : pack.problems().getFirst();
        if (LobbyAdmin.progress(server).isPresent()) return "已经有一个大厅包在安装，先等它完成或取消";
        return null;
    }

    private static String title(PackSummary pack) {
        return pack.manifest().map(m -> m.name() + " v" + m.version()).orElse(pack.fileName());
    }

    private static String names(PackSummary pack) {
        return String.join("、", pack.fallbacks().stream().map(f -> f.displayName()).toList());
    }

    private static String authors(List<String> authors) { return authors.isEmpty() ? "佚名" : String.join("、", authors); }

    private static String size(long bytes) {
        return bytes >= 1 << 20 ? String.format("%.1f MB", bytes / (double) (1 << 20)) : Math.max(1, bytes >> 10) + " KB";
    }

    private static void act(Workshop workshop, ServerPlayer player, View view, Button button) {
        if (!Workshop.owner(player)) { player.closeContainer(); return; }
        var server = player.level().getServer();
        switch (button.action()) {
            case "library" -> view.page = Page.LIBRARY;
            case "pack" -> { view.selected = button.value(); view.page = Page.PACK; }
            case "install" -> view.page = Page.CONFIRM_INSTALL;
            case "rollback" -> view.page = Page.CONFIRM_ROLLBACK;
            case "back" -> view.page = switch (view.page) {
                case CONFIRM_INSTALL -> Page.PACK;
                case PACK -> Page.LIBRARY;
                default -> Page.TOP;
            };
            case "workshop" -> workshop.openMenu(player);
            case "install-confirm" -> {
                var pack = LobbyAdmin.library(server).packs().stream().filter(p -> p.fileName().equals(button.value())).findFirst();
                if (pack.isEmpty()) { Notices.refuse(player, "大厅包已经不在包库里了"); view.page = Page.LIBRARY; return; }
                report(player, LobbyAdmin.install(server, pack.get().file(), player));
                view.page = Page.TOP;
            }
            case "rollback-confirm" -> {
                report(player, LobbyAdmin.rollback(server, player));
                view.page = Page.TOP;
            }
            case "cancel" -> report(player, LobbyAdmin.cancel(server, player));
            default -> { }
        }
    }

    /** {@link LobbyAdmin} tells the operators what it took; a refusal only reaches the one who asked. */
    private static void report(ServerPlayer player, LobbyAdmin.Result result) {
        if (!result.accepted()) Notices.refuse(player, result.message());
    }
}
