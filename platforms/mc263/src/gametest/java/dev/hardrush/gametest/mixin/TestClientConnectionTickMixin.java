package dev.hardrush.gametest.mixin;

import dev.hardrush.gametest.TestClient;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixture clients' connections tick right after the server's own connection list, in the same phase of the tick: after
 * the levels, before the player list, the GameTests and chunk sending. Embedded connections are not in that list.
 */
@Mixin(MinecraftServer.class)
public abstract class TestClientConnectionTickMixin {
    @Inject(method = "tickConnection", at = @At("TAIL"))
    private void hardrush$tickTestClients(CallbackInfo callback) {
        TestClient.tickConnections((MinecraftServer) (Object) this);
    }
}
