package dev.hardrush.monster;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

/** Java 26.3 mob equipment, with hard/max-local probabilities and a 5–30 enchantment input. */
public final class MonsterSpawnEquipment {
    private static final List<EquipmentSlot> ARMOR_ORDER = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private MonsterSpawnEquipment() {}

    public static Map<EquipmentSlot, ItemStack> generate(ServerPlayer player, Mob model, boolean skeleton) {
        return generate(player.level(), model.getRandom(), skeleton);
    }

    public static Map<EquipmentSlot, ItemStack> generate(ServerLevel level, RandomSource random, boolean skeleton) {
        var candidates = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(EnchantmentTags.ON_MOB_SPAWN_EQUIPMENT);
        return generate(random, skeleton, (stack, rng, cost) -> EnchantmentHelper.updateEnchantments(stack, mutable -> {
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
        var result = new EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot.class);
        for (var slot : EquipmentSlot.VALUES) result.put(slot, ItemStack.EMPTY);
        if (random.nextFloat() < 0.15F) {
            int material = random.nextInt(3);
            for (int attempt = 0; attempt < 3; attempt++) {
                if (random.nextFloat() < 0.1087F) material++;
            }
            boolean first = true;
            for (var slot : ARMOR_ORDER) {
                if (!first && random.nextFloat() < 0.1F) break;
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

    private static void enchant(ItemStack stack, RandomSource random, float chance, Enchanter enchanter) {
        if (!stack.isEmpty() && random.nextFloat() < chance) {
            enchanter.enchant(stack, random, 5 + random.nextInt(26));
        }
    }
}
