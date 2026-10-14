package dev.hardrush.p0.world.mixin;

import dev.hardrush.p0.world.WorldProbe;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LocationPredicate.class)
public abstract class LocationDimensionPredicateMixin {
    @Redirect(method = "matches", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;dimension()Lnet/minecraft/resources/ResourceKey;"))
    private ResourceKey<Level> hardrush$predicateDimension(ServerLevel level) {
        var expected = ((LocationPredicate) (Object) this).dimension().orElse(null);
        // Preserve data packs that explicitly target the real custom dimension key.
        if (expected != null && (expected.equals(Level.OVERWORLD) || expected.equals(Level.NETHER) || expected.equals(Level.END))) {
            return WorldProbe.semanticDimension(level);
        }
        return level.dimension();
    }
}
