package dev.hardrush.gametest;

import dev.hardrush.core.api.BookPort;
import dev.hardrush.core.api.OperationResult;
import dev.hardrush.core.api.OperationTag;
import dev.hardrush.book.BookInventorySaver;
import dev.hardrush.book.WrittenBookPort;
import dev.hardrush.book.BookReader;
import dev.hardrush.player.ProfileJournal;
import dev.hardrush.player.ProfileTagged;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueOutput;

/** Real server/items/save files. No real client or screenshot is involved. */
public final class HistoryBookGameTest {
    @GameTest
    public void inventoryBookCanBeReadWithoutOverwritingTheHeldItem(GameTestHelper test) {
        var player = test.makeMockServerPlayerInLevel();
        var server = test.getLevel().getServer();
        try {
            player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 64));
            player.getInventory().setItem(30, WrittenBookPort.createItem(new OperationTag(UUID.randomUUID(), UUID.randomUUID(), 1), book()));
            test.assertTrue(!BookReader.open(player, 1), "Empty/unmarked slots cannot invoke book GUI");
            test.assertTrue(BookReader.open(player, 30), "Book outside hotbar opens via native GUI");
            test.assertTrue(WrittenBookPort.isHistoryBook(player.getInventory().getSelectedItem()), "Book is now in the synchronized native hand");
            test.assertValueEqual(player.getInventory().getItem(30).getCount(), 64, "Held stack is swapped, never discarded or overwritten");
            test.assertTrue(player.getInventory().getItem(30).is(Items.EMERALD), "Original item identity preserved");
            test.assertValueEqual(countBooks(player), 1, "Reading neither duplicates nor consumes a book");
        } finally { server.getPlayerList().remove(player); }
        test.succeed();
    }
    @GameTest
    public void vanillaWrittenBookHasLiteralPagesAndControlledMetadata(GameTestHelper test) {
        var scope = new OperationTag(UUID.randomUUID(), UUID.randomUUID(), 1);
        var book = book();
        var item = WrittenBookPort.createItem(scope, book);
        test.assertTrue(item.is(Items.WRITTEN_BOOK) && item.getCount() == 1, "Actual vanilla written-book item");
        test.assertTrue(WrittenBookPort.isHistoryBook(item), "Controlled marker identifies read-only book");
        var content = item.get(DataComponents.WRITTEN_BOOK_CONTENT);
        test.assertTrue(content != null && content.resolved(), "Book is resolved, never resolves player selectors");
        test.assertValueEqual(content.title().raw(), book.title(), "Bounded native title");
        var page = content.getPages(false).getFirst();
        test.assertValueEqual(page.getString(), book.pages().getFirst(), "JSON-looking name remains literal plain text");
        test.assertTrue(page.getStyle().getClickEvent() == null && page.getStyle().getHoverEvent() == null
                && page.getSiblings().isEmpty(), "No click/hover actions can be supplied by text");
        test.assertTrue(!WrittenBookPort.isHistoryBook(new ItemStack(Items.WRITTEN_BOOK)), "Vanilla unmarked book grants no exemption");
        test.succeed();
    }

    @GameTest
    public void fullOfflineStaleAndFailedSaveRetainItemsAndRetryWithoutDuplicates(GameTestHelper test) {
        var player = test.makeMockServerPlayerInLevel();
        var server = test.getLevel().getServer();
        try {
            var saved = new boolean[]{false};
            var port = new WrittenBookPort(server, (scope, id) -> true, p -> CompletableFuture.completedFuture(saved[0]));
            var scope = new OperationTag(UUID.randomUUID(), UUID.randomUUID(), 1);
            var book = book();
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.EMERALD, 64));
            test.assertValueEqual(value(port.deliver(scope, player.getUUID(), book)), BookPort.Delivery.INVENTORY_FULL,
                    "Full inventory retains pending delivery");
            test.assertValueEqual(player.getInventory().getItem(0).getCount(), 64, "Existing stack unchanged");
            test.assertValueEqual(countBooks(player), 0, "Full inventory neither overwrites nor drops book");
            player.getInventory().setItem(5, ItemStack.EMPTY);
            var failed = port.deliver(scope, player.getUUID(), book).toCompletableFuture().join();
            test.assertValueEqual(failed.outcome(), OperationResult.Outcome.RETRYABLE_FAILURE, "Unverified save cannot acknowledge claim");
            test.assertValueEqual(countBooks(player), 1, "Failed save retains one inserted item for retry");
            saved[0] = true;
            test.assertValueEqual(value(port.deliver(scope, player.getUUID(), book)), BookPort.Delivery.DELIVERED,
                    "Identical operation retries save even with inventory now full");
            test.assertValueEqual(countBooks(player), 1, "Retry cannot insert duplicate automatic book");
            var manual = new OperationTag(scope.roundId(), UUID.randomUUID(), 1);
            test.assertValueEqual(value(port.deliver(manual, player.getUUID(), book)), BookPort.Delivery.INVENTORY_FULL,
                    "Fresh manual operation requires another free slot");
            test.assertValueEqual(value(port.deliver(scope, UUID.randomUUID(), book)), BookPort.Delivery.OFFLINE,
                    "Offline target receives nothing");
            var stale = new WrittenBookPort(server, (tag, id) -> false, p -> { throw new AssertionError("Stale must not save"); });
            test.assertValueEqual(stale.deliver(scope, player.getUUID(), book).toCompletableFuture().join().outcome(),
                    OperationResult.Outcome.REJECTED, "Changed scope checked at physical delivery");
            test.assertValueEqual(countBooks(player), 1, "Rejected old callback does not mutate inventory");
        } finally { server.getPlayerList().remove(player); }
        test.succeed();
    }

    @GameTest
    public void nativePlayerSaveIsReadBackAndMismatchCannotAcknowledge(GameTestHelper test) throws Exception {
        var player = test.makeMockServerPlayerInLevel();
        var server = test.getLevel().getServer();
        var temporary = Files.createTempDirectory("hardrush-book-readback-");
        try {
            ((ProfileTagged) player).hardrush$generation(new ProfileJournal.Generation(UUID.randomUUID(), 123));
            var scope = new OperationTag(UUID.randomUUID(), UUID.randomUUID(), 1);
            player.getInventory().setItem(0, WrittenBookPort.createItem(scope, book()));
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
            player.saveWithoutId(output);
            var expected = output.buildResult();
            var file = temporary.resolve("inventory.dat");
            test.assertTrue(!BookInventorySaver.matchesSavedInventory(file, expected), "Missing save rejected");
            NbtIo.writeCompressed(expected, file);
            test.assertTrue(BookInventorySaver.matchesSavedInventory(file, expected), "Exact inventory/generation readback verified");
            var changed = expected.copy(); changed.putString("hardrush_round_id", UUID.randomUUID().toString());
            test.assertTrue(!BookInventorySaver.matchesSavedInventory(file, changed), "Another generation cannot acknowledge delivery");
            player.getInventory().setItem(1, new ItemStack(Items.DIAMOND));
            var second = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
            player.saveWithoutId(second);
            test.assertTrue(!BookInventorySaver.matchesSavedInventory(file, second.buildResult()), "Old inventory cannot acknowledge changed item state");
            test.assertTrue(new BookInventorySaver(server).apply(player).toCompletableFuture().join(),
                    "Actual native PlayerList save and bounded on-disk inventory readback");
        } finally {
            server.getPlayerList().remove(player);
            Files.deleteIfExists(temporary.resolve("inventory.dat")); Files.deleteIfExists(temporary);
        }
        test.succeed();
    }

    private static BookPort.Book book() {
        return new BookPort.Book(UUID.randomUUID(), 1, 1, "HardRush 第1轮", "HardRush",
                List.of("历史战绩\n{\"clickEvent\":\"/op @s\"}\n/hardrush history"));
    }

    private static BookPort.Delivery value(java.util.concurrent.CompletionStage<OperationResult<BookPort.Delivery>> result) {
        return result.toCompletableFuture().join().value().orElseThrow();
    }

    private static int countBooks(ServerPlayer player) {
        int count = 0;
        for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
            if (WrittenBookPort.isHistoryBook(player.getInventory().getItem(index))) count++;
        }
        return count;
    }
}
