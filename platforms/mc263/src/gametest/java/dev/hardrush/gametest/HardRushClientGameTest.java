package dev.hardrush.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

/** Exercises actual server packets with an isolated Fabric test client. */
public final class HardRushClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext test) {
        command("hardrushp0 status"); // Fail early if the debug server is unavailable.
        test.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client,
                ServerAddress.parseString("127.0.0.1:25586"),
                new ServerData("HardRush debug", "127.0.0.1:25586", ServerData.Type.OTHER), false, null));
        test.waitFor(client -> client.player != null && client.level != null && client.getConnection() != null, 1200);
        test.waitForScreen(null);
        JsonArray evidence = new JsonArray();
        try {
            for (String slot : new String[]{"weapon.mainhand", "weapon.offhand", "armor.head", "armor.chest", "armor.legs", "armor.feet"}) {
                command("item replace entity HRDebug " + slot + " with minecraft:air");
            }
            command("gamemode survival HRDebug");
            evidence.add(command("attribute HRDebug minecraft:attack_damage base get"));
            evidence.add(command("attribute HRDebug minecraft:attack_damage get"));
            command("effect clear HRDebug");
            command("time set day");
            command("execute in minecraft:overworld run tp HRDebug 0.5 150 0.5 0 0");
            command("fill -8 149 -8 8 149 8 minecraft:stone");
            command("fill -8 150 -8 8 158 8 minecraft:air");
            command("kill @e[tag=hr_selfview_target]");
            command("execute positioned 0.5 150 0.5 run kill @e[type=minecraft:item,distance=..10]");
            command("summon minecraft:pig 0.5 150 2.5 {NoAI:1b,NoGravity:1b,Silent:1b,Tags:[\"hr_selfview_target\"]}");
            test.waitTicks(30);
            for (String kind : new String[]{"zombie", "skeleton", "creeper"}) {
                command("tp HRDebug 0.5 150 0.5 0 0");
                command("item replace entity HRDebug weapon.mainhand with minecraft:air");
                var applied = command("hardrushp0 disguise HRDebug " + kind);
                if (!applied.toString().contains("disguise=" + kind)) throw new AssertionError("Disguise rejected: " + applied);
                test.waitFor(HardRushClientGameTest::hasSelfBody, 400);
                test.waitFor(client -> client.hitResult instanceof EntityHitResult hit
                    && hit.getEntity().distanceTo(client.player) < 1, 400);
                evidence.add(snapshot(test, kind + "-visible-baseline"));
                screenshot(test, "hardrush-" + kind + "-visible-first-person");

                if (kind.equals("zombie")) {
                    var before = command("data get entity HRDebug Pos");
                    command("hardrushdebug camera HRDebug self");
                    test.waitFor(client -> client.getCameraEntity() != client.player, 100);
                    test.waitTicks(5);
                    evidence.add(snapshot(test, "camera-before-movement"));
                    screenshot(test, "hardrush-camera-first-person");
                    test.getInput().holdKeyFor(options -> options.keyUp, 20);
                    var after = command("data get entity HRDebug Pos");
                    JsonObject camera = snapshot(test, "camera-after-movement");
                    camera.add("serverPositionBefore", before);
                    camera.add("serverPositionAfter", after);
                    evidence.add(camera);
                    if (!before.equals(after)) throw new AssertionError("Camera experiment unexpectedly sent movement; re-evaluate result");
                    command("hardrushdebug camera HRDebug reset");
                    test.waitFor(client -> client.getCameraEntity() == client.player, 100);
                    command("tp HRDebug 0.5 150 0.5 0 0");
                }

                command("hardrushp0 selfview HRDebug hidden");
                test.waitFor(client -> !hasSelfBody(client) && client.getCameraEntity() == client.player, 100);
                test.getInput().lookAt(0, 25);
                test.waitFor(client -> client.hitResult instanceof EntityHitResult hit && hit.getEntity().getType() == EntityTypes.PIG, 100);
                evidence.add(snapshot(test, kind + "-hidden-aim"));
                screenshot(test, "hardrush-" + kind + "-hidden-first-person");
                command("data merge entity @e[tag=hr_selfview_target,limit=1] {Health:10.0f}");
                test.waitTicks(25);
                test.getInput().pressKey(options -> options.keyAttack);
                test.waitTicks(10);
                var health = command("data get entity @e[tag=hr_selfview_target,limit=1] Health");
                if (health.get("resultValue").getAsInt() >= 10) throw new AssertionError("Attack caused no server damage: " + health);
                JsonObject attack = snapshot(test, kind + "-hidden-attack");
                attack.add("serverTargetHealth", health);
                evidence.add(attack);
                test.getInput().lookAt(0, 80);
                test.waitFor(client -> client.hitResult != null && !(client.hitResult instanceof EntityHitResult), 100);
                screenshot(test, "hardrush-" + kind + "-hidden-look-down");
                test.getInput().lookAt(0, 0);

                command("hardrushp0 selfview HRDebug visible");
                test.waitFor(HardRushClientGameTest::hasSelfBody, 100);
                test.getInput().pressKey(options -> options.keyTogglePerspective);
                test.waitFor(client -> client.options.getCameraType() == CameraType.THIRD_PERSON_BACK);
                screenshot(test, "hardrush-" + kind + "-manual-third-person-back");
                test.getInput().pressKey(options -> options.keyTogglePerspective);
                test.waitFor(client -> client.options.getCameraType() == CameraType.THIRD_PERSON_FRONT);
                screenshot(test, "hardrush-" + kind + "-manual-third-person-front");
                test.getInput().pressKey(options -> options.keyTogglePerspective);
                test.waitFor(client -> client.options.getCameraType() == CameraType.FIRST_PERSON);
                command("hardrushp0 selfview HRDebug hidden");
                test.waitFor(client -> !hasSelfBody(client), 100);
                for (int cycle = 0; cycle < 20; cycle++) {
                    command("hardrushp0 selfview HRDebug visible");
                    test.waitFor(HardRushClientGameTest::hasSelfBody, 100);
                    command("hardrushp0 selfview HRDebug hidden");
                    test.waitFor(client -> !hasSelfBody(client), 100);
                }
                evidence.add(snapshot(test, kind + "-after-20-toggles"));
            }
            var movementBefore = command("data get entity HRDebug Pos");
            test.getInput().holdKeyFor(options -> options.keyLeft, 10);
            var movementAfter = command("data get entity HRDebug Pos");
            if (movementBefore.equals(movementAfter)) throw new AssertionError("Hidden self-view did not send movement");
            JsonObject movement = snapshot(test, "hidden-movement");
            movement.add("serverPositionBefore", movementBefore);
            movement.add("serverPositionAfter", movementAfter);
            evidence.add(movement);

            command("hardrushp0 world enter HRDebug nether");
            test.waitFor(client -> client.level.dimension().toString().contains("/nether"), 400);
            test.waitTicks(40);
            if (!command("hardrushp0 status HRDebug").toString().contains("selfSpawned=false")) {
                throw new AssertionError("Hidden mode lost after dimension change");
            }
            test.runOnClient(client -> {
                if (hasSelfBody(client)) throw new AssertionError("Unexpected self body after dimension change");
            });
            evidence.add(snapshot(test, "hidden-after-dimension-change"));
            command("hardrushp0 clear HRDebug");
            test.waitFor(client -> !hasSelfBody(client) && !client.player.isInvisible() && client.getCameraEntity() == client.player, 100);
            evidence.add(snapshot(test, "cleared"));
            screenshot(test, "hardrush-disguise-cleared");
        } finally {
            try {
                Path out = Path.of(System.getProperty("hardrush.root"), ".local/research/self-view/client-results.json");
                Files.writeString(out, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(evidence));
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
            command("hardrushdebug camera HRDebug reset");
            command("hardrushp0 clear HRDebug");
            test.runOnClient(client -> client.disconnectFromWorld(net.minecraft.network.chat.Component.literal("GameTest complete")));
            test.setScreen(TitleScreen::new);
            test.waitForScreen(TitleScreen.class);
        }
    }

    private static void screenshot(ClientGameTestContext test, String name) {
        test.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(
            Path.of(System.getProperty("hardrush.root"), ".local/research/self-view/screenshots")));
    }

    private static boolean hasSelfBody(net.minecraft.client.Minecraft client) {
        for (var entity : client.level.entitiesForRendering()) {
            if ((entity.getType() == EntityTypes.ZOMBIE || entity.getType() == EntityTypes.SKELETON || entity.getType() == EntityTypes.CREEPER)
                    && entity.distanceTo(client.player) < 1) return true;
        }
        return false;
    }

    private static JsonObject snapshot(ClientGameTestContext test, String step) {
        return test.computeOnClient(client -> {
            JsonObject result = new JsonObject();
            result.addProperty("step", step);
            result.addProperty("cameraIsPlayer", client.getCameraEntity() == client.player);
            result.addProperty("cameraType", client.options.getCameraType().name());
            result.addProperty("cameraEntity", client.getCameraEntity().getType().toString());
            result.addProperty("clientPosition", client.player.position().toString());
            result.addProperty("playerInvisible", client.player.isInvisible());
            result.addProperty("attackDamage", client.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));
            result.addProperty("selfBodyPresent", hasSelfBody(client));
            result.addProperty("hit", client.hitResult instanceof EntityHitResult hit ? hit.getEntity().getType().toString()
                : client.hitResult == null ? "null" : client.hitResult.getType().name());
            for (var entity : client.level.entitiesForRendering()) {
                if (entity.getType() == EntityTypes.PIG && entity instanceof LivingEntity living) result.addProperty("targetHealth", living.getHealth());
            }
            return result;
        });
    }

    private static JsonObject command(String command) {
        try {
            Path root = Path.of(System.getProperty("hardrush.root"));
            var config = JsonParser.parseString(Files.readString(root.resolve(".local/debug/server/config/mcpfabric.config.json"))).getAsJsonObject();
            if (!config.get("host").getAsString().equals("127.0.0.1") || config.get("port").getAsInt() != 25599
                    || !config.get("requireAuth").getAsBoolean()) throw new IllegalStateException("Unexpected bridge configuration");
            var body = new JsonObject();
            body.addProperty("jsonrpc", "2.0"); body.addProperty("id", 1); body.addProperty("method", "command.run");
            var params = new JsonObject(); params.addProperty("command", command); body.add("params", params);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:25599/rpc"))
                    .timeout(Duration.ofSeconds(45)).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.get("token").getAsString())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            try (var http = HttpClient.newHttpClient()) {
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                var result = JsonParser.parseString(response.body()).getAsJsonObject();
                if (response.statusCode() != 200 || result.has("error")) throw new IllegalStateException(response.body());
                if (!result.getAsJsonObject("result").get("success").getAsBoolean()
                        && !(command.startsWith("kill @e[tag=hr_selfview_target]")
                            || command.startsWith("execute positioned 0.5 150 0.5 run kill @e[type=minecraft:item,")
                            || (command.equals("effect clear HRDebug") && response.body().contains("Target has no effects to remove"))
                            || (command.startsWith("fill ") && response.body().contains("No blocks were filled"))
                            || (command.startsWith("data merge entity @e[tag=hr_selfview_target,") && response.body().contains("Nothing changed")))) {
                    throw new IllegalStateException("Command failed: " + response.body());
                }
                return result.getAsJsonObject("result");
            }
        } catch (Exception failure) { throw new IllegalStateException("Debug command failed: " + command, failure); }
    }
}
