package dev.hardrush.book.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerList.class)
public interface PlayerListBookSaveAccessor {
    @Invoker("save") void hardrush$saveBookInventory(ServerPlayer player);
}
