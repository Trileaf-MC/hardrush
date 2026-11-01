package dev.hardrush.gametest;

import dev.hardrush.lobby.CampKit;
import dev.hardrush.lobby.RevivalTotems;
import dev.hardrush.round.RoundSession;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Real camp identities and native packets, including the client's optimistic inventory correction. */
final class CampTransferFixtures {
    /** Respawn may queue ONLINE behind the death write; no pending write alone does not mean idle. */
    static boolean actionsReady(RoundSession session) {
        try {
            var field = RoundSession.class.getDeclaredField("events");
            field.setAccessible(true);
            return !session.mutationPending() && ((java.util.Collection<?>) field.get(session)).isEmpty();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    static void append(GameTestSequence sequence, LobbyRuntimeGameTest.Fixture f,
            LobbyRuntimeGameTest.Fixture.Peer donor, LobbyRuntimeGameTest.Fixture.Peer receiver) {
        int[] balances = new int[2];
        ItemEntity[] floor = new ItemEntity[1];
        sequence.thenExecute(() -> {
            CampKit.tick(f.server);
            balances[0] = donor.participant().wallet().coins();
            balances[1] = receiver.participant().wallet().coins();
            f.test.assertTrue(balances[0] > 0 && balances[1] > 0, "Both camp wallets have native coin projections");
            throwKey(donor.current(), CampKit::isCoin, false);
            f.test.assertTrue(f.session.mutationPending(), "The floor stack waits for durable debit");
            f.test.assertTrue(floor(donor.current(), CampKit::isCoin) == null, "No spendable coins spawn before commit");
            int packets = donor.connection.packets.size();
            // A second currency is still visible, but must not race the pending coin write.
            throwKey(donor.current(), RevivalTotems::is, true);
            f.test.assertValueEqual(LobbyRuntimeGameTest.heldTotems(donor.current()), 1,
                    "Rejected Q restores the server totem immediately, before the next tick");
            f.test.assertTrue(donor.connection.packets.subList(packets, donor.connection.packets.size()).stream()
                    .anyMatch(packet -> packet instanceof ClientboundContainerSetContentPacket content
                            && content.items().stream().anyMatch(RevivalTotems::is)),
                    "Rejected Q explicitly corrects the sender's predicted empty slot");
        }).thenWaitUntil(() -> f.await(!f.session.mutationPending() && donor.participant().wallet().coins() == balances[0] - 1
                && held(donor.current(), CampKit::isCoin) == balances[0] - 1 && floor(donor.current(), CampKit::isCoin) != null,
                "Coin Q commits exactly one debit and projects the remaining balance"))
                .thenExecute(() -> {
                    floor[0] = floor(donor.current(), CampKit::isCoin);
                    floor[0].playerTouch(receiver.current());
                    f.test.assertFalse(f.session.mutationPending(), "Coins retain the native pickup delay");
                }).thenWaitUntil(() -> f.await(receiver.connection.packets.stream().anyMatch(packet ->
                        packet instanceof ClientboundAddEntityPacket added && added.getId() == floor[0].getId()),
                        "The other player's connection receives the actual coin drop entity"))
                .thenExecute(() -> {
                    floor[0].setPickUpDelay(0);
                    floor[0].playerTouch(receiver.current());
                    floor[0].playerTouch(receiver.current());
                }).thenWaitUntil(() -> f.await(!f.session.mutationPending() && floor[0].isRemoved()
                        && receiver.participant().wallet().coins() == balances[1] + 1,
                        "Repeated touches credit a transferred coin only once"))
                .thenExecute(() -> throwInventory(receiver.current(), CampKit::isCoin, true))
                .thenWaitUntil(() -> f.await(!f.session.mutationPending() && receiver.participant().wallet().coins() == 0
                        && held(receiver.current(), CampKit::isCoin) == 0 && floor(receiver.current(), CampKit::isCoin) != null,
                        "Inventory Ctrl+Q transfers the entire coin stack"))
                .thenExecute(() -> {
                    floor[0] = floor(receiver.current(), CampKit::isCoin);
                    f.test.assertValueEqual(floor[0].getItem().getCount(), balances[1] + 1, "Floor count equals the committed coin debit");
                    floor[0].setPickUpDelay(0);
                    floor[0].playerTouch(donor.current());
                }).thenWaitUntil(() -> f.await(!f.session.mutationPending() && floor[0].isRemoved()
                        && donor.participant().wallet().coins() == balances[0] + balances[1], "Coin transfers conserve total value"))
                .thenExecute(() -> throwKey(donor.current(), CampKit::isCoin, true))
                .thenWaitUntil(() -> f.await(!f.session.mutationPending() && donor.participant().wallet().coins() == 0
                        && floor(donor.current(), CampKit::isCoin) != null, "Hotbar Ctrl+Q debits the entire coin stack"))
                .thenExecute(() -> {
                    floor[0] = floor(donor.current(), CampKit::isCoin);
                    floor[0].setPickUpDelay(0);
                    floor[0].playerTouch(donor.current());
                }).thenWaitUntil(() -> f.await(!f.session.mutationPending() && floor[0].isRemoved()
                        && donor.participant().wallet().coins() == balances[0] + balances[1], "The owner can reclaim their own coins"))
                .thenExecute(() -> throwKey(donor.current(), RevivalTotems::is, true))
                .thenWaitUntil(() -> f.await(!f.session.mutationPending() && donor.participant().revivalCharges() == 0
                        && floor(donor.current(), RevivalTotems::is) != null, "Totem Ctrl+Q consumes the last charge"))
                .thenExecute(() -> {
                    var p = donor.participant();
                    var reply = new AtomicReference<RoundSession.Reply>();
                    f.session.requestRevival(f.round().roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), reply::set);
                    f.test.assertTrue(reply.get() != null && !reply.get().success(), "Dropping the last totem removes altar eligibility");
                    floor[0] = floor(donor.current(), RevivalTotems::is);
                    floor[0].setPickUpDelay(0);
                    floor[0].playerTouch(donor.current());
                }).thenWaitUntil(() -> f.await(!f.session.mutationPending() && floor[0].isRemoved()
                        && donor.participant().revivalCharges() == 1, "Only reclaiming the totem restores altar eligibility"))
                .thenExecute(() -> throwInventory(donor.current(), RevivalTotems::is, false))
                .thenWaitUntil(() -> f.await(!f.session.mutationPending() && donor.participant().revivalCharges() == 0
                        && floor(donor.current(), RevivalTotems::is) != null, "Inventory Q shares the totem transfer path"))
                .thenExecute(() -> {
                    floor[0] = floor(donor.current(), RevivalTotems::is);
                    floor[0].setPickUpDelay(0);
                    floor[0].playerTouch(donor.current());
                }).thenWaitUntil(() -> f.await(!f.session.mutationPending() && floor[0].isRemoved()
                        && donor.participant().revivalCharges() == 1, "The lifecycle can continue with its original revival charge"))
                .thenExecute(() -> {
                    // Even forbidden non-currency drops must undo the local client's prediction.
                    var player = donor.current();
                    player.getInventory().setItem(8, new ItemStack(Items.STICK));
                    int packets = donor.connection.packets.size();
                    throwKey(player, stack -> stack.is(Items.STICK), false);
                    f.test.assertTrue(player.getInventory().getItem(8).is(Items.STICK), "Ordinary camp items remain sealed");
                    f.test.assertTrue(donor.connection.packets.subList(packets, donor.connection.packets.size()).stream()
                            .anyMatch(ClientboundContainerSetContentPacket.class::isInstance), "Rejected ordinary Q also corrects client prediction");
                    player.getInventory().setItem(8, ItemStack.EMPTY);
                });
    }

    private static int held(ServerPlayer player, Predicate<ItemStack> match) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var item = player.getInventory().getItem(slot);
            if (match.test(item)) count += item.getCount();
        }
        return count;
    }
    private static ItemEntity floor(ServerPlayer player, Predicate<ItemStack> match) {
        return player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(8)).stream()
                .filter(item -> match.test(item.getItem())).findFirst().orElse(null);
    }
    private static void throwKey(ServerPlayer player, Predicate<ItemStack> match, boolean all) {
        select(player, match);
        player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(all
                ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS : ServerboundPlayerActionPacket.Action.DROP_ITEM,
                BlockPos.ZERO, Direction.DOWN));
    }
    private static void select(ServerPlayer player, Predicate<ItemStack> match) {
        for (int slot = 0; slot < 9; slot++) if (match.test(player.getInventory().getItem(slot))) {
            player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));
            if (player.getInventory().getSelectedSlot() != slot) throw new IllegalStateException("Camp rejected native hotbar selection");
            return;
        }
        throw new IllegalStateException("No matching camp currency on the hotbar");
    }
    private static void throwInventory(ServerPlayer player, Predicate<ItemStack> match, boolean all) {
        var menu = player.inventoryMenu;
        for (short slot = 0; slot < menu.slots.size(); slot++) if (match.test(menu.getSlot(slot).getItem())) {
            player.connection.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), slot,
                    (byte) (all ? 1 : 0), ContainerInput.THROW, new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
            return;
        }
        throw new IllegalStateException("No matching camp currency in the inventory");
    }
}
