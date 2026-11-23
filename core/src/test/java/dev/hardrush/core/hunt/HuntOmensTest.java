package dev.hardrush.core.hunt;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HuntOmensTest {
    final UUID a = new UUID(0, 1), b = new UUID(0, 2);

    @Test void aTargetHearsAtMostOncePerTenSeconds() {
        var omens = new HuntOmens();
        assertTrue(omens.landed(a, 1_000));
        // A second hunter landing inside the window stays silent and does not extend it.
        assertFalse(omens.landed(a, 1_000));
        assertFalse(omens.landed(a, 10_999));
        assertTrue(omens.landed(a, 11_000));
    }

    @Test void aClockThatWentBackwardsNeverSilencesForever() {
        var omens = new HuntOmens();
        assertTrue(omens.landed(a, 50_000));
        assertTrue(omens.landed(a, 1_000));
    }

    @Test void targetsKeepSeparateWindows() {
        var omens = new HuntOmens();
        assertTrue(omens.landed(a, 0));
        assertTrue(omens.landed(b, 5_000));
        assertTrue(omens.landed(a, 10_000));
        assertFalse(omens.landed(b, 14_999));
    }
}
