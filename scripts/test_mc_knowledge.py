"""Knowledge CLI guards: artifact identity, ambiguous symbols, bounded evidence."""
import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("mc_knowledge", Path(__file__).with_name("mc-knowledge.py"))
mc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mc)


def make_jar(path, version="26.3", extra=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("version.json", json.dumps({"id": version, "java_version": 25}))
        archive.writestr("net/minecraft/example/Thing.class", b"fixture")
        for name, value in (extra or {}).items():
            archive.writestr(name, value)


class KnowledgeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        (self.root / "gradle.properties").write_text("minecraft_version=26.3\n")
        self.gradle = self.root / "gradle"
        self.jar = self.gradle / "caches/fabric-loom/26.3/minecraft-extracted_server.jar"
        make_jar(self.jar, extra={"data/minecraft/damage_type/mob_attack.json": '{"scaling":"when_caused_by_living_non_player"}'})

    def args(self, *arguments):
        return mc.parse_args(["--project", str(self.root), "--gradle-home", str(self.gradle), *arguments])

    def test_project_version_wins_over_other_cached_versions(self):
        make_jar(self.gradle / "caches/fabric-loom/99.9/minecraft-extracted_server.jar", "99.9")
        vanilla = mc.Vanilla(self.args("doctor"))
        self.assertEqual(vanilla.version, "26.3")
        self.assertEqual(vanilla.jar, self.jar)

    def test_jar_identity_is_checked_instead_of_trusting_directory(self):
        make_jar(self.jar, "26.2")
        with self.assertRaises(mc.KnowledgeError) as caught:
            mc.Vanilla(self.args("doctor"))
        self.assertEqual(caught.exception.code, "VERSION_MISMATCH")

    def test_explicit_version_does_not_fall_back_to_available_other_version(self):
        with self.assertRaises(mc.KnowledgeError) as caught:
            mc.Vanilla(self.args("doctor", "--version", "26.2"))
        self.assertEqual(caught.exception.code, "JAR_MISSING")

    def test_byte_change_at_same_version_invalidates_cache_bucket(self):
        first = mc.Vanilla(self.args("doctor"))
        make_jar(self.jar, extra={"changed.json": "{}"})
        second = mc.Vanilla(self.args("doctor"))
        self.assertNotEqual(first.bucket, second.bucket)

    def test_bundle_checksum_mismatch_is_rejected(self):
        with zipfile.ZipFile(self.jar.with_name("minecraft-server.jar"), "w") as archive:
            archive.writestr("META-INF/versions.list", "0" * 64 + "\t26.3\t26.3/server.jar\n")
        with self.assertRaises(mc.KnowledgeError) as caught:
            mc.Vanilla(self.args("doctor"))
        self.assertEqual(caught.exception.code, "JAR_CHECKSUM_MISMATCH")

    def test_missing_extracted_server_is_recovered_only_in_tool_cache(self):
        content = self.jar.read_bytes()
        checksum = mc.digest(self.jar)
        self.jar.unlink()
        with zipfile.ZipFile(self.jar.with_name("minecraft-server.jar"), "w") as archive:
            archive.writestr("META-INF/versions.list", checksum + "\t26.3\t26.3/server.jar\n")
            archive.writestr("META-INF/versions/26.3/server.jar", content)
        vanilla = mc.Vanilla(self.args("doctor"))
        self.assertEqual(vanilla.sha256, checksum)
        self.assertFalse(self.jar.exists())
        self.assertTrue(vanilla.jar.is_relative_to(self.root / ".local"))

    def test_ambiguous_class_requires_fully_qualified_name(self):
        make_jar(self.jar, extra={"net/minecraft/other/Thing.class": b"fixture"})
        vanilla = mc.Vanilla(self.args("doctor"))
        with self.assertRaises(mc.KnowledgeError) as caught:
            vanilla.resolve("Thing")
        self.assertEqual(caught.exception.code, "CLASS_AMBIGUOUS")
        self.assertEqual(vanilla.resolve("net.minecraft.other.Thing"), "net.minecraft.other.Thing")

    def test_overloaded_method_requires_descriptor(self):
        text = """public class net.minecraft.example.Thing {
  public int act(int);
    descriptor: (I)I
  public int act(java.lang.String);
    descriptor: (Ljava/lang/String;)I
}
"""
        vanilla = mc.Vanilla(self.args("source", "Thing", "--member", "act"))
        with patch.object(vanilla, "bytecode", return_value=(text, False)):
            with self.assertRaises(mc.KnowledgeError) as caught:
                vanilla.select_member("net.minecraft.example.Thing")
            self.assertEqual(caught.exception.code, "MEMBER_AMBIGUOUS")
            vanilla.args.descriptor = "(I)I"
            self.assertEqual(vanilla.select_member("net.minecraft.example.Thing")["descriptor"], "(I)I")

    def test_initializers_use_bytecode_to_preserve_field_initialization(self):
        outline = """public class net.minecraft.example.Thing {
  public net.minecraft.example.Thing();
    descriptor: ()V
  static {};
    descriptor: ()V
}
"""
        bytecode = """public class net.minecraft.example.Thing {
  public net.minecraft.example.Thing();
    descriptor: ()V
    Code:
       0: aload_0
       1: bipush 60
       3: putfield #7 // Field ticks:I
  static {};
    descriptor: ()V
    Code:
       0: bipush 30
       2: putstatic #9 // Field LIMIT:I
}
"""
        def read(_self, _class, code=False):
            return (bytecode if code else outline), False
        with patch.object(mc.Vanilla, "bytecode", read):
            constructor = mc.execute(self.args("source", "Thing", "--member", "<init>"))
            static = mc.execute(self.args("source", "Thing", "--member", "<clinit>"))
        self.assertEqual(constructor["representation"], "javap bytecode")
        self.assertIn("putfield", constructor["text"])
        self.assertIn("putstatic", static["text"])
        self.assertNotIn("putfield", static["text"])

    def test_full_source_cached_for_other_jar_is_not_searched(self):
        first = mc.Vanilla(self.args("doctor"))
        folder = first.bucket / "sources/fake"
        folder.mkdir(parents=True)
        (folder / "source.java").write_text("ATTACK_DAMAGE = 999;\n")
        mc.write_json(folder / "source.json", {"class": "net.minecraft.example.Thing", "source_sha256": mc.digest(folder / "source.java")})
        make_jar(self.jar, extra={"new.json": "{}"})
        result = mc.execute(self.args("search", "ATTACK_DAMAGE"))
        self.assertEqual(result["total_matches"], 0)
        self.assertEqual(result["cached_classes"], 0)
        self.assertIn("cached", result["coverage"])

    def test_corrupt_cached_source_is_excluded_from_search(self):
        vanilla = mc.Vanilla(self.args("doctor"))
        folder = vanilla.bucket / "sources/fake"
        folder.mkdir(parents=True)
        (folder / "source.java").write_text("wrong evidence")
        mc.write_json(folder / "source.json", {"class": "net.minecraft.example.Thing", "source_sha256": "wrong"})
        self.assertEqual(mc.execute(self.args("search", "wrong"))["total_matches"], 0)

    def test_data_lookup_with_json_pointer(self):
        result = mc.execute(self.args("data", "damage_type/mob_attack", "--pointer", "/scaling"))
        self.assertEqual(json.loads(result["text"]), "when_caused_by_living_non_player")
        self.assertEqual(result["source"]["minecraft_version"], "26.3")

    def test_pointer_arrays_and_escaped_keys(self):
        value = {"a/b": {"~": ["zero", "one"]}}
        self.assertEqual(mc.pointer_value(value, "/a~1b/~0/1"), "one")
        with self.assertRaises(mc.KnowledgeError):
            mc.pointer_value(value, "/a~1b/~0/-1")

    def test_pagination_round_trip_for_long_lines_and_unicode(self):
        text = "αβγ" * 37 + "\nshort\u2028separator\n" + "中" * 14 + "\n"
        args = self.args("source", "Thing", "--max-chars", "13", "--max-lines", "2")
        pieces = []
        for _ in range(100):
            result = mc.page_text(text, args)
            self.assertLessEqual(len(result["text"]), 13)
            pieces.append(result["text"])
            if not result["truncated"]:
                break
            args.start_line = result["next"]["start_line"]
            args.start_char = result["next"]["start_char"]
        self.assertEqual("".join(pieces), text)

    def test_common_flags_work_before_and_after_subcommand(self):
        before = self.args("--max-chars", "100", "source", "Thing")
        after = self.args("source", "Thing", "--max-chars", "100")
        self.assertEqual(before.max_chars, after.max_chars)
        self.assertEqual(before.project, self.root)

    def test_out_of_range_does_not_look_like_empty_success(self):
        with self.assertRaises(mc.KnowledgeError):
            mc.page_text("hello", self.args("source", "Thing", "--start-line", "2"))


if __name__ == "__main__":
    unittest.main()
