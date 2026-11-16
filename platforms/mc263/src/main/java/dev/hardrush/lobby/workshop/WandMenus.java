package dev.hardrush.lobby.workshop;

import dev.hardrush.core.lobby.plan.Binding;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.lobby.plan.MarkerSpec;
import dev.hardrush.core.lobby.plan.MarkerTags;
import dev.hardrush.lobby.ButtonMenu;
import dev.hardrush.lobby.ButtonMenu.Button;
import dev.hardrush.lobby.pack.MarkerScan;
import dev.hardrush.lobby.pack.MarkerStands;
import dev.hardrush.notice.Notices;
import dev.hardrush.player.PlayerProfiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/** The chests the binding wand and box zone markers open (design 4.4 items 3 and 4). */
final class WandMenus {
    private WandMenus() {}

    private abstract static class Spec implements ButtonMenu.Spec {
        final Workshop workshop;
        Spec(Workshop workshop) { this.workshop = workshop; }
        @Override public boolean valid(ServerPlayer owner) {
            return Workshop.get(owner.level().getServer()).orElse(null) == workshop && PlayerProfiles.building(owner) && stillThere(owner);
        }
        abstract boolean stillThere(ServerPlayer owner);
    }

    // ---- Wand on a block -----------------------------------------------------------------------------------------

    static void block(ServerPlayer player, BlockPos pos) {
        var workshop = Workshop.get(player.level().getServer()).orElse(null);
        if (workshop == null) return;
        var level = player.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;
        var target = pos.immutable();
        var title = "绑定 " + Toolkit.shortId(MarkerScan.blockId(state)) + "（" + target.getX() + " " + target.getY() + " " + target.getZ() + "）";
        var view = new int[] {0, -1}; // page: 0 functions, 1 sizes, 2 click functions; chosen size
        ButtonMenu.open(player, Component.literal(title), workshop.throttle, new Spec(workshop) {
            @Override boolean stillThere(ServerPlayer owner) {
                return owner.level() == level && level.getBlockState(target) == state && Toolkit.reachable(owner, net.minecraft.world.phys.Vec3.atCenterOf(target));
            }
            @Override public List<Button> buttons(ServerPlayer owner) {
                var out = new ArrayList<Button>();
                switch (view[0]) {
                    case 0 -> {
                        var specs = Toolkit.blockFunctions(state);
                        for (int i = 0; i < specs.size(); i++) {
                            var spec = specs.get(i);
                            out.add(new Button(MarkerStands.icon(spec.function()), "把这个方块绑定为" + MarkerStands.name(spec), "bind", spec.function().id(),
                                    List.of("绑定形态：" + MarkerTags.bindingName(spec.binding()), "会在方块上方放一个标记")));
                        }
                        out.add(new Button(Items.LEAD, "在这里创建点击范围", "sizes", "", List.of("在方块上方放一个看不见的点击范围", "再给它选一个点击功能")));
                    }
                    case 1 -> {
                        for (int i = 0; i < Toolkit.INTERACTION_SIZES.length; i++) {
                            var s = Toolkit.INTERACTION_SIZES[i];
                            out.add(new Button(Items.SCAFFOLDING, "宽 " + s[0] + " × 高 " + s[1], "size", Integer.toString(i)));
                        }
                        out.add(new Button(Items.ARROW, "返回", "back", ""));
                    }
                    default -> {
                        for (var f : LobbyFunction.values()) if (f.bindings().contains(Binding.CLICK))
                            out.add(new Button(MarkerStands.icon(f), "点击范围绑定为【" + f.displayName() + "】", "click", f.id()));
                        out.add(new Button(Items.ARROW, "返回", "back", ""));
                    }
                }
                return out;
            }
            @Override public void act(ServerPlayer owner, Button button) {
                switch (button.action()) {
                    case "bind" -> {
                        var spec = Toolkit.blockFunctions(state).stream().filter(s -> s.function().id().equals(button.value())).findFirst().orElse(null);
                        if (spec == null) return;
                        Toolkit.bindBlock(owner, target, spec);
                        Notices.hint(owner, "已绑定为 " + Notices.key(MarkerStands.name(spec)));
                        owner.closeContainer();
                    }
                    case "sizes" -> view[0] = 1;
                    case "size" -> { view[1] = Integer.parseInt(button.value()); view[0] = 2; }
                    case "back" -> view[0] = view[0] == 2 ? 1 : 0;
                    case "click" -> {
                        var f = LobbyFunction.byId(button.value()).orElse(null);
                        if (f == null || view[1] < 0) return;
                        var s = Toolkit.INTERACTION_SIZES[view[1]];
                        Toolkit.clickBox(owner, target, s[0], s[1], f);
                        Notices.hint(owner, "已创建点击范围 " + Notices.key("【" + f.displayName() + "】"));
                        owner.closeContainer();
                    }
                    default -> { }
                }
            }
        });
    }

    // ---- Wand on an entity ---------------------------------------------------------------------------------------

    static void entity(ServerPlayer player, Entity entity) {
        var workshop = Workshop.get(player.level().getServer()).orElse(null);
        if (workshop == null) return;
        var title = "绑定实体 " + entity.getType().getDescription().getString();
        ButtonMenu.open(player, Component.literal(title), workshop.throttle, new Spec(workshop) {
            @Override boolean stillThere(ServerPlayer owner) {
                return !entity.isRemoved() && entity.level() == owner.level() && Toolkit.reachable(owner, entity.position());
            }
            @Override public List<Button> buttons(ServerPlayer owner) {
                var out = new ArrayList<Button>();
                var bound = entity.entityTags().stream().filter(t -> t.startsWith(MarkerTags.PREFIX)).sorted().toList();
                if (!bound.isEmpty()) {
                    var function = bound.stream().filter(t -> t.startsWith(MarkerTags.FUNCTION)).findFirst()
                            .flatMap(t -> LobbyFunction.byId(t.substring(MarkerTags.FUNCTION.length()))).map(f -> "【" + f.displayName() + "】").orElse(String.join(" ", bound));
                    out.add(new Button(Items.BARRIER, "移除绑定 " + function, "unbind", ""));
                    return out;
                }
                for (var f : Toolkit.entityFunctions(entity))
                    out.add(new Button(MarkerStands.icon(f), "绑定为【" + f.displayName() + "】", "bind", f.id(),
                            List.of(f.bindings().contains(Binding.CLICK) ? "右键这个实体触发" : "模组会把文字写进这个展示实体")));
                return out;
            }
            @Override public void act(ServerPlayer owner, Button button) {
                switch (button.action()) {
                    case "unbind" -> {
                        for (var tag : Set.copyOf(entity.entityTags())) if (tag.startsWith(MarkerTags.PREFIX)) entity.removeTag(tag);
                        Notices.hint(owner, "已移除绑定");
                        owner.closeContainer();
                    }
                    case "bind" -> {
                        var f = LobbyFunction.byId(button.value()).orElse(null);
                        if (f == null || !Toolkit.entityFunctions(entity).contains(f)) return;
                        entity.addTag(MarkerTags.FUNCTION + f.id());
                        Notices.hint(owner, "已绑定为 " + Notices.key("【" + f.displayName() + "】"));
                        owner.closeContainer();
                    }
                    default -> { }
                }
            }
        });
    }

    // ---- Box zone size -------------------------------------------------------------------------------------------

    static void box(ServerPlayer player, ArmorStand stand) {
        var workshop = Workshop.get(player.level().getServer()).orElse(null);
        if (workshop == null) return;
        ButtonMenu.open(player, Component.literal("调整方框区域"), workshop.throttle, new Spec(workshop) {
            @Override boolean stillThere(ServerPlayer owner) {
                return !stand.isRemoved() && stand.level() == owner.level() && current() != null;
            }
            MarkerSpec current() {
                return MarkerTags.parseMarker(stand.entityTags()) instanceof MarkerTags.Ok ok
                        && ok.spec().shape() == dev.hardrush.core.lobby.plan.ZoneShape.BOX ? ok.spec() : null;
            }
            @Override public List<Button> buttons(ServerPlayer owner) {
                var spec = current();
                var out = new ArrayList<Button>();
                out.add(new Button(MarkerStands.icon(spec.function()), MarkerStands.name(spec) + " 宽 " + (spec.boxX() * 2 + 1) + " 高 " + (spec.boxY() + 1)
                        + " 深 " + (spec.boxZ() * 2 + 1), "info", "", List.of("宽、深向两边各延伸，高从标记脚下往上", "每项 0–" + MarkerTags.MAX_BOX)));
                for (var axis : List.of("x", "y", "z")) {
                    var name = switch (axis) { case "x" -> "宽"; case "y" -> "高"; default -> "深"; };
                    for (int delta : new int[] {-5, -1, 1, 5})
                        out.add(new Button(delta < 0 ? Items.REDSTONE : Items.EMERALD, name + (delta > 0 ? " +" : " ") + delta, "resize", axis + delta));
                }
                return out;
            }
            @Override public void act(ServerPlayer owner, Button button) {
                if (!button.action().equals("resize")) return;
                var spec = current();
                int delta = Integer.parseInt(button.value().substring(1));
                int x = spec.boxX(), y = spec.boxY(), z = spec.boxZ();
                switch (button.value().charAt(0)) {
                    case 'x' -> x = clamp(x + delta);
                    case 'y' -> y = clamp(y + delta);
                    default -> z = clamp(z + delta);
                }
                Toolkit.resizeBox(stand, x, y, z);
            }
            int clamp(int v) { return Math.max(0, Math.min(MarkerTags.MAX_BOX, v)); }
        });
    }
}
