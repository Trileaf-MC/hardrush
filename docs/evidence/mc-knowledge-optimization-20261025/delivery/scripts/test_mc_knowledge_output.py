"""Whole-stdout budget, lossless continuation, evidence and compatibility tests."""
import contextlib
from copy import deepcopy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shlex
import tempfile
import unittest
import zipfile
from unittest.mock import patch

from mc_knowledge_output import OutputBudgetTooSmall, budget_error, render_output

spec = importlib.util.spec_from_file_location("mc_output_cli", Path(__file__).with_name("mc-knowledge.py"))
mc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mc)


def unpack(rendered, compact):
    if not compact:
        return json.loads(rendered)
    header, body = rendered.split("\n", 1)
    value = json.loads(header)
    count = value["metrics"]["body_chars"]
    assert body[count:] == "\n"
    value["text"] = body[:count]
    return value


class OutputTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "gradle.properties").write_text("minecraft_version=26.3\n")

    def args(self, *arguments):
        return mc.parse_args(["--project", str(self.root), "data", "fixture", *arguments])

    def response(self, text, args, **extra):
        return {"ok": True, "source": {"minecraft_version": "26.3", "edition": "Java", "kind": "supplied-jar",
                "jar": "/local/fixture.jar", "jar_sha256": "a" * 64, "integrity": "content-addressed"},
                "resource": "data/minecraft/fixture.json", "pointer": None, **mc.page_text(text, args), **extra}

    def test_default_json_is_unbounded_and_preserves_legacy_fields(self):
        args = self.args("--max-chars", "20000")
        original = self.response("中文\\\"\n" * 1800, args, unknown_new_metadata="v" * 8000)
        rendered = render_output(original, args, 1.25)
        result = unpack(rendered, False)
        self.assertGreater(len(rendered), 6000)
        for key, value in original.items():
            self.assertEqual(result[key], value)
        self.assertEqual(result["metrics"]["output_chars"], len(rendered))
        self.assertEqual(result["unknown_new_metadata"], "v" * 8000)
        self.assertNotIn("evidence", result)
        self.assertFalse(args.cache_dir.exists())

    def test_default_find_class_preserves_exact_legacy_structure_and_zero_body_metric(self):
        jar = self.root / "fixture.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("version.json", json.dumps({"id": "26.3", "java_version": 25}))
            archive.writestr("net/minecraft/example/Thing.class", b"fixture")
            archive.writestr("net/minecraft/other/Thing.class", b"fixture")
        args = mc.parse_args(["--project", str(self.root), "--jar", str(jar), "find-class", "Thing", "--limit", "1"])
        original = mc.execute(args)
        self.assertEqual(set(original), {"source", "matches", "total_matches", "truncated"})
        self.assertEqual(original["matches"], ["net.minecraft.example.Thing"])
        self.assertEqual(original["total_matches"], 2)
        self.assertTrue(original["truncated"])
        rendered = render_output({"ok": True, **original}, args, 0)
        result = json.loads(rendered)
        self.assertEqual(set(result), {"ok", "source", "matches", "total_matches", "truncated", "metrics"})
        self.assertEqual(result["metrics"]["body_chars"], 0)
        self.assertNotIn("text", result)
        self.assertFalse(args.cache_dir.exists())
        args.format = "compact"
        paged = mc.execute(args)
        self.assertEqual(json.loads(paged["text"]), "net.minecraft.example.Thing")
        self.assertTrue(paged["matches_limited"])
        self.assertIsNone(paged["next"])

    def test_format_environment_requires_explicit_selection_and_cli_wins(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(self.args().format, "json")
        with patch.dict(os.environ, {"MC_KNOWLEDGE_FORMAT": "compact"}):
            self.assertEqual(self.args().format, "compact")
            self.assertEqual(self.args("--format", "json").format, "json")
        with patch.dict(os.environ, {"MC_KNOWLEDGE_FORMAT": "typo"}):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                self.args()

    def test_compact_has_raw_body_and_default_entire_stdout_limit(self):
        args = self.args("--format", "compact", "--max-chars", "10000", "--max-lines", "10000")
        original = self.response('"raw\\escape"\n中文\u2028' * 1400, args)
        rendered = render_output(original, args, 0)
        value = unpack(rendered, True)
        self.assertLessEqual(len(rendered), 6000)
        self.assertEqual(value["format"], "compact")
        self.assertTrue(value["truncated"])
        self.assertEqual(value["source"]["side"], "requested server; supplied JAR side unverified")
        self.assertEqual(value["source"]["jar_id"], "sha256:" + "a" * 16)
        self.assertNotIn("jar_sha256", value["source"])
        self.assertEqual(value["source"]["edition"], "Java")
        self.assertIn('"raw\\escape"\n中文', rendered)
        self.assertEqual(value["metrics"]["output_chars"], len(rendered))

    def test_both_formats_round_trip_unicode_crlf_single_lines_and_json_escaping(self):
        original = 'α中"\\\t\x00' * 70 + '\r\nshort\u2028tail\r' + '🧪' * 60 + '\n'
        for compact in (False, True):
            with self.subTest(compact=compact):
                args = self.args("--format", "compact" if compact else "json", "--max-output-chars", "1600",
                                 "--max-chars", "75", "--max-lines", "2")
                pieces = []
                for _ in range(500):
                    rendered = render_output(self.response(original, args), args, 0)
                    self.assertLessEqual(len(rendered), 1600)
                    value = unpack(rendered, compact)
                    pieces.append(value["text"])
                    self.assertEqual(value["metrics"]["body_chars"], len(value["text"]))
                    self.assertEqual(value["metrics"]["output_chars"], len(rendered))
                    if not value["truncated"]:
                        break
                    self.assertTrue(value["text"])
                    self.assertNotEqual((args.start_line, args.start_char),
                                        (value["next"]["start_line"], value["next"]["start_char"]))
                    args.start_line, args.start_char = value["next"]["start_line"], value["next"]["start_char"]
                self.assertEqual("".join(pieces), original)

    def test_all_warnings_remain_verbatim_and_too_small_never_looks_successful(self):
        args = self.args("--format", "compact", "--max-output-chars", "900")
        warnings = ["警告" * 300, "second\nwarning"]
        response = self.response("long" * 300, args, warnings=[warnings[0]],
                                 decompiled={"warnings": [warnings[1]], "class": "Thing"})
        with self.assertRaises(OutputBudgetTooSmall) as caught:
            render_output(response, args, 0)
        self.assertEqual(caught.exception.warnings, warnings)
        self.assertGreater(caught.exception.minimum, 900)
        small, diagnostic = budget_error(caught.exception, args)
        self.assertLessEqual(len(small), 900)
        error = json.loads(small or diagnostic)
        self.assertFalse(error["ok"])
        self.assertEqual(error["error"]["code"], "OUTPUT_BUDGET_TOO_SMALL")
        self.assertEqual(error["warnings"], warnings)
        args.max_output_chars = caught.exception.minimum
        success = unpack(render_output(response, args, 0), True)
        self.assertEqual(success["warnings"], warnings)
        self.assertNotEqual(success["completeness"], "complete")

    def test_empty_local_body_keeps_legacy_range_identity(self):
        args = self.args()
        page = mc.page_text("", args)
        self.assertEqual(page, {"text": "", "total_lines": 1, "total_chars": 0,
                               "returned_range": {"start_line": 1, "start_char": 0,
                                                  "end_line": 2, "end_char": 0},
                               "truncated": False, "next": None})

    def test_budget_continuation_when_start_char_is_exactly_after_line_break(self):
        for newline in ("\n", "\r\n"):
            for compact in (False, True):
                with self.subTest(newline=newline, compact=compact):
                    args = self.args("--format", "compact" if compact else "json", "--max-output-chars", "1",
                                     "--start-char", str(1 + len(newline)), "--max-lines", "1")
                    text = "a" + newline + "bc" * 300 + newline + "d"
                    original = self.response(text, args)
                    self.assertEqual(original["returned_range"]["start_line"], 2)
                    self.assertEqual(original["returned_range"]["start_char"], 0)
                    self.assertTrue(original["text"].startswith("bc"))
                    with self.assertRaises(OutputBudgetTooSmall) as caught:
                        render_output(original, args, 0)
                    args.max_output_chars = caught.exception.minimum + 10
                    rendered = render_output(original, args, 0)
                    value = unpack(rendered, compact)
                    self.assertEqual(value["next"]["start_line"], 2)
                    args.start_line, args.start_char = value["next"]["start_line"], value["next"]["start_char"]
                    following = self.response(text, args)
                    self.assertEqual(value["text"] + following["text"], original["text"])

    def test_evidence_io_and_integrity_failures_remain_structured_with_warnings_and_budget(self):
        for error in (PermissionError("cache denied"), ValueError("evidence checksum mismatch")):
            for compact in (False, True):
                for budget in (1, 1200):
                    with self.subTest(error=error, compact=compact, budget=budget):
                        stdout, stderr = io.StringIO(), io.StringIO()
                        with patch.object(mc, "execute", return_value={"text": "fixture", "warnings": ["keep verbatim"]}), \
                                patch("mc_knowledge_output.store_evidence", side_effect=error), \
                                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                            code = mc.main(["--project", str(self.root), "data", "fixture", "--format",
                                            "compact" if compact else "json", "--max-output-chars", str(budget)])
                        self.assertEqual(code, 1)
                        self.assertLessEqual(len(stdout.getvalue()), budget)
                        value = unpack(stdout.getvalue() or stderr.getvalue(), compact)
                        self.assertFalse(value["ok"])
                        self.assertEqual(value["error"]["code"], "OUTPUT_FAILED")
                        self.assertEqual(value["warnings"], ["keep verbatim"])
                        self.assertNotIn("Traceback", stderr.getvalue())

    def test_minimum_budget_accounts_for_end_character_reset_at_newline(self):
        args = self.args("--format", "compact", "--max-output-chars", "1", "--max-chars", "10000",
                         "--start-char", "9998")
        text = "x" * 9999 + "\n" + "tail" * 200
        original = self.response(text, args)
        with self.assertRaises(OutputBudgetTooSmall) as caught:
            render_output(original, args, 0)
        args.max_output_chars = caught.exception.minimum
        rendered = render_output(original, args, 0)
        self.assertLessEqual(len(rendered), args.max_output_chars)
        value = unpack(rendered, True)
        self.assertTrue(value["text"])
        self.assertEqual(value["text"], original["text"][:len(value["text"])])

    def test_tiny_budget_uses_stderr_and_nonzero_exit_with_empty_stdout(self):
        output, errors = io.StringIO(), io.StringIO()
        with patch.object(mc, "execute", return_value={"text": "fixture", "warnings": ["keep"]}), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            code = mc.main(["--project", str(self.root), "data", "fixture", "--max-output-chars", "1"])
        self.assertEqual(code, 1)
        self.assertEqual(output.getvalue(), "")
        error = json.loads(errors.getvalue())
        self.assertEqual(error["error"]["code"], "OUTPUT_BUDGET_TOO_SMALL")
        self.assertGreater(error["error"]["minimum_required_chars"], 1)

    def test_evidence_is_content_addressed_and_full_provenance_expandable(self):
        args = self.args("--format", "compact", "--provenance", "full", "--max-output-chars", "15000")
        original = self.response("one\ntwo\n", args)
        value = unpack(render_output(original, args, 0), True)
        path = Path(value["evidence"]["path"])
        identity = hashlib.sha256(path.read_bytes()).hexdigest()
        self.assertEqual(path.stem, identity)
        self.assertEqual(value["evidence"]["id"], "sha256:" + identity[:16])
        record = json.loads(path.read_text())
        self.assertEqual(record["result"], original)
        self.assertEqual(value["provenance"]["source"], original["source"])
        self.assertEqual(unpack(render_output(original, args, 999), True)["evidence"], value["evidence"])
        # Files are never overwritten just because an identity already exists.
        path.chmod(0o644)
        path.write_text("tampered")
        with self.assertRaisesRegex(ValueError, "校验失败"):
            render_output(original, args, 0)

    def test_wiki_budget_cut_keeps_revision_section_identity_and_all_warnings(self):
        args = self.args("--format", "compact", "--max-output-chars", "1800", "--max-chars", "10000", "--max-lines", "10000")
        warnings = ["Java / Bedrock 条件保留", "revision is not Minecraft version"]
        original = self.response("Java only\nBedrock only\n" * 300, args, source={
            "kind": "minecraft-wiki", "page": "Example", "revision": 123, "section": "2",
            "edition_scope": "mixed; unfiltered", "url": "https://minecraft.wiki/w/Example?oldid=123"},
            warnings=warnings, requested_minecraft_version="26.3", returned_chars=9999)
        value = unpack(render_output(original, args, 0), True)
        self.assertEqual(value["returned_chars"], len(value["text"]))
        self.assertEqual(value["next"]["oldid"], 123)
        self.assertEqual(value["next"]["section"], "2")
        self.assertEqual(value["source"]["revision"], 123)
        self.assertEqual(value["warnings"], warnings)
        self.assertNotEqual(value["completeness"], "complete")

    def test_long_list_item_lossless_through_budget_pages_and_limit_status(self):
        for compact in (False, True):
            args = self.args("--format", "compact" if compact else "json", "--max-output-chars", "1500",
                             "--max-chars", "10000", "--max-lines", "10000")
            matches = ["net.minecraft." + '中"\\' * 300, "net.minecraft.Small"]
            text = "\n".join(json.dumps(item, ensure_ascii=False) for item in matches)
            collected = []
            for _ in range(100):
                response = self.response(text, args, matches=matches, total_matches=3,
                                         matches_limited=True)
                rendered = render_output(response, args, 0)
                self.assertLessEqual(len(rendered), 1500)
                value = unpack(rendered, compact)
                collected.append(value["text"])
                self.assertEqual(value["total_matches"], 3)
                self.assertTrue(value["matches_limited"])
                self.assertNotEqual(value["completeness"], "complete")
                if not value["next"]:
                    break
                args.start_line, args.start_char = value["next"]["start_line"], value["next"]["start_char"]
            self.assertEqual("".join(collected), text)
            self.assertEqual([json.loads(line) for line in text.splitlines()], matches)


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "gradle.properties").write_text("minecraft_version=26.3\n")

    def args(self, *tail):
        return mc.parse_args(["--project", str(self.root), "--version", "26.3", "--jar", "/tmp/a jar ' fixture.jar",
                              "--cache-dir", str(self.root / "cache"), "--gradle-home", str(self.root / "gradle"),
                              "source", "Thing", "--member", "act", *tail])

    def vanilla(self, args):
        vanilla = object.__new__(mc.Vanilla)
        vanilla.args = args
        vanilla.classes = ["net.minecraft.Thing", "net.minecraft.Parent", "net.minecraft.Grandparent"]
        return vanilla

    def test_all_parsers_reject_abbreviated_options(self):
        for command in (["--vers", "26.3", "doctor"], ["doctor", "--vers", "26.3"],
                        ["source", "Thing", "--desc", "()V"], ["wiki", "Thing", "--sec", "2"]):
            with self.subTest(command=command), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                mc.parse_args(command)

    def test_overload_recovery_is_executable_and_preserves_identity(self):
        args = self.args()
        vanilla = self.vanilla(args)
        members = [{"name": "act", "descriptor": descriptor, "kind": "method", "declaration": "public void act();"}
                   for descriptor in ["()V", "(I)V"]]
        with patch.object(vanilla, "members", return_value=members):
            with self.assertRaises(mc.KnowledgeError) as caught:
                vanilla.select_member("net.minecraft.Thing")
        self.assertEqual(caught.exception.code, "MEMBER_AMBIGUOUS")
        for command, member in zip(caught.exception.details["next_commands"], members):
            self.assertEqual(shlex.split(command["shell"]), command["argv"])
            recovered = mc.parse_args(command["argv"][2:])
            for flag in ("project", "version", "jar", "cache_dir", "gradle_home", "side"):
                self.assertEqual(getattr(recovered, flag), getattr(args, flag))
            self.assertEqual(recovered.descriptor, member["descriptor"])

    def test_inherited_hint_requires_explicit_declaring_class_and_does_not_replace_override(self):
        args = self.args("--descriptor", "(I)V")
        vanilla = self.vanilla(args)
        def bytecode(name):
            parent = {"net.minecraft.Thing": "net.minecraft.Parent", "net.minecraft.Parent": "net.minecraft.Grandparent"}.get(name)
            return "public class " + name + (" extends " + parent if parent else "") + " {\n}", False
        def member(name):
            if name == "net.minecraft.Thing":
                return [{"name": "act", "descriptor": "()V", "kind": "method", "declaration": "public void act();"}]
            return [{"name": "act", "descriptor": descriptor, "kind": "method", "declaration": "public void act();"}
                    for descriptor in ["()V", "(I)V"]]
        with patch.object(vanilla, "bytecode", side_effect=bytecode), patch.object(vanilla, "members", side_effect=member):
            with self.assertRaises(mc.KnowledgeError) as caught:
                vanilla.select_member("net.minecraft.Thing")
        self.assertEqual(caught.exception.code, "MEMBER_NOT_FOUND")
        inherited = caught.exception.details["inherited_candidates"]
        self.assertTrue(inherited)
        self.assertTrue(all(item["descriptor"] == "(I)V" for item in inherited))
        self.assertFalse(caught.exception.details["inheritance_search"]["automatic_selection"])
        self.assertEqual(inherited[0]["declaring_class"], "net.minecraft.Parent")
        self.assertEqual(len(inherited), 1, "nearest parent override hides the grandparent declaration")
        self.assertTrue(any(mc.parse_args(item["argv"][2:]).class_name == "net.minecraft.Parent"
                            for item in caught.exception.details["next_commands"]))
        args.descriptor = "()V"
        with patch.object(vanilla, "members", side_effect=member), patch.object(vanilla, "inherited_members") as inherited_search:
            self.assertEqual(vanilla.select_member("net.minecraft.Thing")["descriptor"], "()V")
            inherited_search.assert_not_called()

    def test_independent_interfaces_keep_clues_and_ancestry_limits_are_explicit(self):
        args = self.args()
        vanilla = self.vanilla(args)
        vanilla.classes = ["net.minecraft.Thing", "net.minecraft.Left", "net.minecraft.Right"]
        declaration = {"name": "act", "descriptor": "()V", "kind": "method", "declaration": "public void act();"}
        def bytecode(name):
            if name == "net.minecraft.Thing":
                return "public class net.minecraft.Thing implements net.minecraft.Left, net.minecraft.Right {", False
            return "public interface " + name + " {", False
        with patch.object(vanilla, "bytecode", side_effect=bytecode), patch.object(vanilla, "members", return_value=[declaration]):
            clues, warnings = vanilla.inherited_members("net.minecraft.Thing", [])
            self.assertEqual({item["declaring_class"] for item in clues}, {"net.minecraft.Left", "net.minecraft.Right"})
            self.assertEqual(warnings, [])
            limited, warnings = vanilla.inherited_members("net.minecraft.Thing", [], max_depth=0)
            self.assertEqual(limited, [])
            self.assertTrue(any("最大深度" in item for item in warnings))
            limited, warnings = vanilla.inherited_members("net.minecraft.Thing", [], max_classes=1)
            self.assertEqual(limited, [])
            self.assertTrue(any("最大类数" in item for item in warnings))

    def test_recovery_pins_project_default_version(self):
        args = self.args()
        args.version = None
        command = mc.next_command(args, class_name="net.minecraft.Thing", descriptor="()V")
        recovered = mc.parse_args(command["argv"][2:])
        self.assertEqual(recovered.version, "26.3")

    def test_class_ambiguity_offers_fully_qualified_commands(self):
        vanilla = self.vanilla(self.args())
        vanilla.classes.append("net.minecraft.other.Thing")
        with self.assertRaises(mc.KnowledgeError) as caught:
            vanilla.resolve("Thing")
        self.assertEqual(caught.exception.code, "CLASS_AMBIGUOUS")
        self.assertEqual([mc.parse_args(command["argv"][2:]).class_name
                          for command in caught.exception.details["next_commands"]],
                         ["net.minecraft.Thing", "net.minecraft.other.Thing"])


if __name__ == "__main__":
    unittest.main()
