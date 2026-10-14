package dev.hardrush.core.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** A failed/full-inventory delivery leaves the persistent claim pending. */
public interface BookPort {
    record Book(UUID roundId, int edition, int volume, String title, String author, List<String> pages) {
        public Book {
            Objects.requireNonNull(roundId, "roundId");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(author, "author");
            pages = List.copyOf(pages);
            if (edition < 1 || volume < 1 || title.isBlank() || author.isBlank() || pages.isEmpty()) {
                throw new IllegalArgumentException("Invalid history book");
            }
        }
    }

    enum Delivery { DELIVERED, INVENTORY_FULL, OFFLINE }

    /** Must not overwrite an inventory item, drop the book, or mark an undelivered claim consumed. */
    CompletionStage<OperationResult<Delivery>> deliver(OperationTag tag, UUID playerId, Book book);
}
