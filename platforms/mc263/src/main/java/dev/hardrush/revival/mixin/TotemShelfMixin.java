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
import net.minecraft.world.level.block.SelectableSlotContainer;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.SideChainPartBlock;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shelves (26.3 {@code ShelfBlock.useItemOn}) swap without a menu: an unpowered shelf swaps the held stack with the
 * clicked slot, a powered one swaps the right end of the hotbar with the whole connected row ({@code swapHotbar}).
 */
@Mixin(ShelfBlock.class)
public abstract class TotemShelfMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void hardrush$keepOffShelf(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                       InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> ci) {
        if (!(player instanceof ServerPlayer owner) || hand != InteractionHand.MAIN_HAND
                || !(level.getBlockEntity(pos) instanceof ShelfBlockEntity)
                || ((SelectableSlotContainer) (Object) this).getHitSlot(hit, state.getValue(ShelfBlock.FACING)).isEmpty()) return;
        var moving = state.getValue(ShelfBlock.POWERED) ? hotbarTotem(level, pos, owner) : stack;
        if (!RevivalTotems.is(moving)) return;
        TotemKeeping.refuse(owner, moving);
        ci.setReturnValue(InteractionResult.CONSUME);
    }

    /** The first totem among the hotbar slots a powered row would swap, as {@code swapHotbar} maps them. */
    private ItemStack hotbarTotem(Level level, BlockPos pos, ServerPlayer player) {
        var row = ((SideChainPartBlock) (Object) this).getAllBlocksConnectedTo(level, pos);
        int width = 0;
        for (var part : row) if (level.getBlockEntity(part) instanceof ShelfBlockEntity shelf) width = Math.max(width, shelf.getContainerSize());
        var inventory = player.getInventory();
        for (int slot = Math.max(0, 9 - row.size() * width); slot < 9; slot++) {
            if (RevivalTotems.is(inventory.getItem(slot))) return inventory.getItem(slot);
        }
        return ItemStack.EMPTY;
    }
}
