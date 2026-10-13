package dev.hardrush.p0.disguise.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access the 26.3 field instead of assuming a numeric metadata index. */
@Mixin(Entity.class)
public interface DisguiseEntityAccessor {
    @Accessor("DATA_SHARED_FLAGS_ID")
    static EntityDataAccessor<Byte> hardrush$sharedFlags() {
        throw new AssertionError("Mixin was not applied");
    }
}
