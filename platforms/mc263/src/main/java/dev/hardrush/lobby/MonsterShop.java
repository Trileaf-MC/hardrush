package dev.hardrush.lobby;

import dev.hardrush.core.lobby.plan.LobbyFunction;
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
    private static final Map<MinecraftServer, CampAccess> INSTANCES = new IdentityHashMap<>();
    private static final double REACH = 8;

    private MonsterShop() {}

    static void configure(MinecraftServer server, CampAccess access) { INSTANCES.put(server, access); }
    static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); }

    public static boolean isShop(ServerPlayer player) { return player.containerMenu instanceof ShopMenu; }

    /**
     * What the witch sells, also used for the camp projection of paid goods. {@code role} picks between the undead's
     * potion of harming and the creeper's potion of healing.
     */
    public static ItemStack goods(ShopOffer offer, dev.hardrush.core.config.RulesConfig rules, MonsterRole role) {
        return goods(offer, rules, role, 0);
    }

    /** A witch potion as a sortie carries it: the shop's name, and the drinking line ahead of the witch's notes. */
    public static ItemStack sortiePotion(ShopOffer offer, dev.hardrush.core.config.RulesConfig rules, MonsterRole role) {
        var stack = dev.hardrush.monster.MonsterPotions.bottle(offer, rules, role);
        stack.set(DataComponents.CUSTOM_NAME, name(offer, role));
        var lines = new ArrayList<String>();
        lines.add("按住右键喝下");
        lines.addAll(dev.hardrush.monster.MonsterPotions.notes(offer));
        return dev.hardrush.notice.ItemText.lore(stack, lines);
    }

    private static Component name(ShopOffer offer, MonsterRole role) {
        var color = offer.potionBox().map(box -> switch (box) {
            case COMMON -> ChatFormatting.WHITE; case RARE -> ChatFormatting.AQUA; case EPIC -> ChatFormatting.LIGHT_PURPLE;
        }).orElse(offer.key() ? ChatFormatting.AQUA : offer.permanent() ? ChatFormatting.LIGHT_PURPLE
                : offer.revival() ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        return Component.literal(offer.potion()
                ? dev.hardrush.monster.MonsterPotions.title(offer, role) : offer.title())
                .withStyle(Style.EMPTY.withItalic(false).withColor(color));
    }

    /** {@code owned} names the next soulcatcher step; other goods ignore it. */
    private static ItemStack goods(ShopOffer offer, dev.hardrush.core.config.RulesConfig rules, MonsterRole role, int owned) {
        int arrows = MonsterCoins.amount(rules, MonsterCoins.ARROWS_PER_BUNDLE);
        var stack = offer.potion() ? dev.hardrush.monster.MonsterPotions.bottle(offer, rules, role) : switch (offer) {
            case ADULT_EGG, BABY_EGG -> new ItemStack(Items.ZOMBIE_SPAWN_EGG);
            case MONSTER_BOX -> {
                var box = new ItemStack(Items.ZOMBIE_SPAWN_EGG);
                box.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                yield box;
            }
            case SLOWNESS_ARROWS -> PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.SLOWNESS).copyWithCount(arrows);
            case POISON_ARROWS -> PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.POISON).copyWithCount(arrows);
            case TRIAL_KEY -> new ItemStack(Items.TRIAL_KEY);
            case OMINOUS_KEY -> new ItemStack(Items.OMINOUS_TRIAL_KEY);
            case SOULCATCHER_TIER -> new ItemStack(Items.DYED_BUNDLE.pick(SOULCATCHER_COLORS[Math.min(owned + 1, 3)]));
            case SOULCATCHER_CAPACITY -> new ItemStack(Items.BUNDLE);
            case BONUS_CHEST -> new ItemStack(Items.CHEST);
            case LONGER_BLESSING -> new ItemStack(Items.ENCHANTED_GOLDEN_APPLE);
            case IRON_BUCKET, OAK_LOGS, COAL, IRON_PICKAXE, SHIELD, BELL, BREAD -> dev.hardrush.revival.RevivalSupplies.bundle(offer);
            default -> throw new IllegalArgumentException("A witch potion is brewed by MonsterPotions: " + offer);
        };
        stack.set(DataComponents.CUSTOM_NAME, name(offer, role));
        var lore = new ArrayList<Component>();
        lore.add(Component.literal(description(offer, owned)).withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GRAY)));
        for (var note : dev.hardrush.monster.MonsterPotions.notes(offer))
            lore.add(Component.literal(note).withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GRAY)));
        if (offer.permanent()) lore.add(Component.literal("本轮以僵尸出击时一直有效 · 复生后清空")
                .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.DARK_GRAY)));
        if (offer.revival()) lore.add(Component.literal("复生后可用")
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
        if (offer.potion()) return "下次出击时带上，自己喝";
        return switch (offer) {
            case ADULT_EGG -> "下次出击多带 1 枚成年僵尸刷怪蛋";
            case BABY_EGG -> "下次出击多带 1 枚幼年僵尸刷怪蛋";
            case MONSTER_BOX -> "下次出击多带 1 个怪物盲盒 · 升级拘魂袋，它能开出更疯狂的东西";
            case SLOWNESS_ARROWS -> "下次出击时带上，射一箭消耗一支";
            case POISON_ARROWS -> "下次出击时带上，射一箭消耗一支";
            case TRIAL_KEY -> "在庭院的试炼宝库开出一件装备";
            case OMINOUS_KEY -> "在庭院的不祥宝库开出一件装备，奖池更好";
            case SOULCATCHER_TIER -> SOULCATCHER_STEPS[Math.min(owned, SOULCATCHER_STEPS.length - 1)];
            case SOULCATCHER_CAPACITY -> "拘魂袋多装 1 只生物";
            case BONUS_CHEST -> "落地时在身旁放一个原版奖励箱：工具、食物和木头";
            case LONGER_BLESSING -> "图腾之力庇护 " + clock(dev.hardrush.core.round.WitchRecord.BLESSING_MILLIS
                    + dev.hardrush.core.round.WitchRecord.BLESSING_STEP_MILLIS * owned) + " → " + clock(Math.min(
                    dev.hardrush.core.round.WitchRecord.MAX_BLESSING_MILLIS, dev.hardrush.core.round.WitchRecord.BLESSING_MILLIS
                    + dev.hardrush.core.round.WitchRecord.BLESSING_STEP_MILLIS * (owned + 1)));
            case IRON_BUCKET, OAK_LOGS, COAL, IRON_PICKAXE, BREAD -> "复生时放进背包";
            case BELL -> "复生时放进背包 · 敲响后，附近的怪物玩家发光";
            case SHIELD -> "复生时放进副手";
            default -> throw new IllegalArgumentException("A witch potion is described above: " + offer);
        };
    }

    /** "1:30", "5:00". */
    static String clock(long millis) {
        long seconds = millis / 1000;
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    /** A right click on the witch: her guide the first time this round, her shop afterwards. */
    public static void open(ServerPlayer player) {
        var access = INSTANCES.get(player.level().getServer());
        var r = access == null ? null : access.committed().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (!trading(player, access, r, p)) return;
        if (WitchGuide.pending(access, r, p)) { sound(player, SoundEvents.WITCH_AMBIENT); WitchGuide.show(player, access, r, p); return; }
        openShop(player);
    }

    /**
     * The shop itself, also opened by the guide's last page. A monster holding a revival totem can only go back to the
     * race, so it is offered the revival supplies and nothing else.
     */
    static void openShop(ServerPlayer player) {
        var access = INSTANCES.get(player.level().getServer());
        var r = access == null ? null : access.committed().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (!trading(player, access, r, p)) return;
        // The guide's button can be sent from anywhere; only a player standing at the witch is served.
        if (!nearMerchant(player)) return;
        boolean revival = p.revivalEntitled();
        if (!revival && !p.wallet().roleChosen()) {
            dev.hardrush.notice.Notices.hint(player, "女巫：先去展位选个角色，再来找我"); sound(player, SoundEvents.WITCH_AMBIENT); return;
        }
        var trader = new Trader(player, p.monsterRole(), revival);
        trader.rebuild(r.rules(), p);
        var id = player.openMenu(new SimpleMenuProvider((containerId, inventory, ignored) ->
                new ShopMenu(containerId, inventory, trader), Component.literal(revival ? "女巫商人 · 复生补给" : "女巫商人")));
        if (id.isPresent()) player.sendMerchantOffers(id.getAsInt(), trader.offers, 1, 0, false, false);
    }

    /** Near any merchant: a model, or a clicked block or entity bound as one. */
    private static boolean nearMerchant(ServerPlayer player) {
        var lobby = LobbyWorld.open(player.level().getServer());
        if (!lobby.ready() || !LobbyWorld.isLobby(player.level())) return false;
        var plan = lobby.plan();
        var merchants = new java.util.ArrayList<dev.hardrush.core.lobby.plan.Point>();
        plan.models(LobbyFunction.MERCHANT).forEach(m -> merchants.add(m.point()));
        plan.triggers(LobbyFunction.MERCHANT).forEach(t -> merchants.add(t.anchor()));
        return merchants.stream().anyMatch(m -> player.position().distanceToSqr(LobbyWorld.position(m)) <= REACH * REACH);
    }

    private static boolean trading(ServerPlayer player, CampAccess access, dev.hardrush.core.round.RoundSnapshot r, Participant p) {
        if (p != null && MonsterCoins.enabled(r.rules()) && access.eligible(player)) return true;
        dev.hardrush.notice.Notices.hint(player, "女巫：我只和庭院里待命的怪物做生意"); sound(player, SoundEvents.WITCH_AMBIENT);
        return false;
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
                    if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
                    // The answer is told even to a player who already closed the screen; only the screen itself
                    // needs updating while it is still open. The reason waits for the trading screen to close.
                    boolean open = player.containerMenu == menu;
                    if (!reply.success()) {
                        var reason = dev.hardrush.notice.Replies.text(reply, "女巫：这笔交易现在做不了");
                        dev.hardrush.notice.Notices.hint(player, reason);
                        sound(player, SoundEvents.VILLAGER_NO);
                        if (open) { menu.refusal = reason; menu.resync(); }
                        return;
                    }
                    // A refusal still waiting under this screen is out of date once a retry goes through.
                    if (open && menu.refusal != null) { dev.hardrush.notice.Notices.withdraw(player, menu.refusal); menu.refusal = null; }
                    sound(player, SoundEvents.WITCH_CELEBRATE);
                    // The witch's cheer, the coin count and the goods' description tell the rest.
                    if (open) menu.afterPurchase();
                });
    }


    private static void sound(ServerPlayer player, SoundEvent sound) {
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.NEUTRAL, 1, 1);
    }

    /** A server-only merchant; there is no entity, so trading never changes a villager or the world. */
    static final class Trader implements Merchant {
        private final ServerPlayer player;
        private final MonsterRole role;
        /** The revival shelf: what a monster holding a totem is offered instead of sortie goods. */
        private final boolean revival;
        private MerchantOffers offers = new MerchantOffers();
        private final Map<MerchantOffer, ShopOffer> mapping = new IdentityHashMap<>();

        Trader(ServerPlayer player, MonsterRole role, boolean revival) { this.player = player; this.role = role; this.revival = revival; }

        void rebuild(dev.hardrush.core.config.RulesConfig rules, Participant buyer) {
            var built = new MerchantOffers();
            mapping.clear();
            for (var offer : ShopOffer.values()) {
                if (offer.revival() != revival || !revival && !offer.sellableTo(role) || !MonsterCoins.offered(rules, offer)) continue;
                int bought = buyer.wallet().bought(offer), limit = MonsterCoins.limit(rules, offer);
                int base = MonsterCoins.price(rules, offer, 0);
                int price = MonsterCoins.price(rules, offer, Math.min(bought, limit - 1));
                var goods = goods(offer, rules, role, Math.min(bought, limit - 1));
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
        /** The last refusal told under this screen, withdrawn if a retry succeeds before the player sees it. */
        private String refusal;

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
            if (p == null || !access.eligible(server) || p.revivalEntitled() != trader.revival) return false;
            if (!trader.revival && (p.monsterRole() != trader.role || !p.wallet().roleChosen())) return false;
            return nearMerchant(server);
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
