package dev.hardrush.gametest;

import dev.hardrush.p0.monster.MonsterClouds;
import dev.hardrush.p2.combat.FrozenMonsterSource;
import dev.hardrush.p2.combat.MonsterSourceHolder;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;

public final class MonsterCloudGameTest {
    @GameTest(maxTicks = 200)
    public void ownerCannotConsumeCloudOrReceiveInstantAndLastingEffects(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        var cloud = cloud(owner);
        test.runAfterDelay(80, () -> {
            try {
                owner.setHealth(10);
                MonsterClouds.bind(cloud, owner.getUUID());
                for (int tick = 0; tick < 5; tick++) { cloud.tickCount++; cloud.tick(); }
                test.assertValueEqual(owner.getHealth(), 10f, "Owner cannot receive instantaneous cloud healing");
                test.assertTrue(!owner.hasEffect(MobEffects.SLOWNESS), "Owner cannot receive lasting cloud effects");
                test.assertValueEqual(cloud.getRadius(), 3f, "Rejected contact does not consume cloud radius");
            } finally { cloud.discard(); MonsterRoleGameTest.remove(owner); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void allowedNativeMobReceivesBothEffectsAndConsumesOneContact(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        var cloud = cloud(owner);
        var cow = EntityTypes.COW.create(owner.level(), EntitySpawnReason.COMMAND);
        test.runAfterDelay(80, () -> {
            try {
                cow.snapTo(owner.position()); cow.setHealth(5); owner.level().addFreshEntity(cow);
                MonsterClouds.bind(cloud, owner.getUUID());
                owner.teleportTo(owner.getX() + 5, owner.getY(), owner.getZ());
                for (int tick = 0; tick < 5; tick++) { cloud.tickCount++; cloud.tick(); }
                test.assertTrue(cow.getHealth() > 5 && cow.hasEffect(MobEffects.SLOWNESS), "Admitted contact applies both instantaneous and lasting effects");
                test.assertValueEqual(cloud.getRadius(), 2.5f, "Admitted victim consumes one native contact");
            } finally { cow.discard(); cloud.discard(); MonsterRoleGameTest.remove(owner); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void oldFrozenCloudCannotHealApplyEffectsOrConsumeRadius(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        var cloud = cloud(owner);
        var cow = EntityTypes.COW.create(owner.level(), EntitySpawnReason.COMMAND);
        test.runAfterDelay(80, () -> {
            try {
                cow.snapTo(owner.position()); cow.setHealth(5); owner.level().addFreshEntity(cow);
                owner.teleportTo(owner.getX() + 5, owner.getY(), owner.getZ());
                ((MonsterSourceHolder) cloud).hardrush$stampMonsterSource(new FrozenMonsterSource(UUID.randomUUID(), owner.getUUID(), 1, 1, true));
                for (int tick = 0; tick < 5; tick++) { cloud.tickCount++; cloud.tick(); }
                test.assertValueEqual(cow.getHealth(), 5f, "A cloud from another round cannot heal");
                test.assertTrue(!cow.hasEffect(MobEffects.SLOWNESS), "A cloud from another round cannot apply lasting effects");
                test.assertValueEqual(cloud.getRadius(), 3f, "Rejected frozen source consumes no contact");
            } finally { cow.discard(); cloud.discard(); MonsterRoleGameTest.remove(owner); }
            test.succeed();
        });
    }

    private static AreaEffectCloud cloud(ServerPlayer owner) {
        var cloud = new AreaEffectCloud(owner.level(), owner.getX(), owner.getY(), owner.getZ());
        cloud.setOwner(owner);
        cloud.setRadius(3); cloud.setRadiusOnUse(-.5f); cloud.setRadiusPerTick(0);
        cloud.setWaitTime(0); cloud.setDuration(100); cloud.setPotionDurationScale(1);
        cloud.addEffect(new MobEffectInstance(MobEffects.INSTANT_HEALTH, 100));
        cloud.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100));
        return cloud;
    }
}
