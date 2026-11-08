package dev.hardrush.monster;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Item components, not inventory positions, identify the owner's disposable sortie kit. */
public final class MonsterItems {
    private static final String KEY = "hardrush_bound_item";
    private MonsterItems() {}
    public static ItemStack bind(ServerPlayer owner, ItemStack stack, String purpose) {
        if (stack.isEmpty()) return stack;
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        var binding = new CompoundTag();
        binding.putString("owner", owner.getUUID().toString());
        binding.putString("sortie", MonsterRoles.binding(owner));
        binding.putString("purpose", purpose);
        data.put(KEY, binding);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return stack;
    }
    public static boolean bound(ItemStack stack) { return !tag(stack).isEmpty(); }
    /** A world copy of a sortie item, e.g. equipment a slain monster drops natively. */
    public static ItemStack unbind(ItemStack stack) {
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.remove(KEY);
        if (data.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA); else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return stack;
    }
    public static String purpose(ItemStack stack) { return tag(stack).getStringOr("purpose", ""); }
    public static boolean owned(ServerPlayer owner, ItemStack stack) {
        return owned(owner.getUUID(), MonsterRoles.binding(owner), stack);
    }
    /** Thread-agnostic form for callers that captured the sortie binding on the server thread. */
    static boolean owned(java.util.UUID owner, String sortie, ItemStack stack) {
        var binding = tag(stack);
        return !binding.isEmpty() && binding.getStringOr("owner", "").equals(owner.toString())
                && binding.getStringOr("sortie", "").equals(sortie);
    }
    public static boolean matches(ServerPlayer owner, ItemStack stack, String purpose) {
        return owned(owner, stack) && purpose.equals(purpose(stack));
    }
    private static CompoundTag tag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompoundOrEmpty(KEY);
    }
}
