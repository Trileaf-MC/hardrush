package dev.hardrush.monster;

import dev.hardrush.gametest.MonsterRoleGameTest;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;

/** docs/plan/钟声显形.md: vanilla's bell reveals monster players as raiders, every time it rings. The look stays a manual check. */
public final class MonsterBellGameTest {
    /** Vanilla resonates from the fifth tick for forty more, then glows; a margin covers the tick the ring lands in. */
    private static final int REVEAL_TICKS = 50;

    @GameTest(maxTicks = 400)
    public void aRungBellRevealsMonsterPlayersOnlyEveryTime(GameTestHelper test) {
        var monster = MonsterRoleGameTest.actor(test);
        // Two more actors join the first one's arena, a few blocks from the bell.
        var partner = MonsterRoleGameTest.actor(test);
        var human = MonsterRoleGameTest.actor(test);
        var level = monster.level();
        var bell = monster.blockPosition().east(2);
        var zombie = new Zombie[1];
        Runnable cleanup = () -> {
            if (zombie[0] != null) zombie[0].discard();
            level.setBlockAndUpdate(bell, Blocks.AIR.defaultBlockState());
            MonsterRoleGameTest.remove(monster); MonsterRoleGameTest.remove(partner); MonsterRoleGameTest.remove(human);
        };
        test.startSequence()
                .thenIdle(80)
                .thenExecute(guarded(cleanup, () -> {
                    MonsterRoles.select(monster, "zombie");
                    partner.teleportTo(monster.getX(), monster.getY(), monster.getZ() + 4);
                    MonsterRoles.select(partner, "skeleton");
                    human.teleportTo(monster.getX() - 4, monster.getY(), monster.getZ() - 4);
                    var helper = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.MOB_SUMMONED);
                    helper.setNoAi(true);
                    helper.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                    helper.snapTo(monster.getX() - 2, monster.getY(), monster.getZ(), 0, 0);
                    level.addFreshEntity(helper);
                    zombie[0] = helper;
                    level.setBlockAndUpdate(bell, Blocks.BELL.defaultBlockState());
                    ring(level, bell);
                }))
                .thenIdle(REVEAL_TICKS)
                .thenExecute(guarded(cleanup, () -> {
                    test.assertTrue(monster.hasEffect(MobEffects.GLOWING), "The bell reveals the monster player");
                    test.assertTrue(monster.isCurrentlyGlowing(), "The disguise mirrors the owner's glow");
                    test.assertTrue(partner.hasEffect(MobEffects.GLOWING), "Every monster player in range glows");
                    test.assertFalse(human.hasEffect(MobEffects.GLOWING), "A human never glows");
                    test.assertFalse(zombie[0].hasEffect(MobEffects.GLOWING), "A native zombie is no raider and stays dark");
                    monster.removeEffect(MobEffects.GLOWING); partner.removeEffect(MobEffects.GLOWING);
                    ring(level, bell);
                }))
                .thenIdle(REVEAL_TICKS)
                .thenExecute(guarded(cleanup, () -> {
                    test.assertTrue(monster.hasEffect(MobEffects.GLOWING) && partner.hasEffect(MobEffects.GLOWING),
                            "A bell never rests: ringing it again reveals again");
                    cleanup.run();
                }))
                .thenSucceed();
    }

    private static void ring(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        if (!((BellBlock) Blocks.BELL).attemptToRing(level, pos, Direction.NORTH)) throw new IllegalStateException("The bell did not ring");
    }

    private static Runnable guarded(Runnable cleanup, Runnable step) {
        return () -> {
            try { step.run(); } catch (Throwable failure) { cleanup.run(); throw failure; }
        };
    }

}
