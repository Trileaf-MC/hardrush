package dev.hardrush.monster;

import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.notice.ItemText;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A body can change species without changing the selected role or sortie. Everything that differs between
 * species lives here, so a new species is one more constant.
 */
public enum MonsterForm {
    ZOMBIE(MonsterRole.ZOMBIE, EntityTypes.ZOMBIE, "僵尸", true,
            SoundEvents.ZOMBIE_AMBIENT, SoundEvents.ZOMBIE_HURT, SoundEvents.ZOMBIE_DEATH, null, Items.ZOMBIE_SPAWN_EGG, null, 1),
    DROWNED(MonsterRole.ZOMBIE, EntityTypes.DROWNED, "溺尸", false,
            SoundEvents.DROWNED_AMBIENT, SoundEvents.DROWNED_HURT, SoundEvents.DROWNED_DEATH, null, Items.DROWNED_SPAWN_EGG,
            new Weapon(MonsterRoles.SEA_TRIDENT, () -> ItemText.lore(new ItemStack(Items.TRIDENT), MonsterRoles.TRIDENT_USAGE)), 1),
    SKELETON(MonsterRole.SKELETON, EntityTypes.SKELETON, "骷髅", true,
            SoundEvents.SKELETON_AMBIENT, SoundEvents.SKELETON_HURT, SoundEvents.SKELETON_DEATH, "骷髅之弓", null, null, 1),
    STRAY(MonsterRole.SKELETON, EntityTypes.STRAY, "流浪者", false,
            SoundEvents.STRAY_AMBIENT, SoundEvents.STRAY_HURT, SoundEvents.STRAY_DEATH, "流浪者之弓", null, null, 1),
    /** The silent one: no ambient sound gives a creeper away. */
    CREEPER(MonsterRole.CREEPER, EntityTypes.CREEPER, "苦力怕", true,
            null, SoundEvents.CREEPER_HURT, SoundEvents.CREEPER_DEATH, null, null, null, 1);

    static {
        ZOMBIE.conversion = new Conversion(player -> player.isEyeInFluid(FluidTags.WATER), DROWNED,
                SoundEvents.ZOMBIE_CONVERTED_TO_DROWNED, "泡在水里会慢慢化身溺尸");
        SKELETON.conversion = new Conversion(player -> player.isInPowderSnow, STRAY,
                SoundEvents.SKELETON_CONVERTED_TO_STRAY, "陷在细雪里会慢慢化身流浪者");
    }

    /** Native conversion: while {@code exposed}, the body slowly turns {@code into}; the first soak of a round shows {@code hint}. */
    public record Conversion(Predicate<ServerPlayer> exposed, MonsterForm into, SoundEvent sound, String hint) {}
    /** A weapon the form always carries, bound under {@code purpose}; {@code item} makes a fresh unbound one. */
    public record Weapon(String purpose, Supplier<ItemStack> item) {}

    public final MonsterRole role;
    public final EntityType<? extends Mob> type;
    /** What players call it, e.g. 溺尸. */
    public final String label;
    /** Selectable on its own; a variant only comes from the environment. */
    public final boolean base;
    /** Null for a form vanilla keeps silent. */
    public final SoundEvent ambientSound;
    public final SoundEvent hurtSound, deathSound;
    /** Null for forms without a bow skill. */
    public final String bowName;
    /** The companion egg of zombie-line forms, null otherwise; it hatches this form's own species. */
    public final Item egg;
    /** Null for forms without a weapon of their own. */
    public final Weapon weapon;
    /** Multiplies the role's configured max health. */
    public final double maxHealthScale;
    private Conversion conversion;

    MonsterForm(MonsterRole role, EntityType<? extends Mob> type, String label, boolean base, SoundEvent ambientSound,
                SoundEvent hurtSound, SoundEvent deathSound, String bowName, Item egg, Weapon weapon, double maxHealthScale) {
        this.role = role; this.type = type; this.label = label; this.base = base; this.ambientSound = ambientSound;
        this.hurtSound = hurtSound; this.deathSound = deathSound; this.bowName = bowName; this.egg = egg;
        this.weapon = weapon; this.maxHealthScale = maxHealthScale;
    }
    public boolean zombie() { return role == MonsterRole.ZOMBIE; }
    public boolean skeleton() { return role == MonsterRole.SKELETON; }
    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    /** Null when the environment never turns this form. */
    public Conversion conversion() { return conversion; }

    /** The form whose {@link #id()} is {@code id}, or null. */
    public static MonsterForm byId(String id) {
        for (var form : values()) if (form.id().equals(id)) return form;
        return null;
    }
    /** The form whose companion egg {@code stack} is, or null. */
    public static MonsterForm hatching(ItemStack stack) {
        for (var form : values()) if (form.egg != null && stack.is(form.egg)) return form;
        return null;
    }
}
