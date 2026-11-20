package dev.hardrush.revival.mixin;

import dev.hardrush.revival.RevivalTotems;
import dev.hardrush.revival.TotemKeeping;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 26.3 {@code DecoratedPotBlock.useItemOn} stores one of the held stack; a totem is refused. */
@Mixin(DecoratedPotBlock.class)
public abstract class TotemDecoratedPotMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void hardrush$keepOutOfPot(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                       InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> ci) {
        // Vanilla only stores into an empty pot or onto the same stack; anything else just wobbles it.
        if (player instanceof ServerPlayer owner && RevivalTotems.is(stack) && level.getBlockEntity(pos) instanceof DecoratedPotBlockEntity pot
                && (pot.getTheItem().isEmpty() || ItemStack.isSameItemSameComponents(pot.getTheItem(), stack))) {
            TotemKeeping.refuse(owner, stack);
            ci.setReturnValue(InteractionResult.FAIL);
        }
    }
}
