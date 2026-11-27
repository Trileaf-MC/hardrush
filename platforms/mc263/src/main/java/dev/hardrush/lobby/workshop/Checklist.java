package dev.hardrush.lobby.workshop;

import dev.hardrush.core.lobby.plan.Binding;
import dev.hardrush.core.lobby.plan.MarkerSpec;
import dev.hardrush.core.lobby.plan.PublishBlockers;
import dev.hardrush.lobby.pack.MarkerStands;
import dev.hardrush.notice.ItemText;
import dev.hardrush.notice.Notices;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;

/**
 * The builder's checklist (design 9): a written book in hotbar slot 7 whose name says how many problems still block
 * publishing and whose description names them. Right-clicking it re-checks the draft, puts the marker items and
 * blocks that fix the missing functions into the hotbar (the rest into the backpack), and lists every problem in chat.
 * The toolbox's checklist page keeps the per-problem teleports.
 */
final class Checklist {
    static final String USAGE = "右键：补齐缺项用的标记放进快捷栏，并列出所有问题";
    /** Problems named in the item's description before "and N more"; chat lines of problems, missing functions folded into one. */
    static final int SHOWN_IN_LORE = 6, SHOWN_IN_CHAT = 6;
    /** Hotbar slots tools may take: every one left of the kept tools. */
    static final int TOOL_SLOTS = Toolkit.CHECKLIST_SLOT;

    private Checklist() {}

    /** The checklist for the draft's latest check; {@code result} is null before the first one. */
    static ItemStack item(WorkshopChecks.Result result) {
        String name;
        var lore = new ArrayList<String>();
        lore.add(USAGE);
        if (result == null) name = "验收清单（检查中…）";
        else {
            var blocking = result.blocking();
            name = blocking.isEmpty() ? "验收清单：可以发布" : "验收清单：还有 " + blocking.size() + " 项挡着发布";
            var labels = blocking.stream().map(PublishBlockers::label).distinct().toList();
            for (int i = 0; i < Math.min(SHOWN_IN_LORE, labels.size()); i++) lore.add("· " + labels.get(i));
            if (labels.size() > SHOWN_IN_LORE) lore.add("· …还有 " + (labels.size() - SHOWN_IN_LORE) + " 种");
            if (result.warnings() > 0) lore.add("另有 " + result.warnings() + " 条提醒，不影响发布");
        }
        lore.add("只统计附近已加载区域里的标记");
        return Toolkit.tool(new ItemStack(Items.WRITTEN_BOOK), Toolkit.CHECKLIST, name, lore);
    }

    /** Right-clicked: check now, hand out the tools for what is missing, and list every problem. */
    static void use(Workshop workshop, ServerPlayer player) {
        player.inventoryMenu.sendAllDataToRemote();
        var draft = workshop.buildingIn(player).orElse(null);
        var level = draft == null ? null : workshop.level(draft.id()).orElse(null);
        if (level == null) return;
        var result = workshop.checks().check(workshop, draft, level);
        var blocking = result.blocking();
        if (blocking.isEmpty()) {
            Notices.hint(player, "附近没有挡着发布的问题" + (result.warnings() > 0 ? " · " + result.warnings() + " 条提醒在工具箱的验收清单里" : "")
                    + " · 正式发布时会检查整个范围");
            return;
        }
        var tools = tools(PublishBlockers.needs(blocking, result::count));
        var handed = hand(player, tools);
        var lines = new ArrayList<String>();
        lines.add("「" + draft.name() + "」还有 " + Notices.key(blocking.size()) + " 项挡着发布：");
        for (var line : PublishBlockers.chatLines(blocking, SHOWN_IN_CHAT)) lines.add("· " + line);
        if (!tools.isEmpty()) lines.add(handed == tools.size() ? "补齐用的 " + Notices.key(tools.size()) + " 件工具已放进快捷栏"
                : "补齐用的工具：" + Notices.key(handed) + " 件在快捷栏，其余 " + Notices.key(tools.size() - handed) + " 件在背包里");
        Notices.personal(player, String.join("\n", lines));
    }

    /** The marker items, then the blocks, that {@code needs} calls for. */
    static List<ItemStack> tools(List<PublishBlockers.Need> needs) {
        var markers = new ArrayList<ItemStack>();
        var blocks = new ArrayList<ItemStack>();
        for (var need : needs) {
            var f = need.function();
            if (need.markers() > 0) {
                var spec = need.binding() == Binding.ZONE ? MarkerSpec.flood(f, Set.of()) : MarkerSpec.simple(f, need.binding());
                var marker = MarkerStands.item(spec);
                marker.setCount(Math.min(need.markers(), marker.getMaxStackSize()));
                markers.add(marker);
            }
            if (need.block()) blocks.add(block(need));
        }
        markers.addAll(blocks);
        return markers;
    }

    /** The block a function must be bound to: its required block in the required state, or a sign for text. */
    private static ItemStack block(PublishBlockers.Need need) {
        var f = need.function();
        var requirement = f.block().orElse(null);
        if (requirement == null) {
            var sign = new ItemStack(Items.OAK_SIGN, Math.max(1, Math.min(need.markers(), 16)));
            return ItemText.lore(sign, "给 " + Notices.key("【" + f.displayName() + "】") + " 用：放好后把它的标记放上去");
        }
        var item = BuiltInRegistries.BLOCK.getValue(Identifier.parse(requirement.id())).asItem();
        var stack = new ItemStack(item, Math.max(1, Math.min(need.markers(), item.getDefaultMaxStackSize())));
        if (!requirement.properties().isEmpty()) {
            stack.set(DataComponents.BLOCK_STATE, new BlockItemStateProperties(requirement.properties()));
            // Vanilla names both vault states alike; the function's name tells them apart.
            ItemText.name(stack, f.displayName());
        }
        return ItemText.lore(stack, "给 " + Notices.key("【" + f.displayName() + "】") + " 用：放好后用绑定杖右键它，或把标记放上去");
    }

    /**
     * Puts {@code tools} into the hotbar left of the kept tools, earlier ones first: copies the builder already carries
     * are replaced, an empty slot is used before an occupied one, whose item moves to the backpack. What finds no hotbar
     * slot goes to the backpack. Returns how many went into the hotbar.
     */
    static int hand(ServerPlayer player, List<ItemStack> tools) {
        var inventory = player.getInventory();
        for (var tool : tools)
            for (int i = 0; i < inventory.getContainerSize(); i++)
                if (ItemStack.isSameItemSameComponents(inventory.getItem(i), tool)) inventory.setItem(i, ItemStack.EMPTY);
        int handed = 0;
        var placed = new boolean[TOOL_SLOTS];
        for (var tool : tools) {
            int slot = -1;
            for (int i = 0; i < TOOL_SLOTS && slot < 0; i++) if (!placed[i] && inventory.getItem(i).isEmpty()) slot = i;
            for (int i = 0; i < TOOL_SLOTS && slot < 0; i++) if (!placed[i]) slot = i;
            if (slot < 0) { toBackpack(inventory, tool.copy()); continue; }
            var displaced = inventory.getItem(slot);
            inventory.setItem(slot, tool.copy());
            placed[slot] = true;
            handed++;
            // A full creative backpack loses the displaced stack; creative items cost nothing.
            if (!displaced.isEmpty()) toBackpack(inventory, displaced);
        }
        player.inventoryMenu.sendAllDataToRemote();
        return handed;
    }

    /** The first free slot of the backpack proper (never the hotbar), or nowhere. */
    private static void toBackpack(Inventory inventory, ItemStack stack) {
        for (int i = Inventory.SELECTION_SIZE; i < Inventory.INVENTORY_SIZE; i++)
            if (inventory.getItem(i).isEmpty()) { inventory.setItem(i, stack); return; }
    }
}
