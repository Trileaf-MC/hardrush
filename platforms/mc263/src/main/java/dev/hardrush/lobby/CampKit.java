package dev.hardrush.lobby;

import dev.hardrush.core.round.MonsterCoins;
import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.MonsterWallet;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ShopOffer;
import dev.hardrush.monster.MonsterGacha;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * A camp monster's coins, paid orders, unopened keys and vault gear, projected from the committed
 * wallet. None of these stacks is a real possession: they converge to the snapshot every tick, and
 * purchases, vault openings and deployments read the wallet rather than the inventory.
 */
public final class CampKit {
    private static final String KEY = "hardrush_camp_kit";
    private static final String COIN = "hardrush_monster_coin";
    /** Coins beyond three stacks are still counted; the lore names the true total. */
    public static final int MAX_SHOWN_COINS = 3 * 64;
    private static final Map<MinecraftServer, RevivalTotems.Access> INSTANCES = new IdentityHashMap<>();
    /** Players watching a vault animation; their new gear appears when it flies into the backpack. */
    private static final Set<UUID> HELD = new HashSet<>();

    private CampKit() {}

    static void configure(MinecraftServer server, RevivalTotems.Access access) { INSTANCES.put(server, access); }
    static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); }

    static void hold(ServerPlayer player, boolean held) {
        if (held) HELD.add(player.getUUID()); else HELD.remove(player.getUUID());
    }

    /** Identical components on every coin stack, so one trade cost predicate matches them all. */
    public static ItemStack coin(int count) {
        var stack = new ItemStack(Items.EMERALD, count);
        var data = new CompoundTag(); data.putBoolean(COIN, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        stack.set(DataComponents.CUSTOM_NAME, coinName());
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    public static Component coinName() {
        return Component.literal("怪物币").withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GREEN));
    }

    public static boolean isCoin(ItemStack stack) {
        return stack.is(Items.EMERALD) && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(COIN);
    }

    public static boolean is(ItemStack stack) {
        return !stack.isEmpty() && (isCoin(stack)
                || stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(KEY));
    }

    /** The projected key a player must hold to open a vault of this tier. */
    public static boolean holdsKey(ServerPlayer player, ShopOffer key) {
        var held = player.getMainHandItem();
        return is(held) && ("order:" + key.id()).equals(tag(held));
    }

    private static String tag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr(KEY, "");
    }

    private static ItemStack tagged(ItemStack stack, String key, List<String> lore) {
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.putString(KEY, key);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        var lines = new ArrayList<Component>();
        stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().forEach(lines::add);
        lore.forEach(line -> lines.add(Component.literal(line).withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GRAY))));
        stack.set(DataComponents.LORE, new ItemLore(lines));
        return stack;
    }

    public static void tick(MinecraftServer server) {
        var access = INSTANCES.get(server);
        if (access == null) return;
        var r = access.committed().orElse(null);
        for (var player : server.getPlayerList().getPlayers()) {
            // Isolated camp bodies may skip native menu validation; the witch's menu is enforced here.
            if (player.containerMenu instanceof MonsterShop.ShopMenu shop) { if (!shop.stillValid(player)) player.closeContainer(); continue; }
            if (HELD.contains(player.getUUID())) continue;
            var p = r == null ? null : r.participants().get(player.getUUID());
            boolean shown = p != null && MonsterCoins.enabled(r.rules()) && access.eligible(player);
            converge(player, shown ? desired(player, r.rules(), p) : List.of());
        }
    }

    /** Immediately re-project after a purchase or vault opening, even with the shop menu open. */
    public static void refresh(ServerPlayer player) {
        var access = INSTANCES.get(player.level().getServer());
        if (access == null) return;
        var r = access.committed().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p != null && MonsterCoins.enabled(r.rules()) && access.eligible(player)) converge(player, desired(player, r.rules(), p));
    }

    private record Want(String key, ItemStack stack, EquipmentSlot slot) {}

    private static List<Want> desired(ServerPlayer player, dev.hardrush.core.config.RulesConfig rules, Participant p) {
        var wallet = p.wallet();
        var wants = new ArrayList<Want>();
        int shown = Math.min(wallet.coins(), MAX_SHOWN_COINS);
        if (shown > 0) {
            var coins = coin(shown);
            coins.set(DataComponents.LORE, new ItemLore(List.of(gray("共 " + wallet.coins() + " 枚怪物币"),
                    gray("向营地的女巫商人购买下次出场的增强"), gray("本轮结束清空；不能丢出或转赠"))));
            wants.add(new Want("coin", coins, null));
        }
        for (var entry : wallet.order().entrySet()) {
            var offer = entry.getKey();
            var stack = MonsterShop.goods(offer, rules);
            stack.setCount(Math.min(stack.getMaxStackSize(), stack.getCount() * entry.getValue()));
            wants.add(new Want("order:" + offer.id(), tagged(stack, "order:" + offer.id(), List.of(offer.key()
                    ? "手持对准营地宝库右键，抽取一件装备"
                    : "已购 ×" + entry.getValue() + "：下次出场自动带入，用剩即清除")), null));
        }
        // Each slot shows the newest wearable piece on the body; the rest wait in the backpack.
        var worn = EnumSet.noneOf(EquipmentSlot.class);
        var gear = new ArrayList<>(wallet.gear());
        gear.sort(Comparator.comparingInt(MonsterWallet.GearPiece::serial).reversed());
        for (var piece : gear) {
            var stack = MonsterGacha.decode(player.level().registryAccess(), piece.item());
            if (stack.isEmpty()) continue;
            var slot = MonsterGacha.equipmentSlot(piece.slot());
            boolean wear = piece.wearableBy(p.monsterRole()) && slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && worn.add(slot);
            String key = "gear:" + piece.serial();
            wants.add(new Want(key, tagged(stack, key, List.of(piece.wearableBy(p.monsterRole())
                    ? "宝库装备：下次出场穿戴，只用一次"
                    : "宝库装备：切换为" + roles(piece.roles()) + "时才会带出")), wear ? slot : null));
        }
        return wants;
    }

    private static String roles(Set<MonsterRole> roles) {
        var names = new ArrayList<String>();
        for (var role : MonsterRole.values()) if (roles.contains(role)) names.add(switch (role) {
            case ZOMBIE -> "僵尸"; case SKELETON -> "骷髅"; case CREEPER -> "苦力怕";
        });
        return String.join("或", names);
    }

    private static Component gray(String line) {
        return Component.literal(line).withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GRAY));
    }

    /** Rebuilds only when the projected keys, counts or worn slots differ; personal sorting survives. */
    private static void converge(ServerPlayer player, List<Want> wants) {
        var inventory = player.getInventory();
        var craft = player.inventoryMenu.getCraftSlots();
        var present = new HashMap<String, Integer>();
        boolean extra = false;
        for (int i = 0; i < inventory.getContainerSize(); i++) extra |= count(present, inventory.getItem(i));
        for (int i = 0; i < craft.getContainerSize(); i++) extra |= count(present, craft.getItem(i));
        extra |= count(present, player.containerMenu.getCarried());
        var expected = new HashMap<String, Integer>();
        boolean matches = !extra;
        for (var want : wants) {
            expected.put(want.key, want.stack.getCount());
            if (want.slot != null && !want.key.equals(tag(player.getItemBySlot(want.slot)))) matches = false;
        }
        if (matches && expected.equals(present)) return;
        for (int i = 0; i < inventory.getContainerSize(); i++) if (is(inventory.getItem(i))) inventory.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < craft.getContainerSize(); i++) if (is(craft.getItem(i))) craft.setItem(i, ItemStack.EMPTY);
        if (is(player.containerMenu.getCarried())) player.containerMenu.setCarried(ItemStack.EMPTY);
        for (var want : wants) {
            if (want.slot != null && player.getItemBySlot(want.slot).isEmpty()) { player.setItemSlot(want.slot, want.stack.copy()); continue; }
            var stack = want.stack.copy();
            while (!stack.isEmpty()) {
                var part = stack.split(Math.min(stack.getCount(), stack.getMaxStackSize()));
                if (!inventory.add(part)) break;
            }
        }
        player.inventoryMenu.broadcastChanges();
    }

    /** Returns true for a projected stack that no longer belongs to the current projection format. */
    private static boolean count(Map<String, Integer> present, ItemStack stack) {
        if (!is(stack)) return false;
        String key = isCoin(stack) ? "coin" : tag(stack);
        if (key.isEmpty()) return true;
        present.merge(key, stack.getCount(), Integer::sum);
        return false;
    }
}
