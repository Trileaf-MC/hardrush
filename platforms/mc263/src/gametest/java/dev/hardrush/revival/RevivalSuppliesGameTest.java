package dev.hardrush.revival;

import dev.hardrush.core.round.HumanThreat;
import dev.hardrush.core.round.MonsterDimension;
import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.MonsterWallet;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.ShopOffer;
import dev.hardrush.core.round.WitchRecord;
import dev.hardrush.gametest.MonsterRoleGameTest;
import java.util.ArrayList;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Revival supplies join a body that may already hold the kept belongings of its previous life (PR #74 review). */
public final class RevivalSuppliesGameTest {
    @GameTest
    public void suppliesNeverReplaceKeptBelongingsAndOverflowWaitsForTheLanding(GameTestHelper test) {
        var player = MonsterRoleGameTest.actor(test);
        try {
            // What the previous life kept: a torch in the off hand and a full backpack.
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH, 3));
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            var life = new Participant(player.getUUID(), "Revived", ParticipantRole.HUMAN_ACTIVE, true, false, 0, 0, 2, 0,
                    MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of(), MonsterWallet.EMPTY, 0, false, HumanThreat.NONE,
                    Map.of(), WitchRecord.NONE.supplied(2, Map.of(ShopOffer.SHIELD, 1, ShopOffer.BREAD, 1, ShopOffer.COAL, 3)));
            var leftovers = new ArrayList<ItemStack>();
            RevivalSupplies.fill(player, life, leftovers);
            test.assertTrue(player.getOffhandItem().is(Items.TORCH) && player.getOffhandItem().getCount() == 3,
                    "The bought shield never replaces what the off hand kept");
            test.assertTrue(leftovers.stream().anyMatch(stack -> stack.is(Items.SHIELD))
                            && leftovers.stream().filter(stack -> stack.is(Items.BREAD)).mapToInt(ItemStack::getCount).sum() == 8
                            && leftovers.stream().filter(stack -> stack.is(Items.COAL)).mapToInt(ItemStack::getCount).sum() == 24,
                    "Supplies with no room wait to be set down at the landing: " + leftovers);
            test.assertTrue(player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(4)).isEmpty(),
                    "Nothing spills where the body stands before it lands");
        } finally { MonsterRoleGameTest.remove(player); }
        test.succeed();
    }
}
