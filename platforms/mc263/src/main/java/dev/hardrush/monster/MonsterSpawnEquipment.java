package dev.hardrush.monster;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import dev.hardrush.core.round.HuntPressure;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Java 26.3 mob equipment, with hard/max-local probabilities and a 5–30 enchantment input. A sortie's tier
 * (docs/plan/悬赏分档与追猎强度.md §5.4) raises the chance to wear armour and its material from three stars on;
 * one and two stars roll exactly as vanilla does at its peak.
 */
public final class MonsterSpawnEquipment {
    private static final List<EquipmentSlot> ARMOR_ORDER = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private MonsterSpawnEquipment() {}

    public static Map<EquipmentSlot, ItemStack> generate(ServerPlayer player, Mob model, boolean skeleton, int tier) {
        return generate(player.level(), model.getRandom(), skeleton, tier);
    }

    public static Map<EquipmentSlot, ItemStack> generate(ServerLevel level, RandomSource random, boolean skeleton, int tier) {
        var candidates = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(EnchantmentTags.ON_MOB_SPAWN_EQUIPMENT);
        return generate(random, skeleton, HuntPressure.armor(tier), (stack, rng, cost) -> EnchantmentHelper.updateEnchantments(stack, mutable -> {
            for (var enchantment : EnchantmentHelper.selectEnchantment(rng, stack, cost, candidates.stream())) {
                mutable.upgrade(enchantment.enchantment(), enchantment.level());
            }
        }));
    }

    @FunctionalInterface
    interface Enchanter {
        void enchant(ItemStack stack, RandomSource random, int cost);
    }

    // An injected random source and enchantment boundary allow exact threshold tests without sampling luck.
    static Map<EquipmentSlot, ItemStack> generate(RandomSource random, boolean skeleton, Enchanter enchanter) {
        return generate(random, skeleton, HuntPressure.armor(1), enchanter);
    }

    static Map<EquipmentSlot, ItemStack> generate(RandomSource random, boolean skeleton, HuntPressure.ArmorRoll armor, Enchanter enchanter) {
        var result = new EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot.class);
        for (var slot : EquipmentSlot.VALUES) result.put(slot, ItemStack.EMPTY);
        if (random.nextFloat() < armor.wearChance()) {
            int material = armor.materialWeights().map(weights -> weighted(random, weights)).orElseGet(() -> {
                int vanilla = random.nextInt(3);
                for (int attempt = 0; attempt < 3; attempt++) {
                    if (random.nextFloat() < 0.1087F) vanilla++;
                }
                return vanilla;
            });
            boolean first = true;
            for (var slot : ARMOR_ORDER) {
                if (!first && random.nextFloat() < armor.stopChance()) break;
                first = false;
                var item = Mob.getEquipmentForSlot(slot, material);
                if (item != null) result.put(slot, new ItemStack(item));
            }
        }
        if (skeleton) {
            result.put(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        } else if (random.nextFloat() < 0.05F) {
            result.put(EquipmentSlot.MAINHAND, new ItemStack(switch (random.nextInt(6)) {
                case 0 -> Items.IRON_SWORD;
                case 1 -> Items.IRON_SPEAR;
                default -> Items.IRON_SHOVEL;
            }));
        }
        enchant(result.get(EquipmentSlot.MAINHAND), random, 0.25F, enchanter);
        for (var slot : EquipmentSlot.VALUES) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                enchant(result.get(slot), random, 0.5F, enchanter);
            }
        }
        return result;
    }

    private static int weighted(RandomSource random, List<Integer> weights) {
        int roll = random.nextInt(weights.stream().mapToInt(Integer::intValue).sum());
        for (int material = 0; material < weights.size(); material++) {
            roll -= weights.get(material);
            if (roll < 0) return material;
        }
        throw new IllegalStateException("Unreachable armour weight");
    }

    private static void enchant(ItemStack stack, RandomSource random, float chance, Enchanter enchanter) {
        if (!stack.isEmpty() && random.nextFloat() < chance) {
            enchanter.enchant(stack, random, 5 + random.nextInt(26));
        }
    }
}
