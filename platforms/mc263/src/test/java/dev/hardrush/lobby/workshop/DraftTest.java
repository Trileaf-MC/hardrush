package dev.hardrush.lobby.workshop;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.Point;
import dev.hardrush.lobby.pack.PackException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DraftTest {
    @Test
    void rangeIsTheWorldBorderSquare() {
        var draft = Draft.create("draft1", "测试", "空白", 256);
        assertEquals(new Box(new Cell(-128, -64, -128), new Cell(127, 319, 127)), draft.range(-64, 319));
        var moved = draft.withBorder(8, -8, 96);
        assertEquals(new Box(new Cell(-40, 0, -56), new Cell(55, 10, 39)), moved.range(0, 10));
    }

    @Test
    void draftJsonKeepsEntryBottomAndRange() throws PackException {
        var draft = Draft.create("draft2", "名字", "存档 a", 256).ready()
                .withParticipant(UUID.randomUUID(), "A").withBorder(3, 4, 64).withBottomY(60)
                .withEntry(new Point(1.5, 65, -2.5, 90)).published(2);
        assertEquals(draft, WorkshopStorage.fromJson(WorkshopStorage.toJson(draft)));
        var bare = Draft.create("draft3", "空", "空白", 128);
        assertEquals(bare, WorkshopStorage.fromJson(WorkshopStorage.toJson(bare)));
    }
}
