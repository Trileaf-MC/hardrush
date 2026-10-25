"""Wiki contracts: bounded exact paging, revision identity, and offline behavior."""

import argparse
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError


spec = importlib.util.spec_from_file_location("mc_knowledge_wiki", Path(__file__).with_name("mc_knowledge_wiki.py"))
wiki = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wiki)


def args(**kwargs):
    return argparse.Namespace(**{"title": "Zombie", **kwargs})


def metadata(revision=123, title="Zombie", sections=None):
    return {"parse": {"title": title, "revid": revision, "sections": sections or [
        {"index": "1", "line": "Behavior"}, {"index": "2", "line": "Attacking"}]}}


def body(text, revision=123):
    return {"parse": {"title": "Zombie", "revid": revision, "wikitext": text}}


class WikiTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.cache = Path(self.temporary.name)

    def test_long_lines_and_unicode_round_trip_without_losing_characters(self):
        original = "甲乙丙丁\n" + "x" * 25 + "\nJava only\r\nBedrock only\nend"
        cursor = {"start_line": 1, "start_char": 0}
        pages = []
        for _ in range(100):
            page = wiki.slice_text(original, max_lines=2, max_chars=7, **cursor)
            self.assertLessEqual(page["returned_chars"], 7)
            pages.append(page["text"])
            cursor = page["next"]
            if cursor is None:
                break
        else:
            self.fail("pagination did not progress")
        self.assertEqual("".join(pages), original)

    def test_invalid_offsets_and_budgets_are_rejected(self):
        for values in ({"start_line": 0}, {"max_chars": 0}, {"start_char": -1},
                       {"start_line": 3}, {"start_char": 4}):
            with self.subTest(values=values), self.assertRaises(wiki.WikiError):
                wiki.slice_text("abc", **values)

    def test_section_fetch_is_pinned_to_directory_revision(self):
        calls = []

        def api(_base, parameters, _timeout):
            calls.append(parameters)
            if parameters["prop"] == "sections|revid":
                return metadata()
            self.assertEqual(parameters["oldid"], 123)
            self.assertNotIn("page", parameters)
            return body("{{Only|JE}}\nAttack damage = 3\n{{Only|BE}}\nDifferent rule")

        with patch.object(wiki, "_request_json", side_effect=api):
            result = wiki.wiki_query(args(section="Behavior", max_chars=15), self.cache)
        self.assertEqual(len(calls), 2)
        self.assertEqual(result["source"]["revision"], 123)
        self.assertEqual(result["source"]["section"], "1")
        self.assertEqual(result["next"]["oldid"], 123)
        self.assertEqual(result["next"]["section"], "1")
        self.assertIn("oldid=123", result["source"]["url"])
        self.assertLessEqual(len(result["text"]), 15)

    def test_explicit_revision_can_be_read_offline_after_online_read(self):
        with patch.object(wiki, "_request_json", side_effect=[metadata(), body("Java\nBedrock")]):
            wiki.wiki_query(args(), self.cache)
        with patch.object(wiki, "_request_json", side_effect=AssertionError("network attempted")):
            result = wiki.wiki_query(args(offline=True, oldid=123), self.cache)
        self.assertTrue(result["source"]["cache_hit"])
        self.assertEqual(result["text"], "Java\nBedrock")

    def test_offline_uncached_section_fails_instead_of_fetching(self):
        with patch.object(wiki, "_request_json", return_value=metadata()):
            wiki.wiki_query(args(toc=True), self.cache)
        with patch.object(wiki, "_request_json", side_effect=AssertionError("network attempted")):
            with self.assertRaises(wiki.WikiError) as error:
                wiki.wiki_query(args(offline=True, section="Behavior"), self.cache)
        self.assertEqual(error.exception.code, "WIKI_CACHE_MISS")

    def test_network_failure_does_not_silently_serve_stale_cache(self):
        with patch.object(wiki, "_request_json", side_effect=[metadata(), body("cached")]):
            wiki.wiki_query(args(), self.cache)
        with patch.object(wiki, "_request_json", side_effect=wiki.WikiError("WIKI_NETWORK_ERROR", "offline")):
            with self.assertRaises(wiki.WikiError) as error:
                wiki.wiki_query(args(refresh=True), self.cache)
        self.assertEqual(error.exception.code, "WIKI_NETWORK_ERROR")

    def test_revision_mismatch_never_caches_wrong_body(self):
        with patch.object(wiki, "_request_json", side_effect=[metadata(), body("wrong", revision=124)]):
            with self.assertRaises(wiki.WikiError) as error:
                wiki.wiki_query(args(), self.cache)
        self.assertEqual(error.exception.code, "WIKI_REVISION_MISMATCH")
        self.assertEqual(list(self.cache.rglob("section-*.json")), [])

    def test_explicit_oldid_does_not_accept_another_page(self):
        with patch.object(wiki, "_request_json", return_value=metadata(title="Creeper")):
            with self.assertRaises(wiki.WikiError) as error:
                wiki.wiki_query(args(oldid=123), self.cache)
        self.assertEqual(error.exception.code, "WIKI_REVISION_MISMATCH")

    def test_duplicate_section_names_require_index(self):
        directory = metadata(sections=[{"index": "1", "line": "Behavior"},
                                       {"index": "2", "line": "Behavior"}])
        with patch.object(wiki, "_request_json", return_value=directory):
            with self.assertRaises(wiki.WikiError) as error:
                wiki.wiki_query(args(section="Behavior"), self.cache)
        self.assertEqual(error.exception.code, "WIKI_SECTION_AMBIGUOUS")
        with patch.object(wiki, "_request_json", return_value=body("chosen second")):
            result = wiki.wiki_query(args(section="2"), self.cache)
        self.assertEqual(result["text"], "chosen second")

    def test_refresh_discovers_new_revision_but_old_revision_stays_readable(self):
        with patch.object(wiki, "_request_json", side_effect=[metadata(), body("old")]):
            wiki.wiki_query(args(), self.cache)
        with patch.object(wiki, "_request_json", side_effect=[metadata(124), body("new", 124)]):
            result = wiki.wiki_query(args(refresh=True), self.cache)
        self.assertEqual(result["source"]["revision"], 124)
        with patch.object(wiki, "_request_json", side_effect=AssertionError("network attempted")):
            previous = wiki.wiki_query(args(oldid=123, offline=True), self.cache)
        self.assertEqual(previous["text"], "old")

    def test_search_candidates_are_bounded_and_cacheable(self):
        response = {"query": {"search": [{"title": f"Mob {i}", "snippet": "<span>Java</span> only"}
                                         for i in range(8)], "searchinfo": {"totalhits": 8}}}
        with patch.object(wiki, "_request_json", return_value=response) as api:
            result = wiki.wiki_query(args(search=True, limit=3), self.cache)
        self.assertEqual(api.call_args.args[1]["srlimit"], 3)
        self.assertEqual(result["source"]["result_count"], 3)
        self.assertNotIn("Mob 3", result["text"])
        self.assertIn("Java only", result["text"])
        with patch.object(wiki, "_request_json", side_effect=AssertionError("network attempted")):
            cached = wiki.wiki_query(args(search=True, limit=3, offline=True), self.cache)
        self.assertEqual(cached["text"], result["text"])

    def test_api_transport_reports_http_and_json_errors(self):
        cases = [(HTTPError("https://minecraft.wiki/api.php", 429, "Rate limit", {}, None), "WIKI_HTTP_ERROR"),
                 (URLError("network unreachable"), "WIKI_NETWORK_ERROR")]
        for cause, code in cases:
            with self.subTest(code=code), patch.object(wiki, "urlopen", side_effect=cause):
                with self.assertRaises(wiki.WikiError) as error:
                    wiki._request_json("https://minecraft.wiki", {"action": "parse"}, 2)
                self.assertEqual(error.exception.code, code)
        with patch.object(wiki, "urlopen", return_value=io.BytesIO(b"<html>blocked</html>")):
            with self.assertRaises(wiki.WikiError) as error:
                wiki._request_json("https://minecraft.wiki", {"action": "parse"}, 2)
        self.assertEqual(error.exception.code, "WIKI_RESPONSE_INVALID")

    def test_api_response_size_is_bounded(self):
        with patch.object(wiki, "MAX_RESPONSE_BYTES", 20), patch.object(wiki, "urlopen", return_value=io.BytesIO(b"x" * 21)):
            with self.assertRaises(wiki.WikiError) as error:
                wiki._request_json("https://minecraft.wiki", {}, 2)
        self.assertEqual(error.exception.code, "WIKI_RESPONSE_TOO_LARGE")

    def test_invalid_modes_do_not_access_network(self):
        for arguments in ({"offline": True, "refresh": True}, {"search": True, "oldid": 123},
                          {"toc": True, "section": "1"}, {"lang": "../evil"}):
            with self.subTest(arguments=arguments), patch.object(wiki, "_request_json", side_effect=AssertionError("network attempted")):
                with self.assertRaises(wiki.WikiError):
                    wiki.wiki_query(args(**arguments), self.cache)


if __name__ == "__main__":
    unittest.main()
