package dev.hardrush.core.lobby.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MarkerTagsTest {
    static MarkerSpec ok(Set<String> tags) {
        var parsed = MarkerTags.parseMarker(tags);
        if (parsed instanceof MarkerTags.Failed f) fail(f.errors().toString());
        return ((MarkerTags.Ok) parsed).spec();
    }
    static List<String> errors(MarkerTags.Parsed parsed) {
        return assertInstanceOf(MarkerTags.Failed.class, parsed).errors();
    }
    static Set<String> tags(String... tags) { return new HashSet<>(List.of(tags)); }

    @Test void everyFunctionAndBindingRoundTrips() {
        for (var f : LobbyFunction.values())
            for (var b : f.bindings()) {
                var specs = b == Binding.ZONE
                        ? List.of(MarkerSpec.flood(f, Set.of()), MarkerSpec.flood(f, Set.of("minecraft:lime_wool", "minecraft:green_wool")), MarkerSpec.box(f, 3, 2, 64))
                        : List.of(MarkerSpec.simple(f, b));
                for (var spec : specs) assertEquals(spec, ok(MarkerTags.format(spec)), f + " " + b);
            }
    }
    @Test void parsesTagsAsWrittenByHandAndIgnoresForeignTags() {
        var spec = ok(tags("hardrush.marker", "hardrush.fn.assembly", "hardrush.as.zone", "hardrush.shape.flood",
                "hardrush.mat.minecraft+lime_wool", "other_plugin_tag"));
        assertEquals(Set.of("minecraft:lime_wool"), spec.materials());
        assertEquals(new MarkerSpec(LobbyFunction.COURTYARD, Binding.ZONE, ZoneShape.BOX, Set.of(), 4, 3, 5),
                ok(tags("hardrush.marker", "hardrush.fn.courtyard", "hardrush.as.zone", "hardrush.shape.box", "hardrush.box.4.3.5")));
    }
    @Test void eachRuleRejects() {
        assertTrue(errors(MarkerTags.parseMarker(tags("hardrush.fn.rules", "hardrush.as.click"))).getFirst().contains("hardrush.marker"));
        assertEquals(1, errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.as.click"))).size());
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.rules", "hardrush.fn.history", "hardrush.as.click")));
        assertTrue(errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.bogus", "hardrush.as.click"))).getFirst().contains("未知的功能"));
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.rules")));
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.rules", "hardrush.as.click", "hardrush.as.model")));
        assertTrue(errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.rules", "hardrush.as.wave"))).getFirst().contains("未知的绑定形态"));
        assertTrue(errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.rules", "hardrush.as.point"))).getFirst().contains("【规则】"));
        // Zone shape rules.
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.assembly", "hardrush.as.zone")));
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.assembly", "hardrush.as.zone", "hardrush.shape.flood", "hardrush.shape.box")));
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.assembly", "hardrush.as.zone", "hardrush.shape.blob")));
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.assembly", "hardrush.as.zone", "hardrush.shape.flood", "hardrush.box.1.1.1")));
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.assembly", "hardrush.as.zone", "hardrush.shape.flood", "hardrush.mat.minecraft:lime_wool")));
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.assembly", "hardrush.as.zone", "hardrush.shape.flood", "hardrush.mat.Lime")));
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.assembly", "hardrush.as.zone", "hardrush.shape.box")));
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.assembly", "hardrush.as.zone", "hardrush.shape.box", "hardrush.box.1.1.1", "hardrush.box.2.2.2")));
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.assembly", "hardrush.as.zone", "hardrush.shape.box", "hardrush.box.1.1.1", "hardrush.mat.minecraft+stone")));
        for (var bad : List.of("65.1.1", "1.1", "1.1.1.1", "-1.1.1", "a.1.1", "1..1"))
            errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.assembly", "hardrush.as.zone", "hardrush.shape.box", "hardrush.box." + bad)));
        // Non-zone bindings carry no shape data.
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.landing_central", "hardrush.as.point", "hardrush.shape.box")));
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.landing_central", "hardrush.as.point", "hardrush.mat.minecraft+stone")));
        errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.landing_central", "hardrush.as.point", "hardrush.box.1.1.1")));
        assertTrue(errors(MarkerTags.parseMarker(tags("hardrush.marker", "hardrush.fn.rules", "hardrush.as.click", "hardrush.colour.red")))
                .getFirst().contains("无法识别的标签"));
    }
    @Test void triggersDeriveTheirBinding() {
        assertTrue(MarkerTags.parseTrigger(tags("some_tag")).isEmpty());
        assertEquals(MarkerSpec.simple(LobbyFunction.RULES, Binding.CLICK), ((MarkerTags.Ok) MarkerTags.parseTrigger(tags("hardrush.fn.rules")).orElseThrow()).spec());
        assertEquals(MarkerSpec.simple(LobbyFunction.MERCHANT, Binding.CLICK), ((MarkerTags.Ok) MarkerTags.parseTrigger(tags("hardrush.fn.merchant", "x")).orElseThrow()).spec());
        assertEquals(MarkerSpec.simple(LobbyFunction.CREDITS, Binding.TEXT), ((MarkerTags.Ok) MarkerTags.parseTrigger(tags("hardrush.fn.credits")).orElseThrow()).spec());
        errors(MarkerTags.parseTrigger(tags("hardrush.fn.assembly")).orElseThrow());
        errors(MarkerTags.parseTrigger(tags("hardrush.fn.vault")).orElseThrow());
        errors(MarkerTags.parseTrigger(tags("hardrush.fn.rules", "hardrush.fn.history")).orElseThrow());
        errors(MarkerTags.parseTrigger(tags("hardrush.fn.rules", "hardrush.as.click")).orElseThrow());
        errors(MarkerTags.parseTrigger(tags("hardrush.marker", "hardrush.fn.rules")).orElseThrow());
        errors(MarkerTags.parseTrigger(tags("hardrush.fn.nope")).orElseThrow());
    }
    @Test void materialTagsSwapColons() {
        assertEquals("hardrush.mat.minecraft+lime_wool", MarkerTags.materialTag("minecraft:lime_wool"));
        assertEquals(Optional.of("mymod:deco/floor_1"), MarkerTags.materialId("hardrush.mat.mymod+deco/floor_1"));
        assertTrue(MarkerTags.materialId("hardrush.mat.lime_wool").isEmpty());
        assertTrue(MarkerTags.materialId("hardrush.mat.a+b+c").isEmpty());
        assertTrue(MarkerTags.materialId("hardrush.fn.a+b").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MarkerTags.materialTag("Lime"));
    }
}
