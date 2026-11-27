package dev.hardrush.lobby.pack.imports;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.lobby.plan.Binding;
import dev.hardrush.core.lobby.plan.BuildManifest;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.lobby.plan.MarkerSpec;
import dev.hardrush.lobby.pack.PackException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildManifestJsonTest {
    @TempDir Path dir;

    @BeforeAll
    static void bootstrap() {
        // Flood materials are checked against the block registry.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** The design's example, every binding form once. */
    static final String EXAMPLE = """
            {
              "format": 1,
              "origin": [4, 2, 7],
              "update_shapes": true,
              "future_field": {"ignored": true},
              "markers": [
                {"function": "assembly", "as": "zone", "shape": "flood", "at": [20, 2, 30], "materials": ["minecraft:lime_wool"]},
                {"function": "courtyard", "as": "zone", "shape": "box", "at": [40, 3, 12], "box": [5, 4, 5]},
                {"function": "landing_central", "as": "point", "at": [24, 3, 24], "yaw": 180},
                {"function": "role_zombie", "as": "model", "at": [30, 3, 10], "yaw": 90, "note": "ignored"},
                {"function": "rules", "as": "click", "at": [22, 3, 20]},
                {"function": "ominous_vault", "as": "block", "at": [35, 3, 8]},
                {"function": "status", "as": "text", "at": [23, 4, 19]},
                {"function": "altar_pad", "as": "zone", "shape": "flood", "at": [1, 2, 3]}
              ],
              "signs": [
                {"at": [31, 4, 10], "front": ["右键模型", "选择僵尸"], "back": []},
                {"at": [32, 4, 10], "front": ["只写正面"]}
              ]
            }
            """;

    static BuildManifest parse(String json) throws PackException {
        return BuildManifestJson.parse(json.getBytes(StandardCharsets.UTF_8), "camp.hardrush.json");
    }

    static String refused(String json) {
        return assertThrows(PackException.class, () -> parse(json), json).getMessage();
    }

    /** A manifest with one marker entry. */
    static String marker(String entry) { return "{\"format\": 1, \"markers\": [{\"function\": \"rules\", \"as\": \"click\", \"at\": [0, 0, 0]}, " + entry + "]}"; }
    static String sign(String entry) { return "{\"format\": 1, \"signs\": [" + entry + "]}"; }

    @Test void theDesignExampleParses() throws PackException {
        var m = parse(EXAMPLE);
        assertEquals(new Cell(4, 2, 7), m.origin());
        assertTrue(m.updateShapes());
        assertEquals(List.of(
                new BuildManifest.Marker(MarkerSpec.flood(LobbyFunction.ASSEMBLY, Set.of("minecraft:lime_wool")), new Cell(20, 2, 30), 0),
                new BuildManifest.Marker(MarkerSpec.box(LobbyFunction.COURTYARD, 5, 4, 5), new Cell(40, 3, 12), 0),
                new BuildManifest.Marker(MarkerSpec.simple(LobbyFunction.LANDING_CENTRAL, Binding.POINT), new Cell(24, 3, 24), 180),
                new BuildManifest.Marker(MarkerSpec.simple(LobbyFunction.ROLE_ZOMBIE, Binding.MODEL), new Cell(30, 3, 10), 90),
                new BuildManifest.Marker(MarkerSpec.simple(LobbyFunction.RULES, Binding.CLICK), new Cell(22, 3, 20), 0),
                new BuildManifest.Marker(MarkerSpec.simple(LobbyFunction.OMINOUS_VAULT, Binding.BLOCK), new Cell(35, 3, 8), 0),
                new BuildManifest.Marker(MarkerSpec.simple(LobbyFunction.STATUS, Binding.TEXT), new Cell(23, 4, 19), 0),
                new BuildManifest.Marker(MarkerSpec.flood(LobbyFunction.ALTAR_PAD, Set.of()), new Cell(1, 2, 3), 0)), m.markers());
        assertEquals(List.of(new BuildManifest.Sign(new Cell(31, 4, 10), List.of("右键模型", "选择僵尸"), List.of()),
                new BuildManifest.Sign(new Cell(32, 4, 10), List.of("只写正面"), null)), m.signs());
    }

    /** The gametest's sample: the building converts as described and its manifest stands over it. */
    @Test void theSampleManifestFitsTheSampleBuilding() throws IOException, PackException {
        var sample = SampleFiles.manifestBuilding();
        var result = StructureImports.read(SampleFiles.write(dir, "camp.schem", sample.tag()), StructureImportsTest.LIMITS);
        assertEquals(List.of(SampleFiles.MANIFEST_W, SampleFiles.MANIFEST_H, SampleFiles.MANIFEST_L), List.of(result.sizeX(), result.sizeY(), result.sizeZ()));
        var states = StructureImportsTest.states(result.tiles(), new Cell(0, 0, 0));
        assertEquals(sample.expected().states().size(), states.size());
        sample.expected().states().forEach((c, s) -> assertEquals(s, states.get(c), c.toString()));
        assertEquals(sample.expected().blockEntities(), StructureImportsTest.blockEntities(result.tiles(), new Cell(0, 0, 0)));
        var manifest = parse(SampleFiles.MANIFEST);
        assertEquals(SampleFiles.MANIFEST_ORIGIN, manifest.origin());
        assertEquals(7, manifest.markers().size());
        assertEquals(java.util.Optional.empty(), manifest.horizontalProblem(result.sizeX(), result.sizeZ()));
    }

    @Test void defaults() throws PackException {
        var m = parse("﻿{\"format\": 1.0}");
        assertEquals(new Cell(0, 0, 0), m.origin());
        assertFalse(m.updateShapes(), "Shapes stay as the author made them unless asked");
        assertTrue(m.markers().isEmpty() && m.signs().isEmpty());
    }

    @Test void theFileBesideTheBuildingIsFound() throws IOException, PackException {
        var building = dir.resolve("camp.schem");
        Files.writeString(building, "not read here");
        assertEquals(dir.resolve("camp.hardrush.json"), BuildManifestJson.besides(building));
        assertTrue(BuildManifestJson.readBesides(building).isEmpty(), "No manifest, no change");
        Files.writeString(dir.resolve("camp.hardrush.json"), EXAMPLE);
        assertEquals(parse(EXAMPLE), BuildManifestJson.readBesides(building).orElseThrow());

        Files.write(dir.resolve("camp.hardrush.json"), new byte[(int) BuildManifest.MAX_FILE_BYTES + 1]);
        assertTrue(assertThrows(PackException.class, () -> BuildManifestJson.readBesides(building)).getMessage().contains("太大"));
        Files.delete(dir.resolve("camp.hardrush.json"));
        Files.createDirectory(dir.resolve("camp.hardrush.json"));
        assertTrue(assertThrows(PackException.class, () -> BuildManifestJson.readBesides(building)).getMessage().contains("不是普通文件"));
    }

    @Test void fileLevelRefusals() {
        assertTrue(assertThrows(PackException.class, () -> BuildManifestJson.parse(new byte[] {'{', (byte) 0xC3, '}'}, "x"))
                .getMessage().contains("UTF-8"));
        assertTrue(refused("{\"format\": 1,").contains("不是合法的 JSON"));
        assertTrue(refused("[1]").contains("顶层应当是 JSON 对象"));
        assertTrue(refused("{}").contains("缺少 format"));
        assertTrue(refused("{\"format\": 2}").contains("不认识的 format 2"));
        assertTrue(refused("{\"format\": \"1\"}").contains("format 应当是整数"));
        assertTrue(refused("{\"format\": 1, \"origin\": [1, 2]}").contains("origin 应当是 [x, y, z]"));
        assertTrue(refused("{\"format\": 1, \"origin\": [1, 2, 3.5]}").contains("origin 应当是整数"));
        assertTrue(refused("{\"format\": 1, \"update_shapes\": 1}").contains("update_shapes 应当是 true 或 false"));
        assertTrue(refused("{\"format\": 1, \"markers\": {}}").contains("markers 应当是数组"));
        var many = new StringBuilder("{\"format\": 1, \"markers\": [");
        for (int i = 0; i <= BuildManifest.MAX_MARKERS; i++) many.append(i == 0 ? "" : ",").append("{\"function\": \"rules\", \"as\": \"click\", \"at\": [0, 0, 0]}");
        assertTrue(refused(many + "]}").contains("最多 512"));
        var signs = new StringBuilder("{\"format\": 1, \"signs\": [");
        for (int i = 0; i <= BuildManifest.MAX_SIGNS; i++) signs.append(i == 0 ? "" : ",").append("{\"at\": [0, 0, 0]}");
        assertTrue(refused(signs + "]}").contains("最多 256"));
    }

    @Test void markerRefusalsNameTheEntryAndItsFunction() {
        // Each refusal: the second entry, with what is wrong.
        var cases = List.of(
                List.of("1", "应当是 JSON 对象"),
                List.of("{\"as\": \"click\", \"at\": [0, 0, 0]}", "缺少 function"),
                List.of("{\"function\": \"lectern\", \"as\": \"click\", \"at\": [0, 0, 0]}", "未知功能 lectern"),
                List.of("{\"function\": \"rules\", \"at\": [0, 0, 0]}", "缺少 as"),
                List.of("{\"function\": \"rules\", \"as\": \"button\", \"at\": [0, 0, 0]}", "未知的 as：button"),
                List.of("{\"function\": \"landing_central\", \"as\": \"zone\", \"shape\": \"box\", \"box\": [1, 1, 1], \"at\": [0, 0, 0]}", "不能绑定为区域"),
                List.of("{\"function\": \"rules\", \"as\": \"click\"}", "缺少 at"),
                List.of("{\"function\": \"rules\", \"as\": \"click\", \"at\": [0, 0]}", "at 应当是 [x, y, z]"),
                List.of("{\"function\": \"rules\", \"as\": \"click\", \"at\": [0, 0.5, 0]}", "at 应当是整数"),
                List.of("{\"function\": \"rules\", \"as\": \"click\", \"at\": [0, \"0\", 0]}", "at 应当是整数"),
                List.of("{\"function\": \"rules\", \"as\": \"click\", \"at\": [0, 3000000000, 0]}", "at 应当是整数"),
                List.of("{\"function\": \"rules\", \"as\": \"click\", \"at\": [0, 0, 0], \"yaw\": \"north\"}", "yaw 应当是数字"),
                List.of("{\"function\": \"rules\", \"as\": \"click\", \"at\": [0, 0, 0], \"shape\": \"flood\"}", "只有区域（zone）标记才能带"),
                List.of("{\"function\": \"rules\", \"as\": \"click\", \"at\": [0, 0, 0], \"materials\": []}", "只有区域（zone）标记才能带"),
                List.of("{\"function\": \"assembly\", \"as\": \"zone\", \"at\": [0, 0, 0]}", "区域标记需要 shape"),
                List.of("{\"function\": \"assembly\", \"as\": \"zone\", \"shape\": \"ring\", \"at\": [0, 0, 0]}", "未知的 shape：ring"),
                List.of("{\"function\": \"assembly\", \"as\": \"zone\", \"shape\": \"flood\", \"box\": [1, 1, 1], \"at\": [0, 0, 0]}", "连片区域（flood）不能带 box"),
                List.of("{\"function\": \"assembly\", \"as\": \"zone\", \"shape\": \"flood\", \"materials\": \"minecraft:stone\", \"at\": [0, 0, 0]}", "materials 应当是数组"),
                List.of("{\"function\": \"assembly\", \"as\": \"zone\", \"shape\": \"flood\", \"materials\": [\"Lime_Wool\"], \"at\": [0, 0, 0]}", "不是方块 id"),
                List.of("{\"function\": \"assembly\", \"as\": \"zone\", \"shape\": \"flood\", \"materials\": [\"minecraft:lime_wol\"], \"at\": [0, 0, 0]}", "不是已知的方块"),
                List.of("{\"function\": \"assembly\", \"as\": \"zone\", \"shape\": \"box\", \"materials\": [], \"box\": [1, 1, 1], \"at\": [0, 0, 0]}", "方框区域（box）不能带 materials"),
                List.of("{\"function\": \"assembly\", \"as\": \"zone\", \"shape\": \"box\", \"at\": [0, 0, 0]}", "方框区域需要 box"),
                List.of("{\"function\": \"assembly\", \"as\": \"zone\", \"shape\": \"box\", \"box\": [1, 1], \"at\": [0, 0, 0]}", "box 应当是 [宽, 高, 深]"),
                List.of("{\"function\": \"assembly\", \"as\": \"zone\", \"shape\": \"box\", \"box\": [1, 65, 1], \"at\": [0, 0, 0]}", "0–64"),
                List.of("{\"function\": \"assembly\", \"as\": \"zone\", \"shape\": \"box\", \"box\": [1, -1, 1], \"at\": [0, 0, 0]}", "0–64"));
        for (var c : cases) {
            var message = refused(marker(c.get(0)));
            assertTrue(message.startsWith("建筑清单 camp.hardrush.json 有误：第 2 条标记"), message);
            assertTrue(message.contains(c.get(1)), c.get(0) + " → " + message);
        }
        assertTrue(refused(marker("{\"function\": \"landing_central\", \"as\": \"click\", \"at\": [0, 0, 0]}"))
                .contains("第 2 条标记（【中央落点】）：不能绑定为点击（click），可用：点（point）"));
    }

    @Test void signRefusals() {
        var cases = List.of(
                List.of("[]", "应当是 JSON 对象"),
                List.of("{\"front\": []}", "缺少 at"),
                List.of("{\"at\": [0, 0]}", "at 应当是 [x, y, z]"),
                List.of("{\"at\": [0, 0, 0], \"front\": \"hello\"}", "front 应当是数组"),
                List.of("{\"at\": [0, 0, 0], \"front\": [\"1\", \"2\", \"3\", \"4\", \"5\"]}", "front 有 5 项，最多 4 项"),
                List.of("{\"at\": [0, 0, 0], \"back\": [1]}", "back 的每一行 应当是字符串"),
                List.of("{\"at\": [0, 0, 0], \"front\": [\"" + "x".repeat(65) + "\"]}", "front 第 1 行超过 64 个字符"));
        for (var c : cases) {
            var message = refused(sign(c.get(0)));
            assertTrue(message.startsWith("建筑清单 camp.hardrush.json 有误：第 1 条告示牌："), message);
            assertTrue(message.contains(c.get(1)), c.get(0) + " → " + message);
        }
    }
}
