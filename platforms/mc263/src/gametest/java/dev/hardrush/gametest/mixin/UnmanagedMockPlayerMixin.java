package dev.hardrush.gametest.mixin;

import dev.hardrush.player.PlayerProfiles;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Existing P0 fixtures are deliberate unmanaged players, not real connections entering a P1 round. */
@Mixin(GameTestHelper.class)
public abstract class UnmanagedMockPlayerMixin {
    @Redirect(method = "makeMockServerPlayerInLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V"))
    private void hardrush$unmanagedFixture(PlayerList list, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        dev.hardrush.gametest.UnmanagedProfileFixtures.run(() -> list.placeNewPlayer(connection, player, cookie));
    }
}
