package dev.hardrush.p1.combat.mixin;

import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.combat.CombatOriginCodec;
import dev.hardrush.p1.combat.CombatOriginHolder;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class CombatEntityMixin implements CombatOriginHolder {
    @Unique private CombatOrigin hardrush$origin;
    @Unique private boolean hardrush$sealed;
    @Override public Optional<CombatOrigin> hardrush$combatOrigin() { return Optional.ofNullable(hardrush$origin); }
    @Override public boolean hardrush$combatOriginSealed() { return hardrush$sealed; }
    @Override public void hardrush$sealCombatOrigin(Optional<CombatOrigin> origin) {
        if (!hardrush$sealed) { hardrush$origin = origin.orElse(null); hardrush$sealed = true; }
    }
    @Inject(method="saveWithoutId", at=@At("TAIL"))
    private void saveOrigin(ValueOutput output, CallbackInfo ci) {
        if (hardrush$sealed) output.putString("hardrush_combat_origin", hardrush$origin == null ? "" : CombatOriginCodec.encode(hardrush$origin));
    }
    @Inject(method="load", at=@At("TAIL"))
    private void loadOrigin(ValueInput input, CallbackInfo ci) {
        // Historical entities with no snapshot remain unknown; reassigning owner cannot manufacture provenance.
        hardrush$origin = CombatOriginCodec.decode(input.getStringOr("hardrush_combat_origin", "")).orElse(null);
        hardrush$sealed = hardrush$origin != null || (Object)this instanceof net.minecraft.world.entity.projectile.Projectile
                || (Object)this instanceof net.minecraft.world.entity.item.PrimedTnt;
    }
}
