package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.lobby.plan.MarkerSpec;
import dev.hardrush.core.lobby.plan.MarkerTags;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;

/**
 * Marker armor stands (design 4.1). Spawning and the toolbox item share one entity-data definition and the same vanilla
 * load path ({@link TypedEntityData#loadInto}, which {@code ArmorStandItem} also uses), so a marker looks and parses
 * the same however it got into the world.
 */
public final class MarkerStands {
    /** Every equipment slot, for take, replace and place: the head item is the marker's icon, not loot. */
    static final int ALL_SLOTS_DISABLED = 0xFFFFFF;

    private MarkerStands() {}

    public static ArmorStand spawn(ServerLevel level, MarkerSpec spec, double x, double y, double z, float yaw) {
        var stand = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        stand.snapTo(x, y, z, yaw, 0);
        TypedEntityData.of(EntityTypes.ARMOR_STAND, data(spec)).loadInto(stand);
        // Armor stands render their body yaw; vanilla placement sets both through snapTo as well.
        stand.setYHeadRot(yaw);
        stand.setYBodyRot(yaw);
        level.addFreshEntity(stand);
        return stand;
    }

    /**
     * A click, block or text marker binding {@code target} (design 4.4 item 3): in the cell above when that is air, else
     * in the block's own cell. Either way {@link #target} resolves back to {@code target}.
     */
    public static ArmorStand spawnOn(ServerLevel level, MarkerSpec spec, net.minecraft.core.BlockPos target, float yaw) {
        var above = target.above();
        var cell = level.getBlockState(above).isAir() ? above : target;
        return spawn(level, spec, cell.getX() + .5, cell.getY(), cell.getZ() + .5, yaw);
    }

    /** An armor stand item that vanilla places as this marker. */
    public static ItemStack item(MarkerSpec spec) {
        var stack = new ItemStack(Items.ARMOR_STAND);
        stack.set(DataComponents.ENTITY_DATA, TypedEntityData.of(EntityTypes.ARMOR_STAND, data(spec)));
        stack.set(DataComponents.ITEM_NAME, Component.literal(name(spec) + "标记"));
        return stack;
    }

    /** A marker armor stand, whatever its other tags say. */
    public static boolean is(net.minecraft.world.entity.Entity entity) {
        return entity instanceof ArmorStand && entity.entityTags().contains(MarkerTags.MARKER);
    }

    /** The block a block, text or click marker binds (design 4.2): its own cell, or the one below when that is air. */
    public static net.minecraft.core.BlockPos target(ServerLevel level, net.minecraft.core.BlockPos marker) {
        return level.getBlockState(marker).isAir() ? marker.below() : marker;
    }

    public static String name(MarkerSpec spec) { return "【" + spec.function().displayName() + "】"; }

    /** The marker's entity data without {@code id}; {@link TypedEntityData} carries the type separately. */
    static CompoundTag data(MarkerSpec spec) {
        var tag = new CompoundTag();
        tag.put("Small", ByteTag.ONE);
        tag.put("NoGravity", ByteTag.ONE);
        tag.put("NoBasePlate", ByteTag.ONE);
        tag.put("CustomNameVisible", ByteTag.ONE);
        tag.put("DisabledSlots", IntTag.valueOf(ALL_SLOTS_DISABLED));
        // Text components are stored as NBT since 1.21.5; a plain string is a plain text component.
        tag.put("CustomName", StringTag.valueOf(name(spec)));
        var tags = new ListTag();
        for (var t : MarkerTags.format(spec)) tags.add(StringTag.valueOf(t));
        tag.put("Tags", tags);
        var head = new CompoundTag();
        head.putString("id", BuiltInRegistries.ITEM.getKey(icon(spec.function())).toString());
        head.putInt("count", 1);
        var equipment = new CompoundTag();
        equipment.put("head", head);
        tag.put("equipment", equipment);
        return tag;
    }

    /** The head item that tells builders the function at a glance. */
    public static Item icon(LobbyFunction function) {
        return switch (function) {
            case ASSEMBLY -> Items.WOOL.lime();
            case COURTYARD -> Items.ROTTEN_FLESH;
            case ALTAR_PAD -> Items.CRYING_OBSIDIAN;
            case ALTAR_LIGHT -> Items.TINTED_GLASS;
            case LANDING_CENTRAL -> Items.COMPASS;
            case LANDING_MONSTER -> Items.RECOVERY_COMPASS;
            case LANDING_ALTAR -> Items.LODESTONE;
            case DISPLAY_SLOT -> Items.ARMOR_STAND;
            case RULES -> Items.BOOK;
            case HISTORY -> Items.WRITABLE_BOOK;
            case ROLE_ZOMBIE -> Items.ZOMBIE_HEAD;
            case ROLE_SKELETON -> Items.SKELETON_SKULL;
            case ROLE_CREEPER -> Items.CREEPER_HEAD;
            case MERCHANT -> Items.POTION;
            case VAULT -> Items.TRIAL_KEY;
            case OMINOUS_VAULT -> Items.OMINOUS_TRIAL_KEY;
            case PAGE_PREV -> Items.ARROW;
            case PAGE_NEXT -> Items.SPECTRAL_ARROW;
            case STATUS -> Items.OAK_SIGN;
            case CREDITS -> Items.NAME_TAG;
        };
    }
}
