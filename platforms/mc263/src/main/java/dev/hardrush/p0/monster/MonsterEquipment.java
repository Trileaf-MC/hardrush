package dev.hardrush.p0.monster;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import dev.hardrush.p0.monster.mixin.MonsterLivingAccessor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** Actual mob gear, independent of the vanilla client's immutable skill controls. */
final class MonsterEquipment {
    private final Map<EquipmentSlot,ItemStack> slots=new EnumMap<>(EquipmentSlot.class);
    private final EnumSet<EquipmentSlot> worldGear=EnumSet.noneOf(EquipmentSlot.class);
    private final Map<EquipmentSlot,ItemStack> previous=new EnumMap<>(EquipmentSlot.class);

    MonsterEquipment() { reset(false); }

    void reset(boolean skeleton) {
        slots.clear(); worldGear.clear(); previous.clear();
        for (var slot:EquipmentSlot.values()) slots.put(slot,ItemStack.EMPTY);
        if (skeleton) slots.put(EquipmentSlot.MAINHAND,new ItemStack(Items.BOW));
    }

    void bind(Mob model) {
        previous.clear();
        for (var slot:EquipmentSlot.values()) {
            previous.put(slot,ItemStack.EMPTY);
            model.setItemSlot(slot,get(slot).copy());
            model.setDropChance(slot,worldGear.contains(slot)?2f:0f);
        }
        capture(model);
    }

    ItemStack get(EquipmentSlot slot) { return slots.getOrDefault(slot,ItemStack.EMPTY); }

    void capture(Mob model) {
        ((MonsterLivingAccessor)model).hardrush$collectEquipmentChanges(previous);
        for (var slot:EquipmentSlot.values()) {
            ItemStack item=model.getItemBySlot(slot).copy();
            slots.put(slot,item);
            previous.put(slot,item.copy());
        }
    }

    boolean pickUp(ServerPlayer player,Mob model,ItemEntity entity) {
        if (entity.isRemoved() || entity.hasPickUpDelay() || !player.isAlive()
                || entity.level()!=player.level() || entity.distanceToSqr(player)>4) return false;
        ItemStack candidate=entity.getItem();
        // This first batch equips weapons/gear, without collecting arbitrary
        // resources into a player inventory. The species decides the slot and
        // comparison, including bow preference and forbidden spear pickup.
        if (!isEquipment(candidate) || !model.wantsToPickUp(player.level(),candidate)) return false;
        ItemStack accepted=model.equipItemIfPossible(player.level(),candidate.copy());
        if (accepted.isEmpty()) return false;
        for (var slot:EquipmentSlot.values()) {
            if (!ItemStack.matches(model.getItemBySlot(slot),get(slot))) worldGear.add(slot);
        }
        player.take(entity,accepted.getCount());
        candidate.shrink(accepted.getCount());
        if (candidate.isEmpty()) entity.discard();
        capture(model);
        return true;
    }

    private static boolean isEquipment(ItemStack item) {
        // Spear charge/contact attacks need a separate input adapter.
        if(item.is(net.minecraft.tags.ItemTags.SPEARS))return false;
        return item.has(DataComponents.EQUIPPABLE) || item.has(DataComponents.BLOCKS_ATTACKS)
            || item.is(Items.BOW) || item.is(Items.CROSSBOW)
            || item.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,ItemAttributeModifiers.EMPTY)
                .compute(Attributes.ATTACK_DAMAGE,0,EquipmentSlot.MAINHAND)>0;
    }

    void returnWorldGear(ServerPlayer player,boolean death) {
        for (var slot:worldGear) {
            ItemStack item=get(slot);
            if (!item.isEmpty() && (!death || !EnchantmentHelper.has(item,EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)))
                player.spawnAtLocation(player.level(),item.copy());
        }
        worldGear.clear();
    }
}
