package dev.hardrush.lobby;

import dev.hardrush.core.round.MonsterCoins;
import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.MonsterWallet;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ShopOffer;
import dev.hardrush.monster.MonsterGacha;
import dev.hardrush.notice.ItemText;
import dev.hardrush.notice.Notices;
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
    private static final String USAGE = "hardrush_camp_kit_usage";
    /** Key prefix of a vault piece's projection, followed by its wallet serial. */
    private static final String GEAR = "gear:";
    private static final String COIN_USAGE = "在庭院右键女巫，购买出击增强";
    /** Coins beyond three stacks are still counted; the lore names the true total. */
    public static final int MAX_SHOWN_COINS = 3 * 64;
    private static final Map<MinecraftServer, CampAccess> INSTANCES = new IdentityHashMap<>();
    /** Players watching a vault animation; their new gear appears when it flies into the backpack. */
    private static final Set<UUID> HELD = new HashSet<>();
    /** Players whose newly put-on vault piece is being committed as the worn one; their arrangement stays meanwhile. */
    private static final Set<UUID> WEARING = new HashSet<>();
    /** What the projection last wore on each body: only a different piece in that slot is the player's own choice. */
    private static final Map<ServerPlayer, Map<EquipmentSlot, String>> PROJECTED_WORN = new WeakHashMap<>();

    private CampKit() {}

    static void configure(MinecraftServer server, CampAccess access) { INSTANCES.put(server, access); }
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

    private static boolean sellable(Set<MonsterRole> roles, MonsterRole role) { return role != null && roles.contains(role); }

    /**
     * The projection's usage leads the description, ahead of what the witch's goods say; the same line is kept in the
     * tag so the held-item hint reads exactly what the description says.
     */
    private static ItemStack tagged(ItemStack stack, String key, String usage, List<String> details) {
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.putString(KEY, key);
        data.putString(USAGE, usage);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        var lines = new ArrayList<Component>(ItemText.lines(java.util.stream.Stream.concat(java.util.stream.Stream.of(usage), details.stream()).toList()));
        stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().forEach(lines::add);
        stack.set(DataComponents.LORE, new ItemLore(lines));
        return stack;
    }

    /** What a coin, a paid order or a piece of vault gear is for, on the action bar while held. */
    public static Optional<String> heldHint(ServerPlayer player, ItemStack stack) {
        if (isCoin(stack)) return Optional.of(COIN_USAGE);
        if (!is(stack)) return Optional.empty();
        var usage = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr(USAGE, "");
        return usage.isEmpty() ? Optional.empty() : Optional.of(usage);
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
            var wants = shown ? desired(player, r.rules(), p) : List.<Want>of();
            if (!shown || !wear(access, player, r, p, wants)) converge(player, wants);
        }
    }

    /**
     * The server refused a sealed or protected body's item use. A camp monster's vault piece still goes on natively, in
     * place of the one worn, and the choice is recorded on the next tick like any other way of putting it on; any other
     * armour swap the client predicted is undone.
     */
    public static void refusedUse(ServerPlayer player, net.minecraft.world.InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        var access = INSTANCES.get(player.level().getServer());
        if (access != null && is(stack) && tag(stack).startsWith(GEAR) && access.eligible(player)
                && dev.hardrush.player.NativeArmorSwap.swap(player, hand)) return;
        dev.hardrush.player.NativeArmorSwap.refuse(player, hand);
    }

    /**
     * Native equipping (a right click, a drag or a shift click onto the armour slot) of another piece the role can
     * wear there makes that piece the worn one in the wallet, instead of the projection taking it off again. True while
     * a choice waits to commit: converging then would undo it.
     */
    private static boolean wear(CampAccess access, ServerPlayer player, dev.hardrush.core.round.RoundSnapshot r, Participant p,
                                List<Want> wants) {
        if (WEARING.contains(player.getUUID())) return true;
        var projected = PROJECTED_WORN.get(player);
        if (projected == null) return false;
        for (var want : wants) {
            if (want.fits == null || want.slot == want.fits || !want.key.equals(tag(player.getItemBySlot(want.fits)))) continue;
            // A newer vault piece replaces the one the projection itself put on; that one is no choice.
            if (want.key.equals(projected.get(want.fits))) continue;
            if (access.busy(p.id()) || access.session().mutationPending()) return true;
            WEARING.add(player.getUUID());
            int serial = Integer.parseInt(want.key.substring(GEAR.length()));
            access.session().requestGearWorn(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), serial, reply -> {
                WEARING.remove(player.getUUID());
                if (!reply.success()) Notices.hint(player, dev.hardrush.notice.Replies.text(reply, "没能换上这件装备"));
                refresh(player);
            });
            return true;
        }
        return false;
    }

    /** Immediately re-project after a purchase or vault opening, even with the shop menu open. */
    public static void refresh(ServerPlayer player) {
        var access = INSTANCES.get(player.level().getServer());
        if (access == null) return;
        var r = access.committed().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p != null && MonsterCoins.enabled(r.rules()) && access.eligible(player)) converge(player, desired(player, r.rules(), p));
    }

    /**
     * @param slot where the projection wears the stack, or null for the backpack
     * @param fits the armour slot the current role may wear this vault piece in, or null
     */
    private record Want(String key, ItemStack stack, EquipmentSlot slot, EquipmentSlot fits) {
        Want(String key, ItemStack stack) { this(key, stack, null, null); }
    }

    private static List<Want> desired(ServerPlayer player, dev.hardrush.core.config.RulesConfig rules, Participant p) {
        var wallet = p.wallet();
        var wants = new ArrayList<Want>();
        int available = Math.max(0, wallet.coins() - CampTransfers.pending(player));
        int shown = Math.min(available, MAX_SHOWN_COINS);
        if (shown > 0) {
            var coins = coin(shown);
            ItemText.lore(coins, COIN_USAGE, "共 " + Notices.key(available) + " 枚",
                    "丢出可送给其他怪物 · 落地 5 分钟无人拾取即作废", "本轮结束时清空");
            wants.add(new Want("coin", coins));
        }
        for (var entry : wallet.order().entrySet()) {
            var offer = entry.getKey();
            var stack = MonsterShop.goods(offer, rules, p.monsterRole());
            stack.setCount(Math.min(stack.getMaxStackSize(), stack.getCount() * entry.getValue()));
            String key = "order:" + offer.id();
            if (offer.revival()) {
                wants.add(new Want(key, tagged(stack, key, offer.landing() ? "落地时生效"
                        : offer == dev.hardrush.core.round.ShopOffer.SHIELD ? "复生时放进副手" : "复生时放进背包",
                        List.of("已购 ×" + Notices.key(entry.getValue()) + " · 复生后可用"))));
                continue;
            }
            wants.add(new Want(key, offer.key() ? tagged(stack, key, "手持右键庭院的宝库，开出一件装备", List.of())
                    : tagged(stack, key, sellable(offer.roles(), p.monsterRole()) ? "下次出击时自动带上"
                            : "以 " + Notices.key(roles(offer.roles())) + " 出击时才会带上",
                            List.of("已购 ×" + Notices.key(entry.getValue()) + " · " + (offer.keptUntilUsed()
                                    ? "出击中倒下才算用掉，返回营地、换角色都保留" : "出击结束时用剩的作废，返回营地则随身暂存")))));
        }
        // Each slot shows the newest wearable piece on the body, the one last put on; the rest wait in the backpack.
        var worn = EnumSet.noneOf(EquipmentSlot.class);
        var gear = new ArrayList<>(wallet.gear());
        gear.sort(Comparator.comparingInt(MonsterWallet.GearPiece::serial).reversed());
        for (var piece : gear) {
            var stack = MonsterGacha.decode(player.level().registryAccess(), piece.item());
            if (stack.isEmpty()) continue;
            var slot = MonsterGacha.equipmentSlot(piece.slot());
            var fits = piece.wearableBy(p.monsterRole()) && slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? slot : null;
            boolean wear = fits != null && worn.add(slot);
            String key = GEAR + piece.serial();
            String verb = slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? "穿上" : "带上";
            wants.add(new Want(key, tagged(stack, key, piece.wearableBy(p.monsterRole()) ? "下次出击时" + verb + "，只用一次"
                    : "以 " + Notices.key(roles(piece.roles())) + " 出击时才会" + verb, List.of()), wear ? slot : null, fits));
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

    /** Rebuilds only when the projected keys, counts or worn slots differ; personal sorting survives. */
    private static void converge(ServerPlayer player, List<Want> wants) {
        var inventory = player.getInventory();
        var craft = player.inventoryMenu.getCraftSlots();
        var present = new HashMap<String, Integer>();
        var coinTemplate = wants.stream().filter(want -> want.key.equals("coin")).map(Want::stack).findFirst().orElse(ItemStack.EMPTY);
        boolean extra = false;
        for (int i = 0; i < inventory.getContainerSize(); i++) extra |= count(present, inventory.getItem(i), coinTemplate);
        for (int i = 0; i < craft.getContainerSize(); i++) extra |= count(present, craft.getItem(i), coinTemplate);
        extra |= count(present, player.containerMenu.getCarried(), coinTemplate);
        var expected = new HashMap<String, Integer>();
        boolean matches = !extra;
        for (var want : wants) {
            expected.put(want.key, want.stack.getCount());
            if (want.slot != null && !want.key.equals(tag(player.getItemBySlot(want.slot)))) matches = false;
        }
        var worn = new EnumMap<EquipmentSlot, String>(EquipmentSlot.class);
        for (var want : wants) if (want.slot != null) worn.put(want.slot, want.key);
        PROJECTED_WORN.put(player, worn);
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
    private static boolean count(Map<String, Integer> present, ItemStack stack, ItemStack coinTemplate) {
        if (!is(stack)) return false;
        String key = isCoin(stack) ? "coin" : tag(stack);
        if (key.isEmpty()) return true;
        present.merge(key, stack.getCount(), Integer::sum);
        // The displayed three stacks may stay full while a transfer changes the true balance.
        return isCoin(stack) && !ItemStack.isSameItemSameComponents(stack, coinTemplate);
    }
}
