package dev.hardrush.world.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lobby text slots write into tagged text displays; vanilla keeps the text accessors private. */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
    @Invoker("getText") Component hardrush$text();
    @Invoker("setText") void hardrush$setText(Component text);
}
