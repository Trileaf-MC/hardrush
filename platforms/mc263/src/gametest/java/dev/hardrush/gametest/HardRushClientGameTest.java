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
        command("hardrushdev status"); // Fail early if the debug server is unavailable.
        test.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client,
                ServerAddress.parseString("127.0.0.1:25586"),
                new ServerData("HardRush debug", "127.0.0.1:25586", ServerData.Type.OTHER), false, null));
        test.waitFor(client -> client.player != null && client.level != null && client.getConnection() != null, 1200);
        test.waitForScreen(null);
        JsonArray evidence = new JsonArray();
        String originalSkin = test.computeOnClient(client -> client.player.getSkin().body().texturePath().toString());
        try {
            command("hardrushdev clear HRDebug");
            command("hardrushdebug actor clear");
            command("gamemode survival HRDebug");
            command("effect clear HRDebug");
            command("time set day");
            command("execute in minecraft:overworld run tp HRDebug 0.5 150 0.5 0 0");
            test.waitFor(client -> client.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD), 400);
            test.waitForScreen(null);
            test.waitTicks(40); // A saved Nether login must finish loading the Overworld fixture.
            command("hardrushdebug actor cleanup");
            command("fill -8 149 -8 8 149 8 minecraft:stone");
            command("fill -8 150 -8 8 158 8 minecraft:air");
            command("item replace entity HRDebug weapon.mainhand with minecraft:diamond_sword");
            command("item replace entity HRDebug armor.chest with minecraft:diamond_chestplate");
            command("experience set HRDebug 7 levels");
            test.waitTicks(30);
            for (String kind : new String[]{"zombie", "skeleton", "creeper"}) {
                command("tp HRDebug 0.5 150 0.5 0 0");
                var applied = command("hardrushdev appearance HRDebug " + kind);
                if (!applied.toString().contains("disguise=" + kind)) throw new AssertionError("Disguise rejected: " + applied);
                test.waitTicks(20);
                evidence.add(snapshot(test, kind + "-after-profile-refresh"));
                test.waitFor(client -> client.player != null && !client.player.isInvisible()
                    && client.getCameraEntity() == client.player && !hasSelfBody(client)
                    && client.player.getSkin().body().texturePath().toString().equals("minecraft:skins/a37f02d925dbe17722606a1d624d0b4ddeaf398d"), 1200);
                test.waitForScreen(null);
                test.runOnClient(client -> {
                    if (!client.player.getMainHandItem().is(net.minecraft.world.item.Items.DIAMOND_SWORD)
                        || !client.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE)
                        || client.player.experienceLevel != 7) throw new AssertionError("Skin refresh lost inventory or experience");
                });
                evidence.add(snapshot(test, kind + "-owner-steve-first-person"));
                screenshot(test, "hardrush-" + kind + "-owner-steve-first-person");
                for (CameraType perspective : new CameraType[]{CameraType.THIRD_PERSON_BACK, CameraType.THIRD_PERSON_FRONT, CameraType.FIRST_PERSON}) {
                    test.getInput().pressKey(options -> options.keyTogglePerspective);
                    test.waitFor(client -> client.options.getCameraType() == perspective);
                    evidence.add(snapshot(test, kind + "-" + perspective.name()));
                    screenshot(test, "hardrush-" + kind + "-owner-" + perspective.name().toLowerCase(java.util.Locale.ROOT));
                }

                command("hardrushdebug actor " + kind);
                test.waitFor(client -> visibleTarget(client, kind), 400);
                var aim = test.computeOnClient(client -> {
                    net.minecraft.world.entity.Entity target = null;
                    for (var entity : client.level.entitiesForRendering()) {
                        if (entity.getUUID().equals(java.util.UUID.fromString("989b216e-c951-4c81-b41c-7b19082677aa"))) target = entity;
                    }
                    if (target == null) throw new AssertionError("Observer target vanished before aiming");
                    var delta = target.getBoundingBox().getCenter().subtract(client.player.getEyePosition());
                    return new float[]{(float)(Math.atan2(delta.z, delta.x) * 180 / Math.PI - 90),
                        (float)(-Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)) * 180 / Math.PI)};
                });
                test.getInput().lookAt(aim[0], aim[1]);
                test.waitFor(client -> client.hitResult instanceof EntityHitResult hit
                    && hit.getEntity().getUUID().equals(java.util.UUID.fromString("989b216e-c951-4c81-b41c-7b19082677aa")), 400);
                evidence.add(snapshot(test, kind + "-observer-monster"));
                screenshot(test, "hardrush-" + kind + "-observer-monster");
                test.waitTicks(80); // Vanilla login/teleport spawn protection must expire.
                var healthBefore = command("data get entity HRVisualTarget Health");
                test.getInput().pressKey(options -> options.keyAttack);
                test.waitTicks(8);
                var healthAfter = command("data get entity HRVisualTarget Health");
                if (healthAfter.get("resultValue").getAsInt() >= healthBefore.get("resultValue").getAsInt()) {
                    throw new AssertionError("Visible monster attack did not reach its authoritative player");
                }
                JsonObject attack = snapshot(test, kind + "-authoritative-attack");
                attack.add("healthBefore", healthBefore); attack.add("healthAfter", healthAfter); evidence.add(attack);
                command("hardrushdebug actor clear");
                test.waitTicks(10);
            }
            var before = command("data get entity HRDebug Pos");
            test.getInput().holdKeyFor(options -> options.keyLeft, 10);
            var after = command("data get entity HRDebug Pos");
            if (before.equals(after)) throw new AssertionError("Native owner camera did not send movement");
            JsonObject movement = snapshot(test, "native-owner-movement");
            movement.add("serverPositionBefore", before); movement.add("serverPositionAfter", after); evidence.add(movement);
            command("hardrushdev world enter HRDebug nether");
            test.waitFor(client -> client.level.dimension().toString().contains("/nether"), 400);
            test.waitFor(client -> !client.player.isInvisible() && !hasSelfBody(client)
                && client.getCameraEntity() == client.player, 400);
            test.waitTicks(30);
            evidence.add(snapshot(test, "owner-after-dimension-change"));
            command("hardrushdev clear HRDebug");
            test.waitFor(client -> client.player.getSkin().body().texturePath().toString().equals(originalSkin)
                && !hasSelfBody(client) && !client.player.isInvisible(), 1200);
            test.waitForScreen(null);
            evidence.add(snapshot(test, "cleared-original-skin"));
            screenshot(test, "hardrush-original-skin-restored");
        } finally {
            try {
                Path out = Path.of(System.getProperty("hardrush.root"), ".local/research/observer-appearance/client-results.json");
                Files.createDirectories(out.getParent());
                Files.writeString(out, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(evidence));
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
            command("hardrushdebug actor clear");
            command("hardrushdev clear HRDebug");
            test.runOnClient(client -> client.disconnectFromWorld(net.minecraft.network.chat.Component.literal("GameTest complete")));
            test.setScreen(TitleScreen::new);
            test.waitForScreen(TitleScreen.class);
        }
    }

    private static boolean visibleTarget(net.minecraft.client.Minecraft client, String kind) {
        var entity = client.level.getEntity(java.util.UUID.fromString("989b216e-c951-4c81-b41c-7b19082677aa"));
        return entity != null && entity.getType() == switch (kind) {
            case "zombie" -> EntityTypes.ZOMBIE;
            case "skeleton" -> EntityTypes.SKELETON;
            default -> EntityTypes.CREEPER;
        };
    }

    private static void screenshot(ClientGameTestContext test, String name) {
        test.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(
            Path.of(System.getProperty("hardrush.root"), ".local/research/observer-appearance/screenshots")));
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
            result.addProperty("skinTexture", client.player.getSkin().body().texturePath().toString());
            result.addProperty("skinModel", client.player.getSkin().model().name());
            result.addProperty("hit", client.hitResult instanceof EntityHitResult hit ? hit.getEntity().getType().toString()
                : client.hitResult == null ? "null" : client.hitResult.getType().name());
            for (var entity : client.level.entitiesForRendering()) {
                if (entity.getUUID().equals(java.util.UUID.fromString("989b216e-c951-4c81-b41c-7b19082677aa"))
                        && entity instanceof LivingEntity living) {
                    result.addProperty("visibleMonsterHealth", living.getHealth());
                    result.addProperty("visibleMonsterId", entity.getId());
                    result.addProperty("visibleMonsterType", entity.getType().toString());
                    result.addProperty("visibleMonsterWeapon", living.getMainHandItem().getItem().toString());
                    result.addProperty("visibleMonsterHelmet", living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).getItem().toString());
                }
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
                        && !((command.equals("effect clear HRDebug") && response.body().contains("Target has no effects to remove"))
                            || (command.startsWith("fill ") && response.body().contains("No blocks were filled")))) {
                    throw new IllegalStateException("Command failed: " + response.body());
                }
                return result.getAsJsonObject("result");
            }
        } catch (Exception failure) { throw new IllegalStateException("Debug command failed: " + command, failure); }
    }
}
