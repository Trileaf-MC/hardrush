package dev.hardrush.monster;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.MonsterCoins;
import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.PotionBox;
import dev.hardrush.core.round.PotionBrew;
import dev.hardrush.core.round.ShopOffer;
import dev.hardrush.notice.Notices;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * The witch's potions on a sortie (docs/plan/女巫药水与药水盲盒.md): bound bottles the monster drinks itself, the native
 * way. Fixed potions carry their native contents and vanilla applies them, the undead's healing from harming included.
 * A blind box carries no effects at all; what it brews is drawn the moment it is drunk, for the form drinking it.
 */
public final class MonsterPotions {
    /** Bound purpose of a fixed potion; a blind box adds its pool after a colon. */
    public static final String PURPOSE = "witch_potion";
    private static final String BOX_PURPOSE = "potion_box:";
    /** Fixed bottle colours, one per box; the native potion colours of what may come out never show. */
    private static final int COMMON_COLOR = 0xD8D8D8, RARE_COLOR = 0x3FC8E0, EPIC_COLOR = 0xB45AE6;

    private MonsterPotions() {}

    /** The bottle the witch sells, unbound and unnamed; {@code role} decides between harming and healing. */
    public static ItemStack bottle(ShopOffer offer, RulesConfig rules, MonsterRole role) {
        var box = offer.potionBox();
        if (box.isPresent()) {
            var stack = new ItemStack(Items.POTION);
            stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.of(color(box.get())), List.of(), Optional.empty()));
            stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.POTION_CONTENTS, true));
            // The glint marks a blind box, as it marks the monster box, among the plain bottles beside it.
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            return stack;
        }
        return switch (offer) {
            case SWIFTNESS -> custom(MobEffects.SPEED, 0, seconds(rules, MonsterCoins.SWIFTNESS_SECONDS));
            case FIRE_RESISTANCE -> custom(MobEffects.FIRE_RESISTANCE, 0, seconds(rules, MonsterCoins.FIRE_RESISTANCE_SECONDS));
            case INSTANT_HEALTH -> PotionContents.createItemStack(Items.POTION, role == MonsterRole.CREEPER ? Potions.HEALING : Potions.HARMING);
            case ABSORPTION -> custom(MobEffects.ABSORPTION, 2, seconds(rules, MonsterCoins.ABSORPTION_SECONDS));
            case RESISTANCE -> custom(MobEffects.RESISTANCE, 0, seconds(rules, MonsterCoins.RESISTANCE_SECONDS));
            case TURTLE_MASTER -> PotionContents.createItemStack(Items.POTION, Potions.TURTLE_MASTER);
            case POISON -> custom(MobEffects.POISON, 0, seconds(rules, MonsterCoins.POISON_SECONDS));
            default -> throw new IllegalArgumentException("Not a witch potion: " + offer);
        };
    }

    /** The native potion's name, by form for the harming-or-healing offer. */
    public static String title(ShopOffer offer, MonsterRole role) {
        if (offer == ShopOffer.INSTANT_HEALTH) return role == MonsterRole.CREEPER ? "治疗药水" : "伤害药水";
        return offer.title();
    }

    /** Lines the native potion tooltip does not already say, below the witch's usage line. */
    public static List<String> notes(ShopOffer offer) {
        return switch (offer) {
            case ABSORPTION, RESISTANCE -> List.of("本女巫特调，绝对美味～");
            case POISON -> List.of("会有什么用呢？");
            case COMMON_POTION_BOX -> List.of("大部分都是增益效果～");
            case RARE_POTION_BOX -> List.of("生活就像巧克力～");
            case EPIC_POTION_BOX -> List.of("加倍惊喜～");
            default -> List.of();
        };
    }

    private static int color(PotionBox box) {
        return switch (box) { case COMMON -> COMMON_COLOR; case RARE -> RARE_COLOR; case EPIC -> EPIC_COLOR; };
    }

    private static int seconds(RulesConfig rules, String key) { return MonsterCoins.amount(rules, key); }

    private static ItemStack custom(Holder<MobEffect> effect, int amplifier, int seconds) {
        var stack = new ItemStack(Items.POTION);
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.empty(),
                List.of(new MobEffectInstance(effect, seconds * 20, amplifier)), Optional.empty()));
        return stack;
    }

    /** The sortie's bottles, one per purchase: potions never stack. */
    static void give(ServerPlayer player, dev.hardrush.core.round.MonsterWallet kit, RulesConfig rules, MonsterRole role) {
        for (var offer : ShopOffer.values()) {
            if (!offer.potion() || !MonsterCoins.offered(rules, offer)) continue;
            for (int i = 0; i < kit.carried(offer); i++) player.getInventory().add(sortieBottle(player, offer, rules, role));
        }
    }

    /** One bottle bound to this sortie, named as the witch sold it. The empty bottle never comes back. */
    static ItemStack sortieBottle(ServerPlayer player, ShopOffer offer, RulesConfig rules, MonsterRole role) {
        var stack = dev.hardrush.lobby.MonsterShop.sortiePotion(offer, rules, role);
        stack.remove(DataComponents.USE_REMAINDER);
        return MonsterItems.bind(player, stack, offer.potionBox().map(box -> BOX_PURPOSE + box.name()).orElse(PURPOSE));
    }

    private static boolean held(ServerPlayer player, ItemStack stack) {
        if (!MonsterItems.owned(player, stack)) return false;
        var purpose = MonsterItems.purpose(stack);
        return purpose.equals(PURPOSE) || purpose.startsWith(BOX_PURPOSE);
    }

    /** A witch bottle in {@code hand}: starts drinking it the native way. */
    static boolean holds(ServerPlayer player, InteractionHand hand) { return held(player, player.getItemInHand(hand)); }

    static boolean drink(ServerPlayer player, InteractionHand hand) {
        if (player.isUsingItem() || MonsterRoles.performingSkill(player)) return false;
        player.startUsingItem(hand);
        return true;
    }

    /** Drinking a witch bottle: letting go of the button stops it as it would anyone's. */
    public static boolean drinking(ServerPlayer player) { return player.isUsingItem() && held(player, player.getUseItem()); }

    /** Just before vanilla finishes the drink: a blind box brews its draw now and the bottle stays empty for vanilla. */
    public static void finishing(ServerPlayer player) {
        var stack = player.getUseItem();
        if (!player.isUsingItem() || !held(player, stack) || !stack.equals(player.getItemInHand(player.getUsedItemHand()))) return;
        var purpose = MonsterItems.purpose(stack);
        if (!purpose.startsWith(BOX_PURPOSE)) return;
        PotionBox box;
        try { box = PotionBox.valueOf(purpose.substring(BOX_PURPOSE.length())); }
        catch (IllegalArgumentException unknown) { return; }
        var form = MonsterRoles.currentForm(player);
        var rules = MonsterRoundRuntime.rules(player).orElseGet(RulesConfig::defaults);
        if (form == null || !PotionBox.configured(rules)) return;
        var random = player.getRandom();
        var doses = box.draw(rules, form.role, new RandomGenerator() {
            @Override public long nextLong() { return random.nextLong(); }
            @Override public int nextInt(int bound) { return random.nextInt(bound); }
        });
        var names = new ArrayList<String>();
        boolean epic = false;
        for (var dose : doses) {
            apply(player, form.role, dose);
            names.add(Notices.key(dose.brew().potionName(form.role) + (dose.level() > 0 ? " II" : "")));
            epic |= dose.grade() == PotionBrew.Grade.EPIC;
        }
        Notices.hint(player, "开出了 " + String.join(" · ", names));
        if (epic) player.connection.send(new ClientboundSoundPacket(net.minecraft.core.Holder.direct(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE),
                SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(), .6f, 1f, player.getRandom().nextLong()));
    }

    /** One drawn dose, as the native potion of that name would act on this form. */
    static void apply(ServerPlayer player, MonsterRole role, PotionBox.Dose dose) {
        // A parting gift lasts until the monster dies: the native infinite duration, so the HUD shows its infinity sign.
        int ticks = dose.seconds() == PotionBox.UNTIL_DEATH ? MobEffectInstance.INFINITE_DURATION : dose.seconds() * 20, level = dose.level();
        switch (dose.brew()) {
            case MENDING -> player.heal(4 << level);
            // The native healing a zombie drinks, or harming a creeper drinks: six damage of magic, and it may kill.
            case WOUNDING -> player.hurtServer(player.level(), player.damageSources().magic(), 6 << level);
            case TURTLE_MASTER -> {
                player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, level == 0 ? 3 : 5));
                player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, level == 0 ? 2 : 3));
            }
            default -> player.addEffect(new MobEffectInstance(effect(dose.brew()), ticks, level));
        }
    }

    static Holder<MobEffect> effect(PotionBrew brew) {
        return switch (brew) {
            case SWIFTNESS -> MobEffects.SPEED; case LEAPING -> MobEffects.JUMP_BOOST; case FIRE_RESISTANCE -> MobEffects.FIRE_RESISTANCE;
            case SLOW_FALLING -> MobEffects.SLOW_FALLING; case DOLPHINS_GRACE -> MobEffects.DOLPHINS_GRACE;
            case WATER_BREATHING -> MobEffects.WATER_BREATHING;
            case STRENGTH -> MobEffects.STRENGTH; case RESISTANCE -> MobEffects.RESISTANCE; case ABSORPTION -> MobEffects.ABSORPTION;
            case REGENERATION -> MobEffects.REGENERATION; case INVISIBILITY -> MobEffects.INVISIBILITY; case WEAVING -> MobEffects.WEAVING;
            case OOZING -> MobEffects.OOZING; case INFESTED -> MobEffects.INFESTED; case WIND_CHARGED -> MobEffects.WIND_CHARGED;
            case SLOWNESS -> MobEffects.SLOWNESS; case WEAKNESS -> MobEffects.WEAKNESS; case GLOWING -> MobEffects.GLOWING;
            case NAUSEA -> MobEffects.NAUSEA; case BLINDNESS -> MobEffects.BLINDNESS;
            case MENDING, WOUNDING, TURTLE_MASTER -> throw new IllegalArgumentException("Not a single effect: " + brew);
        };
    }
}
