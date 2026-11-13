package dev.hardrush.lobby;

import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.core.round.MonsterCoins;
import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ShopOffer;
import dev.hardrush.world.LobbyWorld;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * The camp witch's native trading screen. Coins in the payment slots are projections; taking the
 * result commits the purchase durably instead of handing out an item, and the wallet carries the
 * goods into the next sortie.
 */
public final class MonsterShop {
    private static final Map<MinecraftServer, RevivalTotems.Access> INSTANCES = new IdentityHashMap<>();
    private static final double REACH = 8;

    private MonsterShop() {}

    static void configure(MinecraftServer server, RevivalTotems.Access access) { INSTANCES.put(server, access); }
    static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); }

    public static boolean isShop(ServerPlayer player) { return player.containerMenu instanceof ShopMenu; }

    /** What the witch sells, also used for the camp projection of paid goods. */
    public static ItemStack goods(ShopOffer offer, dev.hardrush.core.config.RulesConfig rules) { return goods(offer, rules, 0); }

    /** {@code owned} names the next soulcatcher step; other goods ignore it. */
    private static ItemStack goods(ShopOffer offer, dev.hardrush.core.config.RulesConfig rules, int owned) {
        int arrows = MonsterCoins.amount(rules, MonsterCoins.ARROWS_PER_BUNDLE);
        var stack = switch (offer) {
            case ADULT_EGG, BABY_EGG -> new ItemStack(Items.ZOMBIE_SPAWN_EGG);
            case SWIFTNESS -> PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
            case FIRE_RESISTANCE -> PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE);
            case REGENERATION -> PotionContents.createItemStack(Items.POTION, Potions.REGENERATION);
            case SLOWNESS_ARROWS -> PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.SLOWNESS).copyWithCount(arrows);
            case POISON_ARROWS -> PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.POISON).copyWithCount(arrows);
            case CHARGED -> new ItemStack(Items.CREEPER_HEAD);
            case TRIAL_KEY -> new ItemStack(Items.TRIAL_KEY);
            case OMINOUS_KEY -> new ItemStack(Items.OMINOUS_TRIAL_KEY);
            case SOULCATCHER_TIER -> new ItemStack(Items.DYED_BUNDLE.pick(SOULCATCHER_COLORS[Math.min(owned + 1, 3)]));
            case SOULCATCHER_CAPACITY -> new ItemStack(Items.BUNDLE);
        };
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(offer.title())
                .withStyle(Style.EMPTY.withItalic(false).withColor(offer.key() ? ChatFormatting.AQUA
                        : offer.permanent() ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.YELLOW)));
        var lore = new ArrayList<Component>();
        lore.add(Component.literal(description(offer, owned)).withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GRAY)));
        if (offer.permanent()) lore.add(Component.literal("本轮以僵尸出击时一直有效 · 复生后清空")
                .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.DARK_GRAY)));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    private static final net.minecraft.world.item.DyeColor[] SOULCATCHER_COLORS = {net.minecraft.world.item.DyeColor.WHITE,
            net.minecraft.world.item.DyeColor.YELLOW, net.minecraft.world.item.DyeColor.CYAN, net.minecraft.world.item.DyeColor.PURPLE};
    private static final String[] SOULCATCHER_STEPS = {
            "升为罕见拘魂袋：可收编骷髅类、蜘蛛类、蠹虫、末影螨、史莱姆、岩浆怪、幻翼",
            "升为稀有拘魂袋：可收编苦力怕、女巫、掠夺者、卫道士、凋灵骷髅、烈焰人、猪灵、疣猪兽及中立与被动生物",
            "升为史诗拘魂袋：可收编唤魔者、劫掠兽、猪灵蛮兵、旋风人、恶魂、恼鬼、凋灵、监守者、远古守卫者"};

    private static String description(ShopOffer offer, int owned) {
        return switch (offer) {
            case ADULT_EGG -> "下次出击多带 1 枚成年僵尸刷怪蛋";
            case BABY_EGG -> "下次出击多带 1 枚幼年僵尸刷怪蛋";
            case SWIFTNESS -> "出击时获得迅捷（3:00）";
            case FIRE_RESISTANCE -> "出击时获得抗火（3:00）";
            case REGENERATION -> "出击时获得再生（0:45）· 爆炸后化作药水云";
            case SLOWNESS_ARROWS -> "下次出击时带上，射一箭消耗一支";
            case POISON_ARROWS -> "下次出击时带上，射一箭消耗一支";
            case CHARGED -> "下次出击成为闪电苦力怕，爆炸威力翻倍";
            case TRIAL_KEY -> "在庭院的试炼宝库开出一件装备";
            case OMINOUS_KEY -> "在庭院的不祥宝库开出一件装备，奖池更好";
            case SOULCATCHER_TIER -> SOULCATCHER_STEPS[Math.min(owned, SOULCATCHER_STEPS.length - 1)];
            case SOULCATCHER_CAPACITY -> "拘魂袋多装 1 只生物";
        };
    }

    public static void open(ServerPlayer player) {
        var access = INSTANCES.get(player.level().getServer());
        var r = access == null ? null : access.committed().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p == null || !MonsterCoins.enabled(r.rules()) || !access.eligible(player)) {
            tell(player, "女巫：我只和庭院里待命的怪物做生意"); sound(player, SoundEvents.WITCH_AMBIENT); return;
        }
        if (LobbyRuntime.revivalFirst(player, r, p)) return;
        if (!p.wallet().roleChosen()) {
            tell(player, "女巫：先去展位选个角色，再来找我"); sound(player, SoundEvents.WITCH_AMBIENT); return;
        }
        var trader = new Trader(player, p.monsterRole());
        trader.rebuild(r.rules(), p);
        var id = player.openMenu(new SimpleMenuProvider((containerId, inventory, ignored) ->
                new ShopMenu(containerId, inventory, trader), Component.literal("女巫商人")));
        if (id.isPresent()) player.sendMerchantOffers(id.getAsInt(), trader.offers, 1, 0, false, false);
    }

    private static void purchase(ServerPlayer player, ShopMenu menu, ShopOffer offer, int price) {
        var access = INSTANCES.get(player.level().getServer());
        var r = access == null ? null : access.committed().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p == null || !access.eligible(player) || access.busy(p.id())) { menu.resync(); return; }
        menu.buying = true;
        access.session().requestShopPurchase(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(),
                offer, price, reply -> {
                    menu.buying = false;
                    if (player.containerMenu != menu) return;
                    if (!reply.success()) { tell(player, dev.hardrush.notice.Replies.text(reply, "女巫：这笔交易现在做不了")); sound(player, SoundEvents.VILLAGER_NO); menu.resync(); return; }
                    sound(player, SoundEvents.WITCH_CELEBRATE);
                    // The witch's cheer, the coin count and the goods' description tell the rest.
                    menu.afterPurchase();
                });
    }

    private static void tell(ServerPlayer player, String message) { dev.hardrush.notice.Notices.hint(player, message); }

    private static void sound(ServerPlayer player, SoundEvent sound) {
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.NEUTRAL, 1, 1);
    }

    /** A server-only merchant; there is no entity, so trading never changes a villager or the world. */
    static final class Trader implements Merchant {
        private final ServerPlayer player;
        private final MonsterRole role;
        private MerchantOffers offers = new MerchantOffers();
        private final Map<MerchantOffer, ShopOffer> mapping = new IdentityHashMap<>();

        Trader(ServerPlayer player, MonsterRole role) { this.player = player; this.role = role; }

        void rebuild(dev.hardrush.core.config.RulesConfig rules, Participant buyer) {
            var built = new MerchantOffers();
            mapping.clear();
            for (var offer : ShopOffer.values()) {
                if (!offer.sellableTo(role) || !MonsterCoins.offered(rules, offer)) continue;
                int bought = buyer.wallet().bought(offer), limit = MonsterCoins.limit(rules, offer);
                int base = MonsterCoins.price(rules, offer, 0);
                int price = MonsterCoins.price(rules, offer, Math.min(bought, limit - 1));
                var goods = goods(offer, rules, Math.min(bought, limit - 1));
                MerchantOffer trade;
                if (price <= MonsterCoins.STACK_PRICE) {
                    trade = new MerchantOffer(coins(base), Optional.empty(), goods, Math.min(bought, limit), limit, 0, 0);
                    // The native screen strikes through the base price once the doubling ladder has started.
                    trade.setSpecialPriceDiff(price - base);
                } else {
                    // One native stack per payment slot: the remainder asks for the second slot.
                    trade = new MerchantOffer(coins(MonsterCoins.STACK_PRICE), Optional.of(coins(price - MonsterCoins.STACK_PRICE)),
                            goods, Math.min(bought, limit), limit, 0, 0);
                }
                built.add(trade);
                mapping.put(trade, offer);
            }
            offers = built;
        }

        private static ItemCost coins(int count) {
            return new ItemCost(Items.EMERALD, count).withComponents(builder -> builder
                    .expect(DataComponents.CUSTOM_DATA, CampKit.coin(1).get(DataComponents.CUSTOM_DATA))
                    .expect(DataComponents.CUSTOM_NAME, CampKit.coinName()));
        }

        @Override public void setTradingPlayer(Player player) {}
        @Override public Player getTradingPlayer() { return player; }
        @Override public MerchantOffers getOffers() { return offers; }
        @Override public void overrideOffers(MerchantOffers offers) {}
        @Override public void notifyTrade(MerchantOffer offer) {}
        @Override public void notifyTradeUpdated(ItemStack stack) {}
        @Override public int getVillagerXp() { return 0; }
        @Override public void overrideXp(int xp) {}
        @Override public boolean showProgressBar() { return false; }
        @Override public SoundEvent getNotifyTradeSound() { return SoundEvents.WITCH_CELEBRATE; }
        @Override public boolean isClientSide() { return false; }
        @Override public boolean stillValid(Player player) { return true; }
    }

    /** Only native offer selection and a click on the result slot reach the trade. */
    public static final class ShopMenu extends MerchantMenu {
        private final Trader trader;
        private boolean buying;

        ShopMenu(int containerId, Inventory inventory, Trader trader) {
            super(containerId, inventory, trader);
            this.trader = trader;
        }

        @Override public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (slot == 2 && !buying && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE)
                    && player instanceof ServerPlayer server
                    && slots.get(2).container instanceof MerchantContainer container && container.getActiveOffer() != null
                    && !slots.get(2).getItem().isEmpty()) {
                var active = container.getActiveOffer();
                var offer = trader.mapping.get(active);
                if (offer != null) {
                    purchase(server, this, offer, active.getCostA().getCount() + active.getCostB().getCount()); resync(); return;
                }
            }
            resync();
        }

        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }

        @Override public boolean stillValid(Player player) {
            if (!(player instanceof ServerPlayer server)) return false;
            var access = INSTANCES.get(server.level().getServer());
            var r = access == null ? null : access.committed().orElse(null);
            var p = r == null ? null : r.participants().get(server.getUUID());
            if (p == null || !access.eligible(server) || p.monsterRole() != trader.role || !p.wallet().roleChosen()) return false;
            var point = LobbyLayout.facility(LobbyLayout.Facility.MERCHANT);
            return server.position().distanceToSqr(LobbyWorld.open(server.level().getServer()).layoutPosition(point)) <= REACH * REACH;
        }

        /** Projected coins never return through the native payment hand-back. */
        @Override public void removed(Player player) {
            slots.get(0).set(ItemStack.EMPTY); slots.get(1).set(ItemStack.EMPTY);
            super.removed(player);
            if (player instanceof ServerPlayer server) CampKit.refresh(server);
        }

        void afterPurchase() {
            var server = (ServerPlayer) trader.player;
            slots.get(0).set(ItemStack.EMPTY); slots.get(1).set(ItemStack.EMPTY);
            var access = INSTANCES.get(server.level().getServer());
            var r = access.committed().orElseThrow();
            trader.rebuild(r.rules(), r.participants().get(server.getUUID()));
            CampKit.refresh(server);
            server.sendMerchantOffers(containerId, trader.offers, 1, 0, false, false);
            resync();
        }

        void resync() { sendAllDataToRemote(); }
    }
}
