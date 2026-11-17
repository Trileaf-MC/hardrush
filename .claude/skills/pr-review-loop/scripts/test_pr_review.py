from pathlib import Path
import unittest

import pr_review

HEAD = 'a' * 40
OLD = 'b' * 40


def review_comment(number, head=HEAD, lane='critic-codex', findings=''):
    return f'<!-- pr-review-loop review round={number} head={head} lane={lane} -->\n## 评审\n\n总评。\n\n## 意见\n\n{findings}'


def response_comment(number, body='逐条处置。'):
    return f'<!-- pr-review-loop response round={number} -->\n## 处置\n\n{body}'


def pull_request(head=HEAD, draft=False):
    return {'isDraft': draft, 'headRefOid': head}


class ParseRoundsTest(unittest.TestCase):
    def test_groups_reviewers_of_one_round_and_orders_rounds(self):
        rounds = pr_review.parse_rounds([
            review_comment(2, findings='- [P2] 小问题 — a.py:1'),
            '无关的评论',
            review_comment(1, OLD, 'critic-codex', '- [P1] 缺陷 — a.py:1\n- [P3] 建议 — b.py:2'),
            review_comment(1, OLD, 'critic-cursor-grok', '  * [P1] 另一个缺陷 — c.py:3'),
            response_comment(1),
        ])
        self.assertEqual([1, 2], [recorded.number for recorded in rounds])
        self.assertEqual({'critic-codex', 'critic-cursor-grok'}, set(rounds[0].reviews))
        self.assertEqual(OLD, rounds[0].head)
        self.assertEqual({'P1': 2, 'P3': 1}, dict(rounds[0].findings))
        self.assertEqual(2, rounds[0].blocking)
        self.assertTrue(rounds[0].response)
        self.assertFalse(rounds[1].response)
        self.assertEqual(0, rounds[1].blocking)

    def test_marker_only_counts_at_the_start_of_a_comment(self):
        quoted = response_comment(1, f'评审者说：\n{review_comment(5)}')
        rounds = pr_review.parse_rounds([review_comment(1), quoted, f'看这个：{review_comment(9)}'])
        self.assertEqual([1], [recorded.number for recorded in rounds])
        self.assertTrue(rounds[0].response)

    def test_review_without_findings_has_none(self):
        (recorded,) = pr_review.parse_rounds([review_comment(1, findings='没有发现需要处理的问题。')])
        self.assertFalse(recorded.findings)


class ReviewBlockerTest(unittest.TestCase):
    def test_first_round_is_allowed(self):
        self.assertIsNone(pr_review.review_blocker([]))

    def test_findings_must_be_answered_before_the_next_round(self):
        unanswered = pr_review.parse_rounds([review_comment(1, findings='- [P2] 问题 — a.py:1')])
        self.assertIn('处置记录', pr_review.review_blocker(unanswered))
        answered = pr_review.parse_rounds([review_comment(1, findings='- [P2] 问题 — a.py:1'), response_comment(1)])
        self.assertIsNone(pr_review.review_blocker(answered))

    def test_round_limit_needs_an_explicit_extra_round(self):
        rounds = pr_review.parse_rounds([review_comment(number) for number in (1, 2, 3)])
        self.assertIn('3 轮', pr_review.review_blocker(rounds))
        self.assertIsNone(pr_review.review_blocker(rounds, extra_round=True))


class MergeBlockerTest(unittest.TestCase):
    def rounds(self, *comments):
        return pr_review.parse_rounds(comments)

    def test_clean_review_of_the_current_head_can_merge(self):
        self.assertIsNone(pr_review.merge_blocker(pull_request(), self.rounds(review_comment(1))))

    def test_draft_and_unreviewed_pull_requests_cannot_merge(self):
        self.assertIn('草稿', pr_review.merge_blocker(pull_request(draft=True), self.rounds(review_comment(1))))
        self.assertIn('没有评审记录', pr_review.merge_blocker(pull_request(), []))

    def test_findings_need_a_response(self):
        rounds = self.rounds(review_comment(1, findings='- [P3] 建议 — a.py:1'))
        self.assertIn('处置记录', pr_review.merge_blocker(pull_request(), rounds))
        rounds = self.rounds(review_comment(1, findings='- [P3] 建议 — a.py:1'), response_comment(1))
        self.assertIsNone(pr_review.merge_blocker(pull_request(), rounds))

    def test_author_cannot_close_blocking_findings_alone(self):
        rejected = self.rounds(review_comment(1, findings='- [P1] 缺陷 — a.py:1'), response_comment(1, '不属实。'))
        self.assertIn('P0/P1', pr_review.merge_blocker(pull_request(), rejected))
        fixed = self.rounds(review_comment(1, OLD, findings='- [P0] 缺陷 — a.py:1'), response_comment(1, '已修。'))
        self.assertIn('P0/P1', pr_review.merge_blocker(pull_request(), fixed, accept_unreviewed_fixes=True))
        self.assertIsNone(pr_review.merge_blocker(pull_request(), rejected, user_approved_open_findings=True))

    def test_a_later_clean_round_settles_earlier_blocking_findings(self):
        rounds = self.rounds(review_comment(1, OLD, findings='- [P1] 缺陷 — a.py:1'), response_comment(1),
                             review_comment(2))
        self.assertIsNone(pr_review.merge_blocker(pull_request(), rounds))

    def test_commits_after_the_last_review_need_a_round_or_an_explicit_waiver(self):
        rounds = self.rounds(review_comment(1, OLD, findings='- [P2] 问题 — a.py:1'), response_comment(1))
        self.assertIn(OLD[:7], pr_review.merge_blocker(pull_request(), rounds))
        self.assertIsNone(pr_review.merge_blocker(pull_request(), rounds, accept_unreviewed_fixes=True))


class VerifyReviewTest(unittest.TestCase):
    def test_completed_review_loses_its_protocol_lines(self):
        text = f'REVIEWED-HEAD: {HEAD}\n\n总评。\n\n## 意见\n\n- [P2] 问题 — a.py:1\n\nREVIEW-STATUS: complete\n'
        body, problem = pr_review.verify_review(text, HEAD)
        self.assertIsNone(problem)
        self.assertEqual('总评。\n\n## 意见\n\n- [P2] 问题 — a.py:1', body)

    def test_markdown_decoration_and_short_hashes_are_accepted(self):
        text = f'**REVIEWED-HEAD:** `{HEAD[:12]}`\n\n总评。\n\n`REVIEW-STATUS: complete`'
        body, problem = pr_review.verify_review(text, HEAD)
        self.assertIsNone(problem)
        self.assertEqual('总评。', body)

    def test_reply_that_is_not_a_finished_review_is_rejected(self):
        cases = {
            '没能读取仓库，无法评审。': 'REVIEW-STATUS',
            f'REVIEWED-HEAD: {HEAD}\n读不到文件。\nREVIEW-STATUS: incomplete': 'incomplete',
            '总评。\nREVIEW-STATUS: complete': 'REVIEWED-HEAD',
            f'REVIEWED-HEAD: {OLD}\n总评。\nREVIEW-STATUS: complete': OLD,
            f'REVIEWED-HEAD: {HEAD}\nREVIEW-STATUS: complete': '空',
        }
        for text, expected in cases.items():
            body, problem = pr_review.verify_review(text, HEAD)
            self.assertIsNone(body, text)
            self.assertIn(expected, problem)

    def test_relay_failure_is_not_a_review(self):
        self.assertIn('result.json', pr_review.completed_review(None, HEAD)[1])
        failed = {'status': 'timeout', 'exitCode': 124, 'stderrTail': ['line one', 'line two']}
        self.assertIn('timeout', pr_review.completed_review(failed, HEAD)[1])
        finished = {'status': 'completed', 'finalMessage': f'REVIEWED-HEAD: {HEAD}\n总评。\nREVIEW-STATUS: complete'}
        self.assertEqual(('总评。', None), pr_review.completed_review(finished, HEAD))


class RenderingTest(unittest.TestCase):
    def test_local_paths_become_links_to_the_reviewed_commit(self):
        root, home = Path('/Users/someone/code/repo'), Path('/Users/someone')
        text = '见 [a.py](/Users/someone/code/repo/src/a.py#L3) 和 /Users/someone/code/repo/b.py，配置在 /Users/someone/.config'
        self.assertEqual('见 [a.py](https://example.test/blob/abc/src/a.py#L3) 和 b.py，配置在 ~/.config',
                         pr_review.publishable(text, root, home, 'https://example.test/blob/abc'))

    def test_overlong_review_is_truncated(self):
        text = pr_review.publishable('长' * (pr_review.MAX_COMMENT_CHARS + 10), Path('/r'), Path('/h'), 'u')
        self.assertLess(len(text), pr_review.MAX_COMMENT_CHARS + 100)
        self.assertIn('截断', text)

    def test_briefs_render_with_values_that_contain_braces(self):
        values = {'head': HEAD, 'base_ref': 'origin/main', 'base_sha': OLD, 'number': '7', 'round': '2',
                  'title': '标题 {head}', 'body': '正文里有 {unknown} 和 {body}'}
        brief = pr_review.render('review-brief.md', history='', **values)
        self.assertIn('正文里有 {unknown} 和 {body}', brief)
        self.assertIn('标题 {head}', brief)
        self.assertIn(f'git diff {OLD}...{HEAD}', brief)
        delta = pr_review.render('rereview-brief.md', prev_head=OLD, response='处置 {x}', **values)
        self.assertIn('处置 {x}', delta)
        self.assertIn(f'git diff {OLD}..{HEAD}', delta)

    def test_history_carries_the_previous_round_without_markers(self):
        self.assertEqual('', pr_review.history_section(None))
        (previous,) = pr_review.parse_rounds([review_comment(1, OLD, findings='- [P1] 缺陷 — a.py:1'),
                                              response_comment(1, '不属实，见 a.py。')])
        history = pr_review.history_section(previous)
        self.assertIn('- [P1] 缺陷 — a.py:1', history)
        self.assertIn('不属实，见 a.py。', history)
        self.assertNotIn('<!--', history)

    def test_failed_checks_ignore_passed_and_skipped(self):
        checks = [{'name': 'b', 'bucket': 'fail'}, {'name': 'a', 'bucket': 'cancel'},
                  {'name': 'c', 'bucket': 'pass'}, {'name': 'd', 'bucket': 'skipping'}]
        self.assertEqual(['a', 'b'], pr_review.failed_checks(checks))


if __name__ == '__main__':
    unittest.main()
