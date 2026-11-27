package dev.hardrush.monster;

import java.util.Collection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Keeps a monster player's movement speed where the native monster's would be under the same modifiers
 * (docs/plan/怪物移速换算.md). Vanilla's {@code MoveControl} sets a mob's speed to its movement attribute and
 * {@code Mob.setSpeed} feeds the same number in as forward input, so a mob moves by attribute²; a player moves by
 * 1.0 × attribute. The body's base is therefore the native base squared ({@code MonsterRoles.bind}), and this class
 * squares the modifiers too: potions, powder snow and soul speed act on the native value before it is squared.
 * Server thread only.
 */
public final class MonsterGait {
    /** The one compensating modifier; transient, so it is never saved and the next tick rebuilds it. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("hardrush", "native_gait");
    // Vanilla LivingEntity's sprint modifier: monster players never sprint, but a stray one must not be squared.
    private static final Identifier SPRINTING = Identifier.withDefaultNamespace("sprinting");
    private static final double EPSILON = 1e-6;

    private MonsterGait() {}

    /** Recomputes the compensation for a monster whose native model's base movement attribute is {@code nativeBase}. */
    static void update(ServerPlayer player, double nativeBase) {
        var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        var modifiers = speed.getModifiers().stream().filter(modifier -> !modifier.id().equals(ID) && !modifier.id().equals(SPRINTING)).toList();
        double target = Math.pow(value(speed, nativeBase, modifiers), 2);
        double current = value(speed, speed.getBaseValue(), modifiers);
        double amount = current > EPSILON ? target / current - 1 : 0;
        var existing = speed.getModifier(ID);
        if (existing != null && Math.abs(existing.amount() - amount) < EPSILON) return;
        if (existing == null && Math.abs(amount) < EPSILON) return;
        speed.removeModifier(ID);
        if (Math.abs(amount) >= EPSILON) speed.addTransientModifier(new AttributeModifier(ID, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /** Leaving the monster body: the human walks as vanilla made it. */
    static void clear(ServerPlayer player) {
        var speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(ID);
    }

    /** Vanilla {@code AttributeInstance.calculateValue} over {@code modifiers} from {@code base}, clamped to the attribute's range. */
    static double value(AttributeInstance instance, double base, Collection<AttributeModifier> modifiers) {
        for (var modifier : modifiers) if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) base += modifier.amount();
        double result = base;
        for (var modifier : modifiers) if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) result += base * modifier.amount();
        for (var modifier : modifiers) if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) result *= 1 + modifier.amount();
        return instance.getAttribute().value().sanitizeValue(result);
    }
}
