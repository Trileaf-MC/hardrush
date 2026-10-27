package dev.hardrush.monster.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.hardrush.monster.MonsterCompanions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Native conversion, infection and reinforcements stay enabled with ancestor ownership. */
@Mixin(Zombie.class)
public abstract class SummonedZombieMixin {
    @WrapOperation(method="hurtServer", at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/EntityType;create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;"))
    private Entity inheritReinforcement(EntityType<?> type, Level level, EntitySpawnReason reason, Operation<Entity> original) {
        Entity entity = original.call(type, level, reason);
        if (entity instanceof Mob mob) MonsterCompanions.inherit((Zombie)(Object)this, mob);
        return entity;
    }
    @WrapOperation(method="finalizeSpawn", at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/ServerLevelAccessor;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean inheritNewJockeyMount(ServerLevelAccessor level, Entity entity, Operation<Boolean> original) {
        // This invocation only inserts the chicken created by this finalizeSpawn call. The
        // separate branch that borrows an existing ordinary chicken never reaches it.
        if (entity instanceof Chicken chicken) MonsterCompanions.inherit((Zombie)(Object)this, chicken);
        return original.call(level, entity);
    }
    @WrapMethod(method="convertVillagerToZombieVillager")
    private boolean inheritInfection(ServerLevel level, Villager villager, Operation<Boolean> original) {
        try (var ignored = MonsterCompanions.infection((Zombie)(Object)this)) {
            return original.call(level, villager);
        }
    }
}
