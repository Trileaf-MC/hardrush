package dev.hardrush.lobby.workshop;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.Point;
import dev.hardrush.lobby.pack.PackException;
import java.util.List;
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
    void rangeFromAPackCoversItsBoundsFarFromTheOrigin() {
        for (var bounds : List.of(new Box(new Cell(4990, 63, -20), new Cell(5053, 70, 43)),
                new Box(new Cell(-7001, 0, 300), new Cell(-6997, 5, 300)), new Box(new Cell(10, 0, 10), new Cell(265, 0, 265)))) {
            var center = DraftImport.horizontalCenter(bounds);
            for (int diameter : List.of(64, 128, 256)) {
                int size = DraftImport.diameterFor(diameter, bounds, 256);
                var range = Draft.create("draft1", "测试", "包库", diameter).withBorder(center.x(), center.z(), size).range(0, 100);
                assertTrue(range.contains(new Cell(bounds.min().x(), 50, bounds.min().z())) && range.contains(new Cell(bounds.max().x(), 50, bounds.max().z())),
                        bounds + " in " + range);
                assertTrue(size >= diameter && size <= 256);
            }
        }
        assertEquals(96, DraftImport.diameterFor(64, new Box(new Cell(0, 0, 0), new Cell(63, 0, 9)), 256));
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
