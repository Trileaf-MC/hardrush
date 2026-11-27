package dev.hardrush.monster;

import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.notice.ItemText;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A body can change species without changing the selected role or sortie. Everything that differs between
 * species lives here, so a new species is one more constant.
 * <p>
 * Declaration order is the priority of {@linkplain #landing() landings}: where two variants of a role would both
 * suit a landing site, the one declared first wins (docs/plan/怪物环境变种.md §1).
 */
public enum MonsterForm {
    ZOMBIE(MonsterRole.ZOMBIE, EntityTypes.ZOMBIE, "僵尸", true,
            SoundEvents.ZOMBIE_AMBIENT, SoundEvents.ZOMBIE_HURT, SoundEvents.ZOMBIE_DEATH, null, Items.ZOMBIE_SPAWN_EGG, null, 1),
    DROWNED(MonsterRole.ZOMBIE, EntityTypes.DROWNED, "溺尸", false,
            SoundEvents.DROWNED_AMBIENT, SoundEvents.DROWNED_HURT, SoundEvents.DROWNED_DEATH, null, Items.DROWNED_SPAWN_EGG,
            new Weapon(MonsterRoles.SEA_TRIDENT, () -> ItemText.lore(new ItemStack(Items.TRIDENT), MonsterRoles.TRIDENT_USAGE)), 1),
    ZOMBIFIED_PIGLIN(MonsterRole.ZOMBIE, EntityTypes.ZOMBIFIED_PIGLIN, "僵尸猪灵", false,
            SoundEvents.ZOMBIFIED_PIGLIN_AMBIENT, SoundEvents.ZOMBIFIED_PIGLIN_HURT, SoundEvents.ZOMBIFIED_PIGLIN_DEATH, null,
            Items.ZOMBIFIED_PIGLIN_SPAWN_EGG, null, 1),
    HUSK(MonsterRole.ZOMBIE, EntityTypes.HUSK, "尸壳", false,
            SoundEvents.HUSK_AMBIENT, SoundEvents.HUSK_HURT, SoundEvents.HUSK_DEATH, null, Items.HUSK_SPAWN_EGG, null, 1),
    SKELETON(MonsterRole.SKELETON, EntityTypes.SKELETON, "骷髅", true,
            SoundEvents.SKELETON_AMBIENT, SoundEvents.SKELETON_HURT, SoundEvents.SKELETON_DEATH, "骷髅之弓", null, null, 1),
    STRAY(MonsterRole.SKELETON, EntityTypes.STRAY, "流浪者", false,
            SoundEvents.STRAY_AMBIENT, SoundEvents.STRAY_HURT, SoundEvents.STRAY_DEATH, "流浪者之弓", null, null, 1),
    // Bogged and parched pay for their arrows with vanilla's 16 of 20 health (Bogged/Parched.createAttributes).
    PARCHED(MonsterRole.SKELETON, EntityTypes.PARCHED, "焦骸", false,
            SoundEvents.PARCHED_AMBIENT, SoundEvents.PARCHED_HURT, SoundEvents.PARCHED_DEATH, "焦骸之弓", null, null, .8),
    BOGGED(MonsterRole.SKELETON, EntityTypes.BOGGED, "沼骸", false,
            SoundEvents.BOGGED_AMBIENT, SoundEvents.BOGGED_HURT, SoundEvents.BOGGED_DEATH, "沼骸之弓", null, null, .8),
    /** The silent one: no ambient sound gives a creeper away. */
    CREEPER(MonsterRole.CREEPER, EntityTypes.CREEPER, "苦力怕", true,
            null, SoundEvents.CREEPER_HURT, SoundEvents.CREEPER_DEATH, null, null, null, 1);

    static {
        Predicate<ServerPlayer> soaked = player -> player.isEyeInFluid(FluidTags.WATER);
        ZOMBIE.conversion = new Conversion(soaked, DROWNED, SoundEvents.ZOMBIE_CONVERTED_TO_DROWNED, "泡在水里会慢慢化身溺尸");
        // Husk.convertsToWhenDrowning: a husk soaks back into a zombie first, which soaks on into a drowned.
        HUSK.conversion = new Conversion(soaked, ZOMBIE, SoundEvents.HUSK_CONVERTED_TO_ZOMBIE, "泡在水里会慢慢变回僵尸");
        SKELETON.conversion = new Conversion(player -> player.isInPowderSnow, STRAY,
                SoundEvents.SKELETON_CONVERTED_TO_STRAY, "陷在细雪里会慢慢化身流浪者");

        // Where vanilla would have spawned the variant instead (natural_mob_spawns and SpawnPlacements).
        // Only water outranks a variant the body already is: a husk cannot fight under water.
        DROWNED.landing = new Landing(MonsterHabitat.Site::submerged, true);
        ZOMBIFIED_PIGLIN.landing = new Landing(MonsterHabitat.Site::nether, false);
        HUSK.landing = new Landing(site -> site.openSky() && site.hosts(EntityTypes.HUSK), false);
        STRAY.landing = new Landing(site -> site.snowing() && site.openSky(), false);
        PARCHED.landing = new Landing(site -> site.openSky() && site.hosts(EntityTypes.PARCHED), false);
        // Monster.checkMonsterSpawnRules: a bogged needs no sky.
        BOGGED.landing = new Landing(site -> site.hosts(EntityTypes.BOGGED), false);

        // Vanilla's own arrow effects (Stray/Bogged/Parched.getArrow), capped for a player's arrows (§3).
        STRAY.arrowEffect = new ArrowEffect(MobEffects.SLOWNESS, 200);
        PARCHED.arrowEffect = new ArrowEffect(MobEffects.WEAKNESS, 200);
        BOGGED.arrowEffect = new ArrowEffect(MobEffects.POISON, 100);
    }

    /** Native conversion: while {@code exposed}, the body slowly turns {@code into}; the first soak of a round shows {@code hint}. */
    public record Conversion(Predicate<ServerPlayer> exposed, MonsterForm into, SoundEvent sound, String hint) {}
    /** A weapon the form always carries, bound under {@code purpose}; {@code item} makes a fresh unbound one. */
    public record Weapon(String purpose, Supplier<ItemStack> item) {}
    /**
     * A landing site where the role's body lands as this form. {@code overridesVariant}: it also turns a body that
     * already is another variant; otherwise only the role's base form is turned.
     */
    public record Landing(Predicate<MonsterHabitat.Site> where, boolean overridesVariant) {}
    /** The effect the native model puts on its arrows, and the longest a player's arrow carries it. */
    public record ArrowEffect(Holder<MobEffect> effect, int maxTicks) {}

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
    private Landing landing;
    private ArrowEffect arrowEffect;

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
    /** Null when no landing site gives this form. */
    public Landing landing() { return landing; }
    /** Null when the form's arrows carry nothing of their own. */
    public ArrowEffect arrowEffect() { return arrowEffect; }
    /** The selectable form of this form's role: a husk's is the zombie. */
    public MonsterForm baseForm() {
        for (var form : values()) if (form.role == role && form.base) return form;
        throw new IllegalStateException("No base form for " + role);
    }

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
