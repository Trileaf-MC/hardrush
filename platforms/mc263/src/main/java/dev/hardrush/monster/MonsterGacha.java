package dev.hardrush.monster;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.MonsterWallet.GearSlot;
import dev.hardrush.core.round.ShopOffer;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Camp vault draws. Every result is equipment a native zombie or skeleton can spawn with: armor by
 * {@link Mob#getEquipmentForSlot}, the zombie's iron sword/spear/shovel, or the skeleton's bow,
 * enchanted through the same mob spawn equipment candidates as {@link MonsterSpawnEquipment}.
 */
public final class MonsterGacha {
    private static final List<GearSlot> SLOTS = List.of(GearSlot.HEAD, GearSlot.CHEST, GearSlot.LEGS, GearSlot.FEET, GearSlot.MAINHAND);
    private static final int MAX_MATERIAL = 5;
    private static final Map<String, ItemStack> DECODED = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, ItemStack> eldest) { return size() > 256; }
    };

    public record Draw(GearSlot slot, Set<MonsterRole> roles, ItemStack stack) {}

    /** Tier odds; ominous starts two material steps higher, upgrades more often and always enchants. */
    record Tier(int materialBase, int materialSpread, float upgradeChance, float enchantChance, int minCost, int maxCost) {}
    static final Tier NORMAL = new Tier(0, 3, 0.1087F, 0.5F, 5, 30);
    static final Tier OMINOUS = new Tier(2, 2, 0.25F, 1F, 15, 30);

    private MonsterGacha() {}

    public static Draw draw(ServerLevel level, RandomSource random, MonsterRole role, ShopOffer key) {
        var candidates = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(EnchantmentTags.ON_MOB_SPAWN_EQUIPMENT);
        return draw(random, role, key, (stack, rng, cost) -> EnchantmentHelper.updateEnchantments(stack, mutable -> {
            for (var enchantment : EnchantmentHelper.selectEnchantment(rng, stack, cost, candidates.stream())) {
                mutable.upgrade(enchantment.enchantment(), enchantment.level());
            }
        }));
    }

    // An injected random source and enchanter allow exact boundary tests without sampling luck.
    static Draw draw(RandomSource random, MonsterRole role, ShopOffer key, MonsterSpawnEquipment.Enchanter enchanter) {
        if (role == MonsterRole.CREEPER || !key.key()) throw new IllegalArgumentException("苦力怕不能使用宝库装备");
        var tier = key == ShopOffer.OMINOUS_KEY ? OMINOUS : NORMAL;
        var slot = SLOTS.get(random.nextInt(SLOTS.size()));
        ItemStack stack;
        Set<MonsterRole> roles;
        if (slot == GearSlot.MAINHAND) {
            roles = EnumSet.of(role);
            stack = new ItemStack(role == MonsterRole.SKELETON ? Items.BOW : zombieWeapon(random, tier));
        } else {
            roles = EnumSet.of(MonsterRole.ZOMBIE, MonsterRole.SKELETON);
            int material = tier.materialBase + random.nextInt(tier.materialSpread);
            for (int attempt = 0; attempt < 3; attempt++) if (random.nextFloat() < tier.upgradeChance) material++;
            stack = new ItemStack(Mob.getEquipmentForSlot(equipmentSlot(slot), Math.min(MAX_MATERIAL, material)));
        }
        if (random.nextFloat() < tier.enchantChance) {
            enchanter.enchant(stack, random, tier.minCost + random.nextInt(tier.maxCost - tier.minCost + 1));
        }
        return new Draw(slot, roles, stack);
    }

    /** An unenchanted sample of this tier's pool, for the vault's rotating display. */
    public static ItemStack preview(RandomSource random, MonsterRole role, ShopOffer key) {
        return draw(random, role, key, (stack, rng, cost) -> {}).stack();
    }

    private static Item zombieWeapon(RandomSource random, Tier tier) {
        if (tier == OMINOUS) return random.nextInt(2) == 0 ? Items.IRON_SWORD : Items.IRON_SPEAR;
        return switch (random.nextInt(6)) {
            case 0 -> Items.IRON_SWORD;
            case 1 -> Items.IRON_SPEAR;
            default -> Items.IRON_SHOVEL;
        };
    }

    public static EquipmentSlot equipmentSlot(GearSlot slot) {
        return switch (slot) {
            case HEAD -> EquipmentSlot.HEAD; case CHEST -> EquipmentSlot.CHEST; case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET; case MAINHAND -> EquipmentSlot.MAINHAND;
        };
    }

    public static String encode(HolderLookup.Provider registries, ItemStack stack) {
        return ItemStack.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), stack)
                .getOrThrow(IllegalStateException::new).toString();
    }

    /** A copy of the stored stack, or empty if it no longer decodes; core never interprets the text. */
    public static ItemStack decode(HolderLookup.Provider registries, String text) {
        var cached = DECODED.get(text);
        if (cached == null) {
            try {
                cached = ItemStack.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(text))
                        .result().orElse(ItemStack.EMPTY);
            } catch (RuntimeException invalid) { cached = ItemStack.EMPTY; }
            DECODED.put(text, cached);
        }
        return cached.copy();
    }
}
