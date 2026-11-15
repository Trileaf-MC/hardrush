package dev.hardrush.lobby.pack;

import static dev.hardrush.lobby.pack.PackFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class LobbyPlanJsonTest {
    @Test void planRoundTripsThroughText() throws PackException {
        var plan = plan();
        var text = LobbyPlanJson.write(LobbyPlanJson.planToJson(plan));
        assertEquals(plan, LobbyPlanJson.planFromJson(LobbyPlanJson.parse(text), BOUNDS));
        // Stable: writing what was read gives the same text.
        assertEquals(text, LobbyPlanJson.write(LobbyPlanJson.planToJson(LobbyPlanJson.planFromJson(LobbyPlanJson.parse(text), BOUNDS))));
        assertTrue(text.contains("\"cells\": [\n"), text);
        assertTrue(text.contains("[0, 63, -1]"), text);
        assertTrue(text.contains("\"type\": \"box\""), text);
    }

    @Test void packRoundTripsManifestAndPlan() throws PackException {
        var json = LobbyPlanJson.parse(new String(json(manifest(), plan()), java.nio.charset.StandardCharsets.UTF_8));
        var manifest = LobbyPlanJson.manifestFromJson(json);
        assertEquals(manifest(), manifest);
        assertEquals(plan(), LobbyPlanJson.planFromPack(json, manifest.bounds()));
    }

    @Test void installationRoundTripsThroughCurrentJson() throws PackException {
        var installation = new LobbyInstallation(3, "hardrush:lobby_3",
                new LobbyInstallation.Pack("default", "默认营地", 2, java.util.List.of("HardRush", "粉丝"), "ab".repeat(32)),
                plan(), LobbyInstallation.State.COMPLETE);
        var text = LobbyPlanJson.write(LobbyPlanJson.installationToJson(installation));
        assertEquals(installation, LobbyPlanJson.installationFromJson(LobbyPlanJson.parse(text)));
        assertTrue(text.contains("\"state\": \"COMPLETE\""), text);
        var unknown = LobbyPlanJson.parse(text.replace("\"COMPLETE\"", "\"PREPARED\""));
        assertTrue(assertThrows(PackException.class, () -> LobbyPlanJson.installationFromJson(unknown)).getMessage().contains("PREPARED"));
        var noPlan = LobbyPlanJson.parse(text);
        noPlan.remove("plan");
        assertThrows(PackException.class, () -> LobbyPlanJson.installationFromJson(noPlan));
    }

    @Test void entityTriggersHaveNoBlockFields() {
        var trigger = LobbyPlanJson.planToJson(plan()).getAsJsonArray("triggers").get(1).getAsJsonObject();
        assertEquals("history", trigger.get("function").getAsString());
        assertFalse(trigger.has("block"));
        assertFalse(trigger.has("block_id"));
    }

    @Test void unknownFunctionsAndZoneTypesAreRejected() {
        var json = LobbyPlanJson.planToJson(plan());
        json.getAsJsonArray("triggers").get(0).getAsJsonObject().addProperty("function", "teleporter");
        assertTrue(reject(json).contains("teleporter"));

        json = LobbyPlanJson.planToJson(plan());
        json.getAsJsonObject("points").add("spawn_pad", new JsonArray());
        assertTrue(reject(json).contains("spawn_pad"));

        json = LobbyPlanJson.planToJson(plan());
        json.getAsJsonObject("zones").getAsJsonArray("assembly").get(0).getAsJsonObject().addProperty("type", "sphere");
        assertTrue(reject(json).contains("sphere"));
    }

    @Test void malformedShapesAreRejectedNotThrown() {
        var json = LobbyPlanJson.planToJson(plan());
        // A point function under zones breaks a LobbyPlan invariant.
        json.getAsJsonObject("zones").add("landing_central", json.getAsJsonObject("zones").get("assembly"));
        reject(json);
        json = LobbyPlanJson.planToJson(plan());
        json.getAsJsonArray("altar_lights").set(0, JsonParser.parseString("[1.5, 2, 3]"));
        reject(json);
        json = LobbyPlanJson.planToJson(plan());
        json.getAsJsonArray("models").get(0).getAsJsonObject().add("point", JsonParser.parseString("[1, 2, 3]"));
        reject(json);
        json = LobbyPlanJson.planToJson(plan());
        json.add("triggers", new JsonObject());
        reject(json);
    }

    @Test void oversizedArraysAreRejected() {
        var json = LobbyPlanJson.planToJson(plan());
        var cells = new JsonArray();
        for (int i = 0; i <= LobbyPlanJson.MAX_CELLS; i++) cells.add(JsonParser.parseString("[" + i + ", 63, 0]"));
        json.getAsJsonObject("zones").getAsJsonArray("assembly").get(0).getAsJsonObject().add("cells", cells);
        assertTrue(reject(json).contains("上限"));

        json = LobbyPlanJson.planToJson(plan());
        var triggers = new JsonArray();
        var one = json.getAsJsonArray("triggers").get(0);
        for (int i = 0; i <= LobbyPlanJson.MAX_ITEMS; i++) triggers.add(one);
        json.add("triggers", triggers);
        assertTrue(reject(json).contains("上限"));
    }

    private static String reject(JsonObject plan) {
        return assertThrows(PackException.class, () -> LobbyPlanJson.planFromJson(plan, BOUNDS)).getMessage();
    }
}
