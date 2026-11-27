package dev.hardrush.gametest;

import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.MonsterWallet;
import dev.hardrush.core.round.ShopOffer;
import dev.hardrush.lobby.CampKit;
import dev.hardrush.monster.MonsterGacha;
import dev.hardrush.monster.MonsterItems;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.round.RoundSession;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Two vault helmets: the camp monster puts the older one on natively, and that is the one its sortie wears. */
final class CampGearFixtures {
    private CampGearFixtures() {}

    /** Before the first deployment of the zombie camp monster {@code m}, with every earlier write settled. */
    static void wearOlderHelmetInCamp(GameTestSequence sequence, LobbyRuntimeGameTest.Fixture f, LobbyRuntimeGameTest.Fixture.Peer m) {
        step(sequence, f, "Grant coins for two trial keys", reply -> f.session.adminCoins(f.round().roundId(), m.profile.id(),
                m.participant().lifeSequence(), m.participant().wallet().coins(), 6, "gametest", "两把试炼钥匙", reply));
        for (int i = 0; i < 2; i++) step(sequence, f, "Buy a trial key", reply -> f.session.requestShopPurchase(f.round().roundId(),
                m.profile.id(), m.participant().lifeSequence(), m.participant().monsterDeploymentSequence(), ShopOffer.TRIAL_KEY,
                m.participant().wallet().nextPrice(f.round().rules(), ShopOffer.TRIAL_KEY), reply));
        for (var helmet : new Item[] {Items.IRON_HELMET, Items.GOLDEN_HELMET}) step(sequence, f, "Open a vault", reply ->
                f.session.requestVaultOpen(f.round().roundId(), m.profile.id(), m.participant().lifeSequence(),
                        m.participant().monsterDeploymentSequence(), ShopOffer.TRIAL_KEY, MonsterWallet.GearSlot.HEAD,
                        Set.of(MonsterRole.ZOMBIE), MonsterGacha.encode(f.server.registryAccess(), new ItemStack(helmet)), reply));
        sequence.thenExecute(() -> {
            var player = m.current();
            CampKit.tick(f.server);
            f.test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET), "The newest vault helmet is worn in the camp");
            hold(player, stack -> stack.is(Items.IRON_HELMET));
            int packets = m.client.packets().size();
            player.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, 0, 0));
            f.test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET) && player.getMainHandItem().is(Items.GOLDEN_HELMET),
                    "A right click swaps the older vault helmet on natively: head=" + player.getItemBySlot(EquipmentSlot.HEAD)
                            + ", hand=" + player.getMainHandItem() + ", protected=" + dev.hardrush.protection.ProtectionRuntime.protectedEntity(player)
                            + ", sealed=" + dev.hardrush.player.PlayerProfiles.sealed(player) + ", mode=" + player.gameMode.getGameModeForPlayer()
                            + ", predicted=" + dev.hardrush.player.NativeArmorSwap.predicted(player.getMainHandItem()));
            f.test.assertTrue(resynced(m, packets), "The swap is sent back to the client");
            CampKit.tick(f.server);
            f.test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "The projection does not take the chosen helmet off");
        }).thenWaitUntil(() -> f.await(!f.session.mutationPending() && newestHelmet(f, m).is(Items.IRON_HELMET),
                "The chosen helmet is committed as the worn one"))
                .thenExecute(() -> {
                    var player = m.current();
                    CampKit.tick(f.server);
                    f.test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "The committed choice stays on");
                    // Ordinary items stay sealed in the camp, and the client's own prediction of their swap is undone.
                    hold(player, stack -> stack.is(Items.GOLDEN_HELMET));
                    var stick = player.getMainHandItem();
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHAINMAIL_HELMET));
                    int packets = m.client.packets().size();
                    player.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, 0, 0));
                    f.test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET) && resynced(m, packets),
                            "A world helmet is not worn in the camp, and the client is corrected");
                    player.setItemInHand(InteractionHand.MAIN_HAND, stick);
                });
    }

    /** Right after the first deployment: the sortie wears the camp's choice and can still swap natively. */
    static void assertSortieWearsChoice(LobbyRuntimeGameTest.Fixture f, LobbyRuntimeGameTest.Fixture.Peer m) {
        var player = m.player;
        var head = player.getItemBySlot(EquipmentSlot.HEAD);
        f.test.assertTrue(head.is(Items.IRON_HELMET) && MonsterItems.matches(player, head, "gear"), "The sortie wears the helmet chosen in the camp");
        hold(player, stack -> stack.is(Items.GOLDEN_HELMET) && MonsterItems.matches(player, stack, "gear"));
        int packets = m.client.packets().size();
        player.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, 0, 0));
        f.test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET) && player.getMainHandItem().is(Items.IRON_HELMET)
                && MonsterItems.matches(player, player.getMainHandItem(), "gear"), "A monster swaps its spare helmet on natively, keeping both");
        f.test.assertTrue(MonsterRoles.nativeModel(player).getItemBySlot(EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET),
                "The native model wears the swapped helmet");
        f.test.assertTrue(resynced(m, packets), "The swap is sent back to the client");
        MonsterRoles.repairLoadout(player);
        f.test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET), "The loadout keeps the monster's own choice");
    }

    private static ItemStack newestHelmet(LobbyRuntimeGameTest.Fixture f, LobbyRuntimeGameTest.Fixture.Peer m) {
        return m.participant().wallet().gear().stream().filter(piece -> piece.slot() == MonsterWallet.GearSlot.HEAD)
                .max(Comparator.comparingInt(MonsterWallet.GearPiece::serial))
                .map(piece -> MonsterGacha.decode(f.server.registryAccess(), piece.item())).orElse(ItemStack.EMPTY);
    }

    private static boolean resynced(LobbyRuntimeGameTest.Fixture.Peer m, int from) {
        return m.client.packets().subList(from, m.client.packets().size()).stream().anyMatch(ClientboundContainerSetContentPacket.class::isInstance);
    }

    /** Moves the matching stack onto the hotbar if needed and selects it with the native packet. */
    private static void hold(ServerPlayer player, Predicate<ItemStack> match) {
        var inventory = player.getInventory();
        int slot = -1;
        for (int i = 0; i < 36 && slot < 0; i++) if (match.test(inventory.getItem(i))) slot = i;
        if (slot < 0) throw new IllegalStateException("Nothing to hold");
        if (slot >= 9) {
            int free = 8;
            var moved = inventory.getItem(free);
            inventory.setItem(free, inventory.getItem(slot)); inventory.setItem(slot, moved);
            slot = free;
        }
        player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));
        if (inventory.getSelectedSlot() != slot) throw new IllegalStateException("Hotbar selection was refused");
    }

    private static void step(GameTestSequence sequence, LobbyRuntimeGameTest.Fixture f, String what,
                             Consumer<Consumer<RoundSession.Reply>> request) {
        var done = new AtomicReference<RoundSession.Reply>();
        sequence.thenWaitUntil(() -> f.await(CampTransferFixtures.actionsReady(f.session), "Settled before: " + what))
                .thenExecute(() -> request.accept(done::set))
                .thenWaitUntil(() -> f.await(done.get() != null && !f.session.mutationPending(), "Answered: " + what))
                .thenExecute(() -> f.test.assertTrue(done.get().success(), what + ": " + done.get().message()));
    }
}
