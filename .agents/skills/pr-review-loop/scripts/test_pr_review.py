"""PR review loop guards: what counts as reviewed, what may merge, and what leaves the machine."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("pr_review", Path(__file__).with_name("pr_review.py"))
pr_review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pr_review)

OLD, NEW = 'a' * 40, 'b' * 40


def marker(number, head):
    return f'<!-- codex-review round={number} head={head} -->\n## Codex 评审 · 第 {number} 轮'


def pull_request(head=NEW, draft=False):
    return {'headRefOid': head, 'isDraft': draft}


class ReviewRounds(unittest.TestCase):
    def test_only_marked_comments_count_and_rounds_are_ordered(self):
        bodies = [marker(2, NEW), '普通评论，提到了 codex-review', marker(1, OLD)]
        self.assertEqual(pr_review.review_rounds(bodies), [(1, OLD), (2, NEW)])

    def test_review_stops_after_the_round_limit_unless_the_user_asked(self):
        rounds = [(number, OLD) for number in range(1, pr_review.MAX_ROUNDS + 1)]
        self.assertIsNone(pr_review.review_blocker(rounds[:-1], extra_round=False))
        self.assertIn('停下来', pr_review.review_blocker(rounds, extra_round=False))
        self.assertIsNone(pr_review.review_blocker(rounds, extra_round=True))


class MergeGate(unittest.TestCase):
    def test_reviewed_head_may_merge(self):
        self.assertIsNone(pr_review.merge_blocker(pull_request(), [(1, OLD), (2, NEW)], NEW, False))

    def test_unreviewed_pr_is_blocked(self):
        self.assertIn('还没有 Codex 评审', pr_review.merge_blocker(pull_request(), [], NEW, False))
        self.assertIn('还没有 Codex 评审', pr_review.merge_blocker(pull_request(), [], NEW, True))

    def test_commits_after_the_last_review_need_an_explicit_decision(self):
        self.assertIn('再评审一轮', pr_review.merge_blocker(pull_request(), [(1, OLD)], NEW, False))
        self.assertIsNone(pr_review.merge_blocker(pull_request(), [(1, OLD)], NEW, True))

    def test_unpushed_commits_and_drafts_are_blocked(self):
        self.assertIn('先 push', pr_review.merge_blocker(pull_request(head=OLD), [(1, OLD)], NEW, True))
        self.assertIn('草稿', pr_review.merge_blocker(pull_request(draft=True), [(1, NEW)], NEW, False))

    def test_anything_but_pass_or_skip_fails_the_checks(self):
        checks = [{'name': 'Unit tests', 'bucket': 'pass'}, {'name': 'Optional', 'bucket': 'skipping'},
                  {'name': 'Assemble', 'bucket': 'fail'}, {'name': 'Lint', 'bucket': 'cancel'}]
        self.assertEqual(pr_review.failed_checks(checks), ['Assemble', 'Lint'])
        self.assertEqual(pr_review.failed_checks(checks[:2]), [])


class PublishedComment(unittest.TestCase):
    def test_local_paths_never_reach_the_pr(self):
        root, home = Path('/Users/someone/projects/app'), Path('/Users/someone')
        text = ('- [P1] 标题 — /Users/someone/projects/app/core/src/A.java:10-12\n'
                '  依 [AGENTS.md:94](/Users/someone/projects/app/AGENTS.md#L94) 核实，'
                '见 /Users/someone/.codex/memories/x.md')
        published = pr_review.publishable(text, root, home, f'https://github.com/owner/repo/blob/{NEW}')
        self.assertNotIn('/Users/someone', published)
        self.assertIn('— core/src/A.java:10-12', published)
        self.assertIn(f'[AGENTS.md:94](https://github.com/owner/repo/blob/{NEW}/AGENTS.md#L94)', published)
        self.assertIn('~/.codex/memories/x.md', published)


if __name__ == '__main__':
    unittest.main()
