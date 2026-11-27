package dev.hardrush.lobby.workshop;

import dev.hardrush.core.lobby.plan.Binding;
import dev.hardrush.core.lobby.plan.Problem;
import dev.hardrush.core.lobby.plan.Severity;
import dev.hardrush.lobby.ButtonMenu;
import dev.hardrush.lobby.ButtonMenu.Button;
import dev.hardrush.lobby.pack.MarkerStands;
import dev.hardrush.notice.Notices;
import dev.hardrush.player.PlayerProfiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * The toolbox chest (design 4.4 item 1, 11.4, 19.1, 19.2): marker items by category, the light check's list, walking
 * preview, the build range and the import helpers, pasting a building file, and leaving.
 */
final class ToolboxMenu {
    /** Marker item categories, by binding. */
    enum Category {
        ZONES("区域", Items.WOOL.lime()), LANDINGS("落点与展位", Items.COMPASS), CLICKS("点击设施", Items.LECTERN),
        BLOCKS("方块设施", Items.VAULT), TEXTS("文字", Items.OAK_SIGN);

        final String label;
        final net.minecraft.world.item.Item icon;
        Category(String label, net.minecraft.world.item.Item icon) { this.label = label; this.icon = icon; }

        static Category of(Binding binding) {
            return switch (binding) {
                case ZONE -> ZONES; case POINT, MODEL -> LANDINGS; case CLICK -> CLICKS; case BLOCK -> BLOCKS; case TEXT -> TEXTS;
            };
        }
        static List<String> hint(Binding binding) {
            return switch (binding) {
                case POINT -> List.of("放在玩家要站的地方，朝向就是玩家的朝向");
                case MODEL -> List.of("放在模型要站的地方，模组在这里显示角色模型");
                case CLICK -> List.of("放在方块上：右键那个方块触发", "也可以用绑定杖直接点方块");
                case BLOCK -> List.of("放在指定类型的方块上");
                case TEXT -> List.of("放在告示牌上，模组把文字写进去");
                case ZONE -> List.of();
            };
        }
    }

    private enum Page { MAIN, CATEGORY, CHECKLIST, PASTE, PASTE_CONFIRM }

    private static final class View {
        Page page = Page.MAIN;
        Category category;
        String file;
    }

    private ToolboxMenu() {}

    static void open(Workshop workshop, ServerPlayer player) {
        var view = new View();
        ButtonMenu.open(player, Component.literal("工坊工具箱"), workshop.throttle, new ButtonMenu.Spec() {
            @Override public boolean valid(ServerPlayer owner) {
                return Workshop.get(owner.level().getServer()).orElse(null) == workshop && PlayerProfiles.building(owner);
            }
            @Override public List<Button> buttons(ServerPlayer owner) { return ToolboxMenu.buttons(workshop, owner, view); }
            @Override public void act(ServerPlayer owner, Button button) { ToolboxMenu.act(workshop, owner, view, button); }
        });
    }

    private static List<Button> buttons(Workshop workshop, ServerPlayer player, View view) {
        var draft = workshop.buildingIn(player).orElse(null);
        if (draft == null) return List.of();
        var out = new ArrayList<Button>();
        switch (view.page) {
            case MAIN -> {
                for (var c : Category.values()) out.add(new Button(c.icon, "标记：" + c.label, "category", c.name(), List.of("点击查看并领取标记物品")));
                var result = workshop.checks().result(draft.id()).orElse(null);
                out.add(new Button(result == null || result.errors() > 0 ? Items.WRITABLE_BOOK : Items.WRITTEN_BOOK,
                        result == null ? "验收清单（检查中…）" : result.errors() > 0 ? "验收清单：还缺 " + result.errors() + " 项" : "验收清单：可以发布",
                        "checklist", "", List.of("只统计附近已加载区域里的标记", "正式发布时会检查整个范围")));
                boolean walking = player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE;
                out.add(new Button(walking ? Items.LEATHER_BOOTS : Items.FEATHER, walking ? "步行预览：开（点击回到创造）" : "步行预览：关（点击开始）",
                        "preview", "", List.of("冒险模式、不能飞，检查走位、跳跃和门", "设施不会响应；装进正式大厅后再试玩")));
                out.add(new Button(Items.RECOVERY_COMPASS, "把范围中心设到我这里", "center", "",
                        List.of("现在：中心 " + draft.borderCenterX() + " " + draft.borderCenterZ() + "，直径 " + draft.borderDiameter())));
                out.add(new Button(Items.REDSTONE, "缩小范围（直径 −16）", "resize", "-16", List.of("范围外的东西不会被发布")));
                out.add(new Button(Items.EMERALD, "放大范围（直径 +16）", "resize", "16",
                        List.of("最大 " + dev.hardrush.HardRushProperties.lobbyMaxSize())));
                out.add(new Button(Items.DEEPSLATE, "底部高度设为脚下这层（Y " + Workshop.bottomUnderfoot(player) + "）", "bottom", "set",
                        List.of(draft.bottomY() == null ? "现在：未设置，所有高度都会发布" : "现在：低于 Y " + draft.bottomY() + " 的方块不发布",
                                "导入的存档常带着整片地下，用它排除")));
                if (draft.bottomY() != null) out.add(new Button(Items.BEDROCK, "取消底部高度", "bottom", "clear"));
                out.add(new Button(Items.IRON_SWORD, "清除生物", "clear-mobs", "", List.of("移除范围内已加载的生物、掉落物等", "盔甲架、展示框、展示实体和点击范围保留")));
                out.add(new Button(Items.STRUCTURE_BLOCK, "贴入建筑文件", "paste-list", "", List.of("把 hardrush/workshop/import 里的建筑文件贴到这里")));
                out.add(new Button(Items.STICK, "领取绑定杖", "wand", ""));
                out.add(new Button(Items.OAK_DOOR, "离开工坊，回到营地", "leave", ""));
            }
            case CATEGORY -> {
                out.addAll(Toolkit.markerButtons(view.category));
                out.add(new Button(Items.ARROW, "返回", "back", ""));
            }
            case CHECKLIST -> {
                var result = workshop.checks().result(draft.id()).orElse(null);
                if (result == null) out.add(new Button(Items.CLOCK, "正在检查…", "info", ""));
                else if (result.problems().isEmpty()) out.add(new Button(Items.EMERALD, "附近没有发现问题", "info", "", List.of("可以去草稿页发布（4 级管理员）")));
                else for (int i = 0; i < result.problems().size(); i++) out.add(problem(result.problems().get(i), i));
                out.add(new Button(Items.ARROW, "返回", "back", ""));
            }
            case PASTE -> {
                var files = workshop.importEntries().stream().filter(e -> e.kind() == ImportFolder.Kind.STRUCTURE).toList();
                if (files.isEmpty()) out.add(new Button(Items.PAPER, "import 目录里没有建筑文件", "info", "",
                        List.of("把 .nbt、.schem、.litematic 放进", "世界目录/hardrush/workshop/import/")));
                for (var e : files) out.add(new Button(Items.PAPER, e.name(), "paste-pick", e.name(),
                        e.hasManifest() ? List.of("贴到我这里：水平居中，底面在脚下", "带建筑清单") : List.of("贴到我这里：水平居中，底面在脚下")));
                out.add(new Button(Items.ARROW, "返回", "back", ""));
            }
            case PASTE_CONFIRM -> {
                out.add(new Button(Items.PAPER, "贴入「" + view.file + "」？", "info", "",
                        List.of("以你为水平中心、底面在 Y " + player.getBlockY(), "空气不会覆盖已有方块", "没有撤销")));
                var busy = workshop.pasteProgress(draft.id());
                out.add(busy.isPresent() ? ButtonMenu.Button.disabled("确认贴入", "正在贴入：" + busy.get())
                        : new Button(Items.STRUCTURE_BLOCK, "确认贴入", "paste", view.file));
                out.add(new Button(Items.ARROW, "取消", "back", ""));
            }
        }
        return out;
    }

    private static Button problem(Problem p, int index) {
        var lore = new ArrayList<String>();
        var text = p.message();
        for (int i = 0; i < text.length(); i += 24) lore.add(text.substring(i, Math.min(text.length(), i + 24)));
        if (p.where() != null) {
            lore.add("位置：" + p.where().x() + " " + p.where().y() + " " + p.where().z());
            lore.add("点击传送过去");
        }
        var icon = p.function() != null ? MarkerStands.icon(p.function()) : Items.BARRIER;
        var label = (p.severity() == Severity.ERROR ? "问题" : "提醒") + (p.function() == null ? "" : "【" + p.function().displayName() + "】");
        return new Button(icon, label, p.where() == null ? "info" : "goto", p.where() == null ? "" : p.where().x() + " " + p.where().y() + " " + p.where().z() + " " + index, lore);
    }

    private static void act(Workshop workshop, ServerPlayer player, View view, Button button) {
        switch (button.action()) {
            case "category" -> { view.category = Category.valueOf(button.value()); view.page = Page.CATEGORY; }
            case "checklist" -> view.page = Page.CHECKLIST;
            case "back" -> view.page = view.page == Page.PASTE_CONFIRM ? Page.PASTE : Page.MAIN;
            case "marker" -> Toolkit.markerItem(button.value()).ifPresent(item -> {
                if (!player.getInventory().add(item)) Notices.refuse(player, "背包满了");
                else player.inventoryMenu.sendAllDataToRemote();
            });
            case "goto" -> {
                var parts = button.value().split(" ");
                player.closeContainer();
                player.teleportTo((ServerLevel) player.level(), Integer.parseInt(parts[0]) + .5, Integer.parseInt(parts[1]) + 2, Integer.parseInt(parts[2]) + .5,
                        Set.of(), player.getYRot(), 60, true);
            }
            case "preview" -> togglePreview(player);
            case "center" -> report(player, workshop.setCenter(player), "范围中心已设到 " + player.getBlockX() + " " + player.getBlockZ());
            case "resize" -> report(player, workshop.resize(player, Integer.parseInt(button.value())), "范围已调整");
            case "bottom" -> {
                int y = Workshop.bottomUnderfoot(player);
                boolean set = button.value().equals("set");
                report(player, workshop.setBottom(player, set ? y : null), set ? "低于 Y " + y + " 的方块不会发布" : "已取消底部高度");
            }
            case "clear-mobs" -> workshop.clearMobs(player, n -> Notices.hint(player, "已清除 " + n + " 个实体"))
                    .ifPresent(refusal -> Notices.refuse(player, refusal));
            case "paste-list" -> view.page = Page.PASTE;
            case "paste-pick" -> { view.file = button.value(); view.page = Page.PASTE_CONFIRM; }
            case "paste" -> {
                var refusal = workshop.paste(player, button.value());
                if (refusal.isPresent()) Notices.refuse(player, refusal.get());
                else { Notices.hint(player, "开始贴入 " + Notices.key(button.value())); player.closeContainer(); }
            }
            case "wand" -> {
                if (!player.getInventory().add(Toolkit.wand())) Notices.refuse(player, "背包满了");
                else player.inventoryMenu.sendAllDataToRemote();
            }
            case "leave" -> { player.closeContainer(); workshop.leave(player, null); }
            default -> { }
        }
    }

    private static void report(ServerPlayer player, java.util.Optional<String> refusal, String done) {
        if (refusal.isPresent()) Notices.refuse(player, refusal.get()); else Notices.hint(player, done);
    }

    /** Design 11.4: adventure without flight to walk the build; again for creative. */
    static void togglePreview(ServerPlayer player) {
        if (player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            Notices.hint(player, "步行预览结束 · 回到创造模式");
        } else {
            player.setGameMode(GameType.ADVENTURE);
            player.getAbilities().flying = false;
            player.getAbilities().mayfly = false;
            player.onUpdateAbilities();
            Notices.hint(player, "步行预览 · 冒险模式，不能飞 · 工具箱里再点一次回到创造");
        }
    }
}
