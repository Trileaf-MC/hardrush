package dev.hardrush.core.hunt;

import static org.junit.jupiter.api.Assertions.*;
import static dev.hardrush.core.round.MonsterRole.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HuntOmensTest {
    final UUID a = new UUID(0, 1), b = new UUID(0, 2);

    @Test void aTargetHearsAtMostOncePerTenSeconds() {
        var omens = new HuntOmens();
        assertTrue(omens.landed(a, ZOMBIE, 1_000));
        // A second hunter landing inside the window stays silent and does not extend it.
        assertFalse(omens.landed(a, ZOMBIE, 1_000));
        assertFalse(omens.landed(a, ZOMBIE, 10_999));
        assertTrue(omens.landed(a, ZOMBIE, 11_000));
    }

    @Test void aClockThatWentBackwardsNeverSilencesForever() {
        var omens = new HuntOmens();
        assertTrue(omens.landed(a, ZOMBIE, 50_000));
        assertTrue(omens.landed(a, ZOMBIE, 1_000));
    }

    @Test void targetsKeepSeparateWindows() {
        var omens = new HuntOmens();
        assertTrue(omens.landed(a, ZOMBIE, 0));
        assertTrue(omens.landed(b, ZOMBIE, 5_000));
        assertTrue(omens.landed(a, ZOMBIE, 10_000));
        assertFalse(omens.landed(b, ZOMBIE, 14_999));
    }

    @Test void aCreeperLandsUnheardAndLeavesTheWindowOpen() {
        var omens = new HuntOmens();
        assertFalse(omens.landed(a, CREEPER, 1_000));
        assertTrue(omens.landed(a, SKELETON, 2_000), "The creeper's silent landing never silences the next hunter");
        assertFalse(omens.landed(a, CREEPER, 20_000));
        assertTrue(omens.landed(a, ZOMBIE, 20_000));
    }
}
