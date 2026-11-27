package dev.hardrush.core.lobby.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuildManifestTest {
    static BuildManifest.Marker point(int x, int y, int z) {
        return new BuildManifest.Marker(MarkerSpec.simple(LobbyFunction.LANDING_CENTRAL, Binding.POINT), new Cell(x, y, z), 180);
    }
    static BuildManifest manifest(Cell origin, List<BuildManifest.Marker> markers, List<BuildManifest.Sign> signs) {
        return new BuildManifest(origin, false, markers, signs);
    }

    @Test void manifestCellsMoveWithTheBuildingsMinimumCorner() {
        var m = manifest(new Cell(4, 2, 7), List.of(), List.of());
        // The building's own (0, 0, 0) is the manifest's origin.
        assertEquals(new Cell(-10, 64, -8), m.toWorld(new Cell(4, 2, 7), new Cell(-10, 64, -8)));
        assertEquals(new Cell(10, 65, 15), m.toWorld(new Cell(24, 3, 30), new Cell(-10, 64, -8)));
        // A zero origin is the building's own frame.
        assertEquals(new Cell(3, 70, 5), manifest(new Cell(0, 0, 0), List.of(), List.of()).toWorld(new Cell(3, 6, 5), new Cell(0, 64, 0)));
    }

    @Test void markersAndSignsMustStandOverTheBuilding() {
        var origin = new Cell(4, 2, 7);
        assertEquals(Optional.empty(), manifest(origin, List.of(point(4, 2, 7), point(13, -50, 16)), List.of()).horizontalProblem(10, 10));
        var west = manifest(origin, List.of(point(4, 2, 7), point(3, 2, 7)), List.of()).horizontalProblem(10, 10).orElseThrow();
        assertTrue(west.startsWith("第 2 条标记（【中央落点】）的位置 [3, 2, 7]"), west);
        assertTrue(west.contains("x 4～13，z 7～16"), west);
        assertTrue(manifest(origin, List.of(point(14, 2, 7)), List.of()).horizontalProblem(10, 10).isPresent(), "x one past the far edge");
        assertTrue(manifest(origin, List.of(point(4, 2, 17)), List.of()).horizontalProblem(10, 10).isPresent(), "z one past the far edge");
        var sign = manifest(origin, List.of(), List.of(new BuildManifest.Sign(new Cell(4, 2, 6), List.of("a"), null)))
                .horizontalProblem(10, 10).orElseThrow();
        assertTrue(sign.startsWith("第 1 条告示牌的位置 [4, 2, 6]"), sign);
        // Hostile coordinates do not overflow.
        assertTrue(manifest(new Cell(Integer.MIN_VALUE, 0, 0), List.of(point(Integer.MAX_VALUE, 0, 0)), List.of()).horizontalProblem(10, 10).isPresent());
    }

    @Test void heightIsCheckedAfterPlacing() {
        var m = manifest(new Cell(0, 10, 0), List.of(point(0, 10, 0), point(0, 270, 0)), List.of());
        assertEquals(Optional.empty(), m.heightProblem(new Cell(0, 50, 0), -64, 319));
        var high = m.heightProblem(new Cell(0, 64, 0), -64, 319).orElseThrow();
        assertTrue(high.startsWith("第 2 条标记（【中央落点】）的位置 [0, 270, 0] 放置后超出了世界高度（Y -64～319）"), high);
        assertTrue(manifest(new Cell(0, 0, 0), List.of(point(0, Integer.MIN_VALUE, 0)), List.of()).heightProblem(new Cell(0, 64, 0), -64, 319).isPresent());
    }

    @Test void limits() {
        var origin = new Cell(0, 0, 0);
        assertDoesNotThrow(() -> manifest(origin, Collections.nCopies(BuildManifest.MAX_MARKERS, point(0, 0, 0)),
                Collections.nCopies(BuildManifest.MAX_SIGNS, new BuildManifest.Sign(origin, List.of(), List.of()))));
        var markers = assertThrows(IllegalArgumentException.class,
                () -> manifest(origin, Collections.nCopies(BuildManifest.MAX_MARKERS + 1, point(0, 0, 0)), List.of()));
        assertTrue(markers.getMessage().contains("512"), markers.getMessage());
        assertThrows(IllegalArgumentException.class, () -> manifest(origin, List.of(),
                Collections.nCopies(BuildManifest.MAX_SIGNS + 1, new BuildManifest.Sign(origin, null, null))));

        var four = List.of("一", "二", "三", "四");
        assertEquals(four, new BuildManifest.Sign(origin, four, null).front());
        assertNull(new BuildManifest.Sign(origin, four, null).back(), "A side left out stays as the building has it");
        var five = new ArrayList<>(four);
        five.add("五");
        assertThrows(IllegalArgumentException.class, () -> new BuildManifest.Sign(origin, five, null));
        assertThrows(IllegalArgumentException.class, () -> new BuildManifest.Sign(origin, null, five));
        // 64 code points, counted as characters even outside the BMP.
        var longest = "字".repeat(BuildManifest.MAX_LINE_LENGTH);
        assertDoesNotThrow(() -> new BuildManifest.Sign(origin, List.of(longest), null));
        assertDoesNotThrow(() -> new BuildManifest.Sign(origin, List.of("😀".repeat(BuildManifest.MAX_LINE_LENGTH)), null));
        var tooLong = assertThrows(IllegalArgumentException.class, () -> new BuildManifest.Sign(origin, List.of("", longest + "a"), null));
        assertTrue(tooLong.getMessage().contains("第 2 行"), tooLong.getMessage());

        assertThrows(IllegalArgumentException.class, () -> new BuildManifest.Marker(MarkerSpec.simple(LobbyFunction.RULES, Binding.CLICK), origin, Float.NaN));
    }

    @Test void manifestFileNameReplacesTheExtension() {
        assertEquals("camp.hardrush.json", BuildManifest.fileNameFor("camp.schem"));
        assertEquals("my.camp.hardrush.json", BuildManifest.fileNameFor("my.camp.litematic"));
        assertEquals("camp.hardrush.json", BuildManifest.fileNameFor("camp"));
    }
}
