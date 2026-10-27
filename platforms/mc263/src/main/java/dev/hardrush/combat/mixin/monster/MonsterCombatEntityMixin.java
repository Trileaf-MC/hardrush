package dev.hardrush.combat.mixin.monster;

import dev.hardrush.combat.*;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class MonsterCombatEntityMixin implements MonsterSourceHolder {
    @Unique private FrozenMonsterSource hardrush$monsterSource;
    @Override public Optional<FrozenMonsterSource> hardrush$monsterSource() { return Optional.ofNullable(hardrush$monsterSource); }
    @Override public void hardrush$stampMonsterSource(FrozenMonsterSource source) {
        if (hardrush$monsterSource == null) hardrush$monsterSource = source;
    }
    @Inject(method="saveWithoutId", at=@At("TAIL"))
    private void save(ValueOutput output, CallbackInfo ci) {
        if (hardrush$monsterSource != null) output.putString("hardrush_p2_monster_source", hardrush$monsterSource.encode());
    }
    @Inject(method="load", at=@At("TAIL"))
    private void load(ValueInput input, CallbackInfo ci) {
        var encoded = input.getStringOr("hardrush_p2_monster_source", "");
        if (!encoded.isEmpty()) hardrush$monsterSource = FrozenMonsterSource.decode(encoded)
                .orElse(new FrozenMonsterSource(new UUID(0, 0), new UUID(0, 0), 0, 0, false));
    }
    @Inject(method="hurtOrSimulate", at=@At("HEAD"), cancellable=true)
    private void harm(DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        if (MonsterCombatRuntime.rejectDamage((Entity)(Object)this, source)) ci.setReturnValue(false);
    }
    @Inject(method="setRemainingFireTicks", at=@At("HEAD"), cancellable=true)
    private void fire(int ticks, CallbackInfo ci) {
        if (ticks > 0 && MonsterCombatRuntime.rejectCurrent((Entity)(Object)this)) ci.cancel();
    }
    @Inject(method={"push(DDD)V", "pushFromExplosion"}, at=@At("HEAD"), cancellable=true)
    private void impulse(CallbackInfo ci) {
        if (MonsterCombatRuntime.rejectCurrent((Entity)(Object)this)) ci.cancel();
    }
    @Inject(method="ignoreExplosion", at=@At("HEAD"), cancellable=true)
    private void explosion(Explosion explosion, CallbackInfoReturnable<Boolean> ci) {
        if (MonsterCombatRuntime.reject(explosion.getDirectSourceEntity(), (Entity)(Object)this)) ci.setReturnValue(true);
    }
}
