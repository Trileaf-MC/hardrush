package dev.hardrush.gametest.mixin;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Test artifact only: {@link dev.hardrush.gametest.TestTeardown} listens for the end of the helper's test. */
@Mixin(GameTestHelper.class)
public interface GameTestHelperAccessor {
    @Accessor("testInfo") GameTestInfo hardrush$testInfo();
}
