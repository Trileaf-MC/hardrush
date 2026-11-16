#!/usr/bin/env python3
"""PR review loop: have Codex review the current branch's pull request, then merge once the gates hold.

Which findings to fix and when to stop are judgement calls described in ../SKILL.md. This script
owns the steps that must not depend on an agent's discipline: what exactly was reviewed, where the
result is recorded, and which conditions a merge has to meet.

It reads nothing project-specific. Requirements: git, an authenticated gh, a logged-in Codex CLI,
and a pull request whose head branch lives in the same repository as its base.
"""
from pathlib import Path
import argparse
import json
import re
import shutil
import subprocess
import sys
import time

MARKER = re.compile(r'<!-- codex-review round=(\d+) head=([0-9a-f]{40}) -->')
MAX_ROUNDS = 3
CHECKS_APPEAR_SECONDS = 180
CHECKS_FINISH_SECONDS = 45 * 60


def run(*cmd, check=True):
    result = subprocess.run(cmd, text=True, capture_output=True)
    if check and result.returncode:
        sys.exit(f'{" ".join(cmd)} 失败：\n{(result.stderr or result.stdout).strip()}')
    return result


def git(*args):
    return run('git', *args).stdout.strip()


def review_rounds(comment_bodies):
    """(round, reviewed head) of every recorded Codex review, oldest round first."""
    found = (MARKER.search(body) for body in comment_bodies)
    return sorted((int(match[1]), match[2]) for match in found if match)


def publishable(text, root, home, blob_url):
    """Codex cites absolute local paths; a PR comment gets links to the reviewed commit instead."""
    text = text.replace(f']({root}/', f']({blob_url}/')
    return text.replace(f'{root}/', '').replace(str(home), '~')


def review_blocker(rounds, extra_round):
    if len(rounds) >= MAX_ROUNDS and not extra_round:
        return f'这个 PR 已经评审了 {len(rounds)} 轮。不要继续循环：停下来向用户说明还剩哪些问题。'
    return None


def merge_blocker(pr, rounds, local_head, accept_unreviewed_fixes):
    head = pr['headRefOid']
    if pr['isDraft']:
        return 'PR 还是草稿。'
    if local_head != head:
        return '本地 HEAD 与 PR 的 head 不一致，先 push。'
    if not rounds:
        return '这个 PR 还没有 Codex 评审记录，先运行 review。评审跑不了时不要合并，告诉用户。'
    if rounds[-1][1] != head and not accept_unreviewed_fixes:
        return (f'最后一轮评审的是 {rounds[-1][1][:7]}，现在的 head 是 {head[:7]}。再评审一轮；'
                '只有其后的提交全是针对次要意见的小修时，才能加 --accept-unreviewed-fixes。')
    return None


def failed_checks(checks):
    return sorted(check['name'] for check in checks if check['bucket'] not in ('pass', 'skipping'))


def pull_request():
    fields = 'number,url,state,isDraft,baseRefName,headRefName,headRefOid,comments'
    result = run('gh', 'pr', 'view', '--json', fields, check=False)
    if result.returncode:
        sys.exit(f'当前分支没有 PR，先 push 并创建：\n{result.stderr.strip()}')
    pr = json.loads(result.stdout)
    if pr['state'] != 'OPEN':
        sys.exit(f'PR #{pr["number"]} 的状态是 {pr["state"]}。')
    return pr, review_rounds(comment['body'] for comment in pr['comments'])


def local_head():
    if git('status', '--porcelain'):
        sys.exit('工作区有未提交的改动。评审和合并都只针对已经 push 的提交。')
    return git('rev-parse', 'HEAD')


def remote_of(branch):
    return run('git', 'config', '--get', f'branch.{branch}.remote', check=False).stdout.strip() or 'origin'


def review(args):
    if not shutil.which('codex'):
        sys.exit('找不到 codex 命令。评审跑不了时不要合并，告诉用户。')
    pr, rounds = pull_request()
    head = local_head()
    if head != pr['headRefOid']:
        sys.exit('本地 HEAD 与 PR 的 head 不一致，先 push。')
    if blocker := review_blocker(rounds, args.extra_round):
        sys.exit(blocker)
    remote = remote_of(pr['headRefName'])
    base = f'{remote}/{pr["baseRefName"]}'
    git('fetch', remote, pr['baseRefName'])

    root = Path(git('rev-parse', '--show-toplevel'))
    number = rounds[-1][0] + 1 if rounds else 1
    # Under the git directory: never tracked, needs no ignore rule, and separate per worktree.
    directory = Path(git('rev-parse', '--absolute-git-dir')) / 'pr-review' / str(pr['number'])
    directory.mkdir(parents=True, exist_ok=True)
    verdict, log = directory / f'round-{number}.md', directory / f'round-{number}.log'
    verdict.unlink(missing_ok=True)
    # The reviewer may run the project's own tools, which often cache inside the checkout or fetch
    # documentation, but it must not be able to touch anything outside this checkout, whatever the
    # user's global Codex sandbox is. No prompt is passed: the author must not steer its own reviewer.
    command = ['codex', 'exec', 'review', '--base', base, '-o', str(verdict),
               '-c', f'model_reasoning_effort="{args.effort}"',
               '-c', 'sandbox_mode="workspace-write"',
               '-c', 'sandbox_workspace_write.network_access=true']
    print(f'Codex 开始评审 PR #{pr["number"]} 第 {number} 轮：{head[:7]} 对比 {base}（日志：{log}）', flush=True)
    with log.open('w') as output:
        try:
            code = subprocess.run(command, cwd=root, stdout=output, stderr=subprocess.STDOUT,
                                  timeout=args.timeout_minutes * 60).returncode
        except subprocess.TimeoutExpired:
            sys.exit(f'Codex 评审超过 {args.timeout_minutes} 分钟被终止。这不算通过。')
    body = verdict.read_text().strip() if verdict.is_file() else ''
    if code or not body:
        tail = '\n'.join(log.read_text(errors='replace').splitlines()[-15:])
        sys.exit(f'Codex 评审没有完成（退出码 {code}）。这不算通过，不要合并：\n{tail}')

    repository = run('gh', 'repo', 'view', '--json', 'url', '--jq', '.url').stdout.strip()
    body = publishable(body, root, Path.home(), f'{repository}/blob/{head}')
    comment = directory / f'round-{number}.comment.md'
    comment.write_text(f'<!-- codex-review round={number} head={head} -->\n'
                       f'## Codex 评审 · 第 {number} 轮\n\n'
                       f'评审对象：`{head[:7]}`，对比 `{base}`。以下是 Codex 输出的原文。\n\n{body}\n')
    url = run('gh', 'pr', 'comment', str(pr['number']), '--body-file', str(comment)).stdout.strip()
    print(f'\n{body}\n\n评审已贴到 PR：{url}')


def wait_for_checks(number):
    started = time.monotonic()
    while True:
        result = run('gh', 'pr', 'checks', str(number), '--json', 'name,bucket', check=False)
        checks = json.loads(result.stdout) if result.stdout.lstrip().startswith('[') else []
        if checks and all(check['bucket'] != 'pending' for check in checks):
            return checks
        waited = time.monotonic() - started
        if not checks and waited > CHECKS_APPEAR_SECONDS:
            sys.exit('PR 上一直没有出现 CI 检查，不合并。仓库确实没有 CI 时加 --no-checks。')
        if waited > CHECKS_FINISH_SECONDS:
            sys.exit('等 CI 超时，不合并。')
        time.sleep(20)


def merge(args):
    pr, rounds = pull_request()
    if blocker := merge_blocker(pr, rounds, local_head(), args.accept_unreviewed_fixes):
        sys.exit(blocker)
    if not args.no_checks:
        print(f'等待 PR #{pr["number"]} 的 CI…', flush=True)
        if failed := failed_checks(wait_for_checks(pr['number'])):
            sys.exit(f'CI 没有通过，不合并：{"、".join(failed)}')
    # --match-head-commit: a push that lands while we were waiting has been neither reviewed nor tested.
    run('gh', 'pr', 'merge', str(pr['number']), f'--{args.method}', '--match-head-commit', pr['headRefOid'])
    if not args.keep_branch:
        run('git', 'push', remote_of(pr['headRefName']), '--delete', pr['headRefName'], check=False)
    print(f'已合并 PR #{pr["number"]}：{pr["url"]}')


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest='command', required=True)
    reviewing = commands.add_parser('review', help='让 Codex 评审当前分支的 PR，并把结果贴成 PR 评论')
    reviewing.add_argument('--effort', default='high', help='Codex 推理强度（默认 high）')
    reviewing.add_argument('--timeout-minutes', type=int, default=60)
    reviewing.add_argument('--extra-round', action='store_true',
                           help=f'超过 {MAX_ROUNDS} 轮后再评审一轮；只在用户明确要求时使用')
    reviewing.set_defaults(action=review)
    merging = commands.add_parser('merge', help='评审过且 CI 通过时合并当前分支的 PR，并删除远端分支')
    merging.add_argument('--method', choices=('merge', 'squash', 'rebase'), default='merge')
    merging.add_argument('--accept-unreviewed-fixes', action='store_true',
                         help='最后一轮评审之后只有针对次要意见的小修时使用')
    merging.add_argument('--no-checks', action='store_true', help='仓库没有 CI 时使用')
    merging.add_argument('--keep-branch', action='store_true', help='合并后保留远端分支')
    merging.set_defaults(action=merge)
    args = parser.parse_args()
    args.action(args)


if __name__ == '__main__':
    main()
