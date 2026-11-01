package dev.hardrush.world.mixin;

import java.util.Map;
import java.util.function.Function;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Templates are shared server-wide, while each level generates on its own worker; the round overworld and the
 * vanilla overworld can fill one palette's plain HashMap at once (ConcurrentModificationException in
 * structure_starts). This lookup is the cache's only access, so locking it makes the cache single-writer.
 */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructurePaletteCacheMixin {
    @Redirect(method = "blocks(Lnet/minecraft/world/level/block/Block;)Ljava/util/List;", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
    private Object hardrush$lockedLookup(Map<Object, Object> cache, Object block, Function<Object, Object> filter) {
        synchronized (cache) { return cache.computeIfAbsent(block, filter); }
    }
}
