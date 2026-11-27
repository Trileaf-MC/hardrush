package dev.hardrush.lobby;

import dev.hardrush.core.lobby.WitchGuideText;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;

/**
 * Every page of 女巫的碎碎念 crosses the native dialog codec intact, heads and item icons included. How it looks on a
 * real client is a manual check.
 */
public final class WitchGuideGameTest {
    @GameTest(maxTicks = 20)
    public void everyPageSurvivesTheNativeDialogCodec(GameTestHelper test) {
        int heads = 0, icons = 0;
        for (int i = 0; i < WitchGuideText.PAGES.size(); i++) {
            var sent = WitchGuide.dialog(i);
            var bytes = new RegistryFriendlyByteBuf(Unpooled.buffer(), test.getLevel().registryAccess());
            try {
                ClientboundShowDialogPacket.STREAM_CODEC.encode(bytes, new ClientboundShowDialogPacket(Holder.direct(sent)));
                var received = ClientboundShowDialogPacket.STREAM_CODEC.decode(bytes).dialog().value();
                test.assertValueEqual(received, sent, "Page " + (i + 1) + " decodes as sent");
            } finally { bytes.release(); }
            for (var body : sent.common().body()) {
                if (body instanceof ItemBody) icons++;
                if (body instanceof PlainMessage message)
                    heads += (int) message.contents().getSiblings().stream()
                            .filter(part -> part.getContents() instanceof ObjectContents).count();
            }
        }
        test.assertValueEqual(heads, 3, "Zombie, skeleton and creeper heads beside the witch");
        test.assertValueEqual(icons, 3, "Totem, monster coin and bread paragraphs");
        test.succeed();
    }
}
