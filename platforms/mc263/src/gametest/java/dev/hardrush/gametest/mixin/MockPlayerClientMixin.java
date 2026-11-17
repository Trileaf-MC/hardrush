package dev.hardrush.gametest.mixin;

import com.mojang.authlib.GameProfile;
import dev.hardrush.gametest.TestClients;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.network.CommonListenerCookie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Vanilla's mock player joins with the same real-client options as every other fixture connection. */
@Mixin(GameTestHelper.class)
public abstract class MockPlayerClientMixin {
    @Redirect(method = "makeMockServerPlayerInLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/CommonListenerCookie;createInitial(Lcom/mojang/authlib/GameProfile;Z)Lnet/minecraft/server/network/CommonListenerCookie;"))
    private CommonListenerCookie hardrush$realClient(GameProfile profile, boolean transferred) {
        return TestClients.cookie(profile);
    }
}
