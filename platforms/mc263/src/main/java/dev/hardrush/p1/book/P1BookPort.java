package dev.hardrush.p1.book;

import dev.hardrush.core.api.BookPort;
import dev.hardrush.core.api.OperationResult;
import dev.hardrush.core.api.OperationTag;
import dev.hardrush.core.book.SafeBookText;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiPredicate;
import java.util.function.Function;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;

/** Vanilla written-book item. Delivery never overwrites or drops another inventory stack. */
public final class P1BookPort implements BookPort {
    private static final String MARKER = "hardrush_history_book";
    private static final String SOURCE = "hardrush_book_round";
    private static final String EDITION = "hardrush_book_edition";
    private static final String VOLUME = "hardrush_book_volume";
    private static final String OPERATION = "hardrush_book_operation";
    private final MinecraftServer server;
    private final BiPredicate<OperationTag, UUID> eligibility;
    private final Function<ServerPlayer, CompletionStage<Boolean>> inventorySaver;

    /** The saver must persist this inventory before returning true; a vanilla void save is insufficient. */
    public P1BookPort(MinecraftServer server, BiPredicate<OperationTag, UUID> eligibility,
            Function<ServerPlayer, CompletionStage<Boolean>> inventorySaver) {
        this.server = Objects.requireNonNull(server); this.eligibility = Objects.requireNonNull(eligibility);
        this.inventorySaver = Objects.requireNonNull(inventorySaver);
    }

    @Override public CompletionStage<OperationResult<Delivery>> deliver(OperationTag tag, UUID playerId, Book book) {
        Objects.requireNonNull(tag); Objects.requireNonNull(playerId); Objects.requireNonNull(book);
        var future = new CompletableFuture<OperationResult<Delivery>>();
        Runnable action = () -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || player.isRemoved()) {
                    future.complete(OperationResult.verified(tag, Delivery.OFFLINE)); return;
                }
                if (!eligibility.test(tag, playerId)) {
                    future.complete(OperationResult.failed(tag, OperationResult.Outcome.REJECTED, "书册发放作用域已变化")); return;
                }
                // Check a retained item before free slots: SQLite ACK retry must not insert another copy.
                if (!hasDelivery(player, tag.operationId(), book)) {
                    int free = player.getInventory().getFreeSlot();
                    if (free < 0) {
                        future.complete(OperationResult.verified(tag, Delivery.INVENTORY_FULL)); return;
                    }
                    ItemStack stack = createItem(tag, book);
                    player.getInventory().setItem(free, stack);
                    player.inventoryMenu.sendAllDataToRemote();
                }
                inventorySaver.apply(player).whenComplete((saved, failure) -> {
                    if (failure == null && Boolean.TRUE.equals(saved)) {
                        future.complete(OperationResult.verified(tag, Delivery.DELIVERED));
                    } else {
                        future.complete(OperationResult.failed(tag, OperationResult.Outcome.RETRYABLE_FAILURE, "书册库存尚未持久化，保留待领"));
                    }
                });
            } catch (RuntimeException failure) {
                future.complete(OperationResult.failed(tag, OperationResult.Outcome.RETRYABLE_FAILURE, "书册发放未完成，保留待领"));
            }
        };
        dev.hardrush.p1.P1MainThreadExecutor.forServer(server).execute(action, () -> future.complete(
                OperationResult.failed(tag, OperationResult.Outcome.RETRYABLE_FAILURE, "服务器正在停止，书册保留待领")));
        return future;
    }

    public static ItemStack createItem(OperationTag tag, Book book) {
        Objects.requireNonNull(tag); Objects.requireNonNull(book);
        if (book.title().length() > 32 || book.author().length() > 64 || book.pages().size() > SafeBookText.MAX_PAGES) {
            throw new IllegalArgumentException("Book metadata exceeds platform bounds");
        }
        requireSafeLiteral(book.title()); requireSafeLiteral(book.author());
        var pages = book.pages().stream().map(page -> {
            if (page.length() > SafeBookText.MAX_PAGE_CHARACTERS) throw new IllegalArgumentException("Book page too large");
            requireSafeLiteral(page);
            // No JSON parsing, selector resolution, click actions, or hover actions are accepted.
            return Filterable.<Component>passThrough(Component.literal(page));
        }).toList();
        var stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(book.title()),
                book.author(), 0, pages, true));
        var data = new CompoundTag();
        data.putBoolean(MARKER, true);
        data.putString(SOURCE, book.roundId().toString());
        data.putInt(EDITION, book.edition()); data.putInt(VOLUME, book.volume());
        data.putString(OPERATION, tag.operationId().toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return stack;
    }

    /** Read-only UI exemption only; this marker never grants a history query or claim entitlement. */
    public static boolean isHistoryBook(ItemStack stack) {
        if (stack == null || !stack.is(Items.WRITTEN_BOOK) || stack.get(DataComponents.WRITTEN_BOOK_CONTENT) == null) return false;
        var custom = stack.get(DataComponents.CUSTOM_DATA);
        return custom != null && custom.copyTag().getBooleanOr(MARKER, false);
    }

    private static boolean hasDelivery(ServerPlayer player, UUID operationId, Book book) {
        var inventory = player.getInventory();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            ItemStack item = inventory.getItem(index);
            if (!isHistoryBook(item)) continue;
            var data = item.get(DataComponents.CUSTOM_DATA).copyTag();
            if (data.getStringOr(OPERATION, "").equals(operationId.toString())
                    && data.getStringOr(SOURCE, "").equals(book.roundId().toString())
                    && data.getIntOr(EDITION, 0) == book.edition() && data.getIntOr(VOLUME, 0) == book.volume()) return true;
        }
        return false;
    }

    private static void requireSafeLiteral(String value) {
        for (int index = 0; index < value.length(); ) {
            int point = value.codePointAt(index); index += Character.charCount(point);
            int type = Character.getType(point);
            if (point == '§' || point != '\n' && Character.isISOControl(point) || type == Character.FORMAT
                    || type == Character.SURROGATE || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException("Unsafe book literal");
            }
        }
    }
}
