package dev.hardrush.lobby.workshop;

import dev.hardrush.core.lobby.plan.Binding;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.lobby.plan.MarkerSpec;
import dev.hardrush.core.lobby.plan.MarkerTags;
import dev.hardrush.core.lobby.plan.Problem;
import dev.hardrush.core.lobby.plan.Severity;
import dev.hardrush.core.lobby.plan.ZoneShape;
import dev.hardrush.lobby.ButtonMenu;
import dev.hardrush.lobby.ButtonMenu.Button;
import dev.hardrush.lobby.pack.MarkerScan;
import dev.hardrush.lobby.pack.MarkerStands;
import dev.hardrush.notice.Notices;
import dev.hardrush.player.PlayerProfiles;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The builder's tools (design 4.4), all vanilla items marked with {@code minecraft:custom_data}: the toolbox (a chest
 * in hotbar slot 9 that opens the workshop's tool chest instead of being placed), the {@link Checklist} (a book in
 * slot 7 naming what blocks publishing), marker items (armor stands vanilla places as markers) and the binding wand
 * (a stick: right-click a block or entity to bind it). Right-clicking a marker
 * armor stand edits it instead of dressing it. The network adapter ({@code WorkshopToolMixin}) routes builders' clicks
 * here; everything else stays vanilla.
 */
public final class Toolkit {
    static final String TOOL = "hardrush_tool";
    static final String TOOLBOX = "toolbox", WAND = "wand", CHECKLIST = "checklist";
    public static final int TOOLBOX_SLOT = 8, WAND_SLOT = 7, CHECKLIST_SLOT = 6;
    /** A kept tool missing from a builder, counted in ticks until it is given back. */
    record Missing(UUID player, String kind) {}
    /** Generous creative reach: the client already limits it, this only rejects nonsense. */
    private static final double REACH = 10;
    /** A toolbox or checklist missing this long is given back; shorter gaps are a creative inventory move in progress. */
    static final int RESTORE_TICKS = 10;
    static final int INTERACTION_SIZES[][] = {{1, 1}, {1, 2}, {2, 2}, {3, 3}};

    private Toolkit() {}

    // ---- Items ---------------------------------------------------------------------------------------------------

    public static ItemStack toolbox() {
        return tool(new ItemStack(Items.CHEST), TOOLBOX, "工坊工具箱",
                List.of("右键打开：标记、验收清单、步行预览、范围设置", "丢掉或放进箱子后会自动回到快捷栏"));
    }

    public static ItemStack wand() {
        return tool(new ItemStack(Items.STICK), WAND, "绑定杖",
                List.of("右键方块：把它绑定为某个功能", "右键实体：给它绑定或解除功能"));
    }

    static ItemStack tool(ItemStack stack, String kind, String name, List<String> lore) {
        var data = new CompoundTag();
        data.putString(TOOL, kind);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        stack.set(DataComponents.ITEM_NAME, Component.literal(name));
        stack.set(DataComponents.LORE, new ItemLore(lore.stream().map(l -> (Component) Component.literal(l)).toList()));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    /** Which tool a stack is, if any. */
    public static Optional<String> kind(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.getString(TOOL);
    }
    static boolean is(ItemStack stack, String kind) { return kind(stack).filter(kind::equals).isPresent(); }

    /** Hands a builder who just arrived the toolbox, the checklist and the wand. */
    static void give(ServerPlayer player, ItemStack checklist) {
        player.getInventory().setItem(TOOLBOX_SLOT, toolbox());
        player.getInventory().setItem(CHECKLIST_SLOT, checklist.copy());
        player.getInventory().setItem(WAND_SLOT, wand());
        player.inventoryMenu.sendAllDataToRemote();
    }

    /**
     * Keeps every builder's toolbox and checklist: taken back out of any other container they have open, dropped copies
     * vanish, a builder without one gets it back in its slot after {@link #RESTORE_TICKS}, and a checklist that no
     * longer reads like {@code checklist} (the latest check) is rewritten in place.
     */
    static void keep(ServerPlayer player, java.util.Map<Missing, Integer> missing, ItemStack checklist) {
        var menu = player.containerMenu;
        if (menu != player.inventoryMenu && !(menu instanceof ButtonMenu)) {
            for (var slot : menu.slots)
                if (slot.container != player.getInventory() && (is(slot.getItem(), TOOLBOX) || is(slot.getItem(), CHECKLIST))) slot.set(ItemStack.EMPTY);
        }
        var level = (ServerLevel) player.level();
        for (var item : level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(16), e -> kind(e.getItem()).isPresent()))
            item.discard();
        keepOne(player, missing, TOOLBOX, TOOLBOX_SLOT, Toolkit::toolbox, null);
        keepOne(player, missing, CHECKLIST, CHECKLIST_SLOT, checklist::copy, checklist);
    }

    /** One kept tool: duplicates go, a stale copy is refreshed to {@code current} (when given), a lost one comes back. */
    private static void keepOne(ServerPlayer player, java.util.Map<Missing, Integer> missing, String kind, int home,
                                java.util.function.Supplier<ItemStack> make, ItemStack current) {
        var inventory = player.getInventory();
        var key = new Missing(player.getUUID(), kind);
        int found = is(player.containerMenu.getCarried(), kind) ? 1 : 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!is(stack, kind)) continue;
            if (found++ > 0) inventory.setItem(i, ItemStack.EMPTY);
            else if (current != null && !ItemStack.isSameItemSameComponents(stack, current)) inventory.setItem(i, current.copy());
        }
        if (found > 0) { missing.remove(key); return; }
        int ticks = missing.merge(key, 1, Integer::sum);
        if (ticks < RESTORE_TICKS) return;
        missing.remove(key);
        var displaced = inventory.getItem(home);
        inventory.setItem(home, make.get());
        // A full creative inventory loses the displaced copy; creative items cost nothing.
        if (!displaced.isEmpty()) inventory.add(displaced);
        player.inventoryMenu.sendAllDataToRemote();
    }

    // ---- Network entry points (server thread) -------------------------------------------------------------------

    /** A right-click in the air. Consumed when it was the toolbox or the checklist. */
    public static boolean useItem(ServerPlayer player, InteractionHand hand) {
        if (!PlayerProfiles.building(player)) return false;
        var held = player.getItemInHand(hand);
        if (is(held, TOOLBOX)) openToolbox(player);
        else if (is(held, CHECKLIST)) useChecklist(player);
        else return false;
        return true;
    }

    /** A right-click on a block. Consumed when a tool is in either hand. */
    public static boolean useItemOn(ServerPlayer player, ServerboundUseItemOnPacket packet) {
        if (!PlayerProfiles.building(player)) return false;
        var main = kind(player.getMainHandItem()).orElse(null);
        var held = kind(player.getItemInHand(packet.hand())).orElse(null);
        if (main == null && held == null) return false;
        // The client predicted placing the chest; tell it what is really there.
        var hit = packet.hitResult();
        player.connection.ackBlockChangesUpTo(packet.sequence());
        player.connection.send(new ClientboundBlockUpdatePacket(player.level(), hit.getBlockPos()));
        player.connection.send(new ClientboundBlockUpdatePacket(player.level(), hit.getBlockPos().relative(hit.getDirection())));
        // A tool in the main hand owns the click; the off-hand packet that follows must not place anything either.
        if (main != null && packet.hand() != InteractionHand.MAIN_HAND) return true;
        var kind = main != null ? main : held;
        var pos = hit.getBlockPos();
        if (kind.equals(TOOLBOX)) openToolbox(player);
        else if (kind.equals(CHECKLIST)) useChecklist(player);
        else if (kind.equals(WAND) && reachable(player, net.minecraft.world.phys.Vec3.atCenterOf(pos)))
            WandMenus.block(player, pos);
        return true;
    }

    /** A right-click on an entity. Consumed for marker armor stands, and for any entity under the wand. */
    public static boolean interact(ServerPlayer player, ServerboundInteractPacket packet) {
        if (!PlayerProfiles.building(player)) return false;
        var entity = player.level().getEntity(packet.entityId());
        if (entity == null || entity instanceof Player) return false;
        boolean marker = MarkerStands.is(entity);
        boolean wand = is(player.getMainHandItem(), WAND);
        if (!marker && !wand) return false;
        if (packet.hand() != InteractionHand.MAIN_HAND || !reachable(player, entity.position())) return true;
        if (marker) markerClick(player, (ArmorStand) entity);
        else WandMenus.entity(player, entity);
        return true;
    }

    static boolean reachable(ServerPlayer player, net.minecraft.world.phys.Vec3 at) {
        return player.getEyePosition().distanceToSqr(at) <= REACH * REACH;
    }

    // ---- Marker armor stands -------------------------------------------------------------------------------------

    /** Design 4.4 item 4: materials for flood zones, a size menu for box zones, a description for the rest. */
    static void markerClick(ServerPlayer player, ArmorStand stand) {
        var parsed = MarkerTags.parseMarker(stand.entityTags());
        if (parsed instanceof MarkerTags.Ok ok && ok.spec().binding() == Binding.ZONE) {
            var spec = ok.spec();
            var held = player.getMainHandItem();
            if (spec.shape() == ZoneShape.FLOOD && held.getItem() instanceof BlockItem block) {
                toggleMaterial(player, stand, spec, BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
                return;
            }
            if (spec.shape() == ZoneShape.BOX && held.isEmpty()) {
                WandMenus.box(player, stand);
                return;
            }
        }
        describe(player, stand);
    }

    /** Adds or removes one extra floor material of a flood zone marker. */
    static void toggleMaterial(ServerPlayer player, ArmorStand stand, MarkerSpec spec, String blockId) {
        var tag = MarkerTags.materialTag(blockId);
        boolean removing = stand.entityTags().contains(tag);
        if (removing) stand.removeTag(tag); else stand.addTag(tag);
        var materials = new LinkedHashSet<>(spec.materials());
        if (removing) materials.remove(blockId); else materials.add(blockId);
        var under = stand.level().getBlockState(stand.blockPosition().below());
        var base = MarkerScan.blockId(under);
        Notices.hint(player, (removing ? "已移除材质 " : "已添加材质 ") + Notices.key(shortId(blockId)) + " · 当前："
                + (base.equals("minecraft:air") ? "" : shortId(base) + "（脚下）")
                + (materials.isEmpty() ? "" : "、" + String.join("、", materials.stream().map(Toolkit::shortId).toList())));
    }

    /** Sets a box zone marker's size tag. */
    static void resizeBox(ArmorStand stand, int x, int y, int z) {
        for (var tag : Set.copyOf(stand.entityTags())) if (tag.startsWith(MarkerTags.BOX)) stand.removeTag(tag);
        stand.addTag(MarkerTags.BOX + x + "." + y + "." + z);
    }

    /** Chat: what the marker is, how it binds, its block, and what the light check has against it. */
    static void describe(ServerPlayer player, ArmorStand stand) {
        var lines = new ArrayList<String>();
        var level = (ServerLevel) player.level();
        var at = stand.blockPosition();
        switch (MarkerTags.parseMarker(stand.entityTags())) {
            case MarkerTags.Failed failed -> {
                lines.add("这个标记的标签有误（" + at.getX() + " " + at.getY() + " " + at.getZ() + "）：");
                failed.errors().forEach(e -> lines.add("· " + e));
            }
            case MarkerTags.Ok ok -> {
                var spec = ok.spec();
                lines.add(Notices.key(MarkerStands.name(spec)) + " 绑定形态：" + MarkerTags.bindingName(spec.binding())
                        + " · 位置 " + at.getX() + " " + at.getY() + " " + at.getZ());
                switch (spec.binding()) {
                    case CLICK, BLOCK, TEXT -> {
                        var target = MarkerStands.target(level, at);
                        lines.add("目标方块：" + MarkerScan.blockId(level.getBlockState(target)) + "（" + target.getX() + " " + target.getY() + " " + target.getZ() + "）");
                    }
                    case ZONE -> lines.add(spec.shape() == ZoneShape.FLOOD
                            ? "连片区域：手持方块右键标记可添加或移除地板材质"
                            : "方框区域：宽 " + (spec.boxX() * 2 + 1) + "、高 " + (spec.boxY() + 1) + "、深 " + (spec.boxZ() * 2 + 1) + " · 空手右键调整");
                    default -> lines.add("站立点：玩家会站在这里，面朝盔甲架的朝向");
                }
            }
        }
        var draft = Workshop.get(level.getServer()).flatMap(w -> w.buildingIn(player).flatMap(d -> w.checks().result(d.id()))).orElse(null);
        if (draft != null) {
            var here = new dev.hardrush.core.lobby.plan.Cell(at.getX(), at.getY(), at.getZ());
            var below = here.offset(0, -1, 0);
            for (Problem p : draft.problems())
                if (here.equals(p.where()) || below.equals(p.where()))
                    lines.add((p.severity() == Severity.ERROR ? "问题：" : "提醒：") + p.message());
        }
        Notices.personal(player, String.join("\n", lines));
    }

    /**
     * A block was broken in a draft: the click, block and text markers that bound it go too (design 4.4 item 5). That
     * is a marker in the broken cell (now air, so it would rebind to the block below) or one standing on it.
     */
    static void blockBroken(ServerLevel level, BlockPos pos) {
        var box = new net.minecraft.world.phys.AABB(pos).expandTowards(0, 1, 0);
        for (var stand : level.getEntitiesOfClass(ArmorStand.class, box, MarkerStands::is)) {
            if (!(MarkerTags.parseMarker(stand.entityTags()) instanceof MarkerTags.Ok ok)) continue;
            var binding = ok.spec().binding();
            if (binding != Binding.CLICK && binding != Binding.BLOCK && binding != Binding.TEXT) continue;
            var at = stand.blockPosition();
            if (at.equals(pos) || at.equals(pos.above()) && level.getBlockState(at).isAir()) stand.discard();
        }
    }

    // ---- Spawning ------------------------------------------------------------------------------------------------

    /** A marker for {@code spec} on {@code target}, facing the builder; {@link MarkerStands#spawnOn} picks the cell. */
    static ArmorStand bindBlock(ServerPlayer player, BlockPos target, MarkerSpec spec) {
        return MarkerStands.spawnOn((ServerLevel) player.level(), spec, target, facing(player));
    }

    /** An interaction box standing on {@code target}, tagged as a click trigger for {@code function}. */
    static Interaction clickBox(ServerPlayer player, BlockPos target, int width, int height, LobbyFunction function) {
        var level = (ServerLevel) player.level();
        var box = new Interaction(EntityTypes.INTERACTION, level);
        box.snapTo(target.getX() + .5, target.getY() + 1, target.getZ() + .5, 0, 0);
        var data = new CompoundTag();
        data.put("width", FloatTag.valueOf(width));
        data.put("height", FloatTag.valueOf(height));
        data.put("response", ByteTag.ONE);
        var tags = new ListTag();
        tags.add(StringTag.valueOf(MarkerTags.FUNCTION + function.id()));
        data.put("Tags", tags);
        TypedEntityData.of(EntityTypes.INTERACTION, data).loadInto(box);
        level.addFreshEntity(box);
        return box;
    }

    /** Functions an entity can carry: click ones on anything, text ones on text displays only. */
    static List<LobbyFunction> entityFunctions(Entity entity) {
        var out = new ArrayList<LobbyFunction>();
        for (var f : LobbyFunction.values()) {
            if (f.bindings().contains(Binding.CLICK) || f.bindings().contains(Binding.TEXT) && entity instanceof Display.TextDisplay) out.add(f);
        }
        return out;
    }

    /** What a block can be bound as (design 4.4 item 3): block functions whose requirement it meets, text functions on signs, click functions anywhere. */
    static List<MarkerSpec> blockFunctions(BlockState state) {
        var out = new ArrayList<MarkerSpec>();
        var id = MarkerScan.blockId(state);
        for (var f : LobbyFunction.values()) {
            if (f.bindings().contains(Binding.BLOCK)) {
                if (f.block().map(r -> r.matches(id, MarkerScan.properties(state))).orElse(false))
                    out.add(MarkerSpec.simple(f, Binding.BLOCK));
            } else if (f.bindings().contains(Binding.TEXT)) {
                if (state.getBlock() instanceof SignBlock) out.add(MarkerSpec.simple(f, Binding.TEXT));
            } else if (f.bindings().contains(Binding.CLICK)) out.add(MarkerSpec.simple(f, Binding.CLICK));
        }
        return out;
    }

    /** Markers face the builder who placed them, like vanilla armor stands. */
    static float facing(ServerPlayer player) { return Math.round((player.getYRot() + 180) / 45f) * 45f % 360; }

    static String shortId(String id) { return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id; }

    // ---- The toolbox chest ---------------------------------------------------------------------------------------

    static void openToolbox(ServerPlayer player) {
        var workshop = Workshop.get(player.level().getServer()).orElse(null);
        if (workshop != null) ToolboxMenu.open(workshop, player);
    }

    static void useChecklist(ServerPlayer player) {
        Workshop.get(player.level().getServer()).ifPresent(workshop -> Checklist.use(workshop, player));
    }

    /** The marker items of one toolbox category, in function order. */
    static List<Button> markerButtons(ToolboxMenu.Category category) {
        var out = new ArrayList<Button>();
        for (var f : LobbyFunction.values()) {
            for (var binding : f.bindings()) {
                if (ToolboxMenu.Category.of(binding) != category) continue;
                if (binding == Binding.ZONE) {
                    out.add(new Button(MarkerStands.icon(f), MarkerStands.name(MarkerSpec.flood(f, Set.of())) + "连片区域", "marker",
                            f.id() + ":flood", List.of("放在地板上：同一层相连的同材质方块都算", "手持方块右键标记可加入更多材质")));
                    out.add(new Button(MarkerStands.icon(f), MarkerStands.name(MarkerSpec.box(f, 2, 2, 2)) + "方框区域", "marker",
                            f.id() + ":box", List.of("以标记为底面中心的方框，默认 5×3×5", "空手右键标记调整大小")));
                } else {
                    out.add(new Button(MarkerStands.icon(f), MarkerStands.name(MarkerSpec.simple(f, binding)) + MarkerTags.bindingName(binding),
                            "marker", f.id() + ":" + binding.tagName(), ToolboxMenu.Category.hint(binding)));
                }
            }
        }
        return out;
    }

    /** The marker item a {@code marker} button hands out. */
    static Optional<ItemStack> markerItem(String value) {
        var parts = value.split(":", 2);
        if (parts.length != 2) return Optional.empty();
        var f = LobbyFunction.byId(parts[0]).orElse(null);
        if (f == null) return Optional.empty();
        MarkerSpec spec = switch (parts[1]) {
            case "flood" -> MarkerSpec.flood(f, Set.of());
            case "box" -> MarkerSpec.box(f, 2, 2, 2);
            default -> {
                var binding = java.util.Arrays.stream(Binding.values()).filter(b -> b.tagName().equals(parts[1])).findFirst().orElse(null);
                yield binding == null || !f.bindings().contains(binding) || binding == Binding.ZONE ? null : MarkerSpec.simple(f, binding);
            }
        };
        return Optional.ofNullable(spec).map(MarkerStands::item);
    }
}
