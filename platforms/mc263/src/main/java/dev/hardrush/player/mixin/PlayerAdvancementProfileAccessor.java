package dev.hardrush.player.mixin;

import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerAdvancements.class)
public interface PlayerAdvancementProfileAccessor {
    @Accessor("progress") Map<AdvancementHolder, AdvancementProgress> hardrush$progress();
    @Accessor("visible") Set<AdvancementHolder> hardrush$visible();
    @Accessor("progressChanged") Set<AdvancementHolder> hardrush$progressChanged();
    @Accessor("rootsToUpdate") Set<AdvancementNode> hardrush$rootsToUpdate();
    @Accessor("isFirstPacket") void hardrush$firstPacket(boolean first);
    @Invoker("markForVisibilityUpdate") void hardrush$markVisibility(AdvancementHolder holder);
    @Invoker("registerListeners") void hardrush$registerListeners(AdvancementHolder holder);
}
