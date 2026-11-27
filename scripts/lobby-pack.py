#!/usr/bin/env python3
"""Build a lobby pack headlessly (docs/plan/建筑清单与大厅包构建.md 2).

    python3 scripts/lobby-pack.py build <id | source directory>

An id means lobby-packs/<id>; a path is used as given (its directory name is still the pack id). The source holds
lobby.json, exactly one build.schem / build.litematic / build.nbt and usually build.hardrush.json. A GameTest suite
imports and packs it in a fresh server and writes out/report.json (plus out/<id>-v<version>.hrlobby without errors).
This prints the report's summary and exits non-zero when the pack has errors or the build itself failed. The full
Gradle output goes to .local/lobby-pack/, never to this terminal.
"""
from pathlib import Path
import argparse
import json
import os
import re
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.local' / 'lobby-pack'
ID = re.compile(r'[a-z0-9_-]{1,32}')
BUILDINGS = ('build.schem', 'build.litematic', 'build.nbt')
TIMEOUT_SECONDS = 15 * 60
LOG_LIMIT = 300 * 1024 * 1024
STORAGE_LINE = 'Isolated GameTest storage: '


class SourceError(Exception):
    pass


def source_directory(target):
    """An id → lobby-packs/<id>; anything else is a path."""
    if ID.fullmatch(target):
        return ROOT / 'lobby-packs' / target
    return Path(target).expanduser().resolve()


def check_source(source):
    """The checks of design 2.1 that need no game; the build repeats them and adds its own."""
    if not source.is_dir():
        raise SourceError(f'源目录不存在：{source}')
    if not ID.fullmatch(source.name):
        raise SourceError(f'源目录名 {source.name} 不是合法的包 id：只能用 1–32 个小写字母、数字、下划线或连字符')
    lobby = source / 'lobby.json'
    if not lobby.is_file():
        raise SourceError(f'缺少 {lobby}')
    try:
        header = json.loads(lobby.read_text(encoding='utf-8-sig'))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SourceError(f'读不了 lobby.json：{error}')
    if not isinstance(header, dict):
        raise SourceError('lobby.json 应当是一个 JSON 对象')
    if not isinstance(header.get('name'), str) or not header['name'].strip():
        raise SourceError('lobby.json 的 name 应当是非空字符串')
    authors = header.get('authors', [])
    if authors is not None and (not isinstance(authors, list) or not all(isinstance(a, str) for a in authors)):
        raise SourceError('lobby.json 的 authors 应当是字符串数组')
    version = header.get('version')
    if isinstance(version, bool) or not isinstance(version, int) or version < 1:
        raise SourceError('lobby.json 的 version 应当是从 1 开始的整数')
    found = [name for name in BUILDINGS if (source / name).exists() or (source / name).is_symlink()]
    if len(found) != 1:
        raise SourceError('缺少建筑文件（' + '、'.join(BUILDINGS) + ' 之一）' if not found
                          else '建筑文件只能有一个，现在有 ' + '、'.join(found))
    return header


def stop(process):
    """Ends only the process group this script started (Gradle, its single-use daemon and the game server)."""
    if process.poll() is not None:
        return
    for sig, wait in ((signal.SIGTERM, 20), (signal.SIGKILL, 10)):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            return
        try:
            process.wait(timeout=wait)
            return
        except subprocess.TimeoutExpired:
            continue


def storage_of(log):
    """The run directory Gradle printed, once it has."""
    try:
        with log.open('r', encoding='utf-8', errors='replace') as text:
            for line in text:
                if STORAGE_LINE in line:
                    return Path(line.split(STORAGE_LINE, 1)[1].strip())
    except OSError:
        pass
    return None


def run_gradle(source, log):
    """Runs the build suite under the watchdog; returns (exit code or None when stopped, why it was stopped)."""
    command = [str(ROOT / 'gradlew'), ':platforms:mc263:runGameTest', f'-PhardrushLobbyPack={source}',
               '--offline', '--console=plain', '--no-daemon']
    started = time.monotonic()
    storage = None
    with log.open('wb') as output:
        process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            while process.poll() is None:
                time.sleep(1)
                if time.monotonic() - started > TIMEOUT_SECONDS:
                    stop(process)
                    return None, f'超过 {TIMEOUT_SECONDS // 60} 分钟，已结束本次启动的进程'
                if log.stat().st_size > LOG_LIMIT:
                    stop(process)
                    return None, 'Gradle 输出超过 300 MB，已结束本次启动的进程'
                if storage is None:
                    storage = storage_of(log)
                latest = storage / 'logs' / 'latest.log' if storage else None
                if latest and latest.is_file() and latest.stat().st_size > LOG_LIMIT:
                    stop(process)
                    return None, f'{latest} 超过 300 MB，已结束本次启动的进程'
        except KeyboardInterrupt:
            stop(process)
            return None, '已中断，已结束本次启动的进程'
    return process.returncode, None


def where(problem):
    parts = []
    if 'manifest' in problem:
        parts.append('清单 ' + ' '.join(map(str, problem['manifest'])))
    if 'world' in problem:
        parts.append('世界 ' + ' '.join(map(str, problem['world'])))
    return '（' + '，'.join(parts) + '）' if parts else ''


def summarize(report, source):
    errors = [p for p in report.get('problems', []) if p.get('severity') == 'error']
    warnings = [p for p in report.get('problems', []) if p.get('severity') != 'error']
    print(f'ok: {"是" if report.get("ok") else "否"}')
    for note in report.get('notes', []):
        print(f'说明：{note}')
    for label, problems in (('错误', errors), ('警告', warnings)):
        if problems:
            print(f'{label} {len(problems)} 条：')
            for p in problems:
                head = '/'.join(x for x in (p.get('kind'), p.get('function')) if x)
                print(f'  [{head}] {p.get("message")}{where(p)}')
    if report.get('pack'):
        print(f'产物：{source / report["pack"]}')
    print(f'报告：{source / "out" / "report.json"}')
    return bool(report.get('ok'))


def build(target):
    source = source_directory(target)
    try:
        check_source(source)
    except SourceError as error:
        print(f'源目录有误：{error}', file=sys.stderr)
        return 2
    out = source / 'out'
    # A failed run must not leave the previous run's report or pack looking current.
    if out.is_dir():
        for stale in [out / 'report.json', *out.glob('*.hrlobby')]:
            if stale.is_file():
                stale.unlink()
    WORK.mkdir(parents=True, exist_ok=True)
    log = WORK / f'gradle-{source.name}-{time.strftime("%Y%m%d-%H%M%S")}.log'
    print(f'构建 {source.name}（Gradle 输出：{log}）…', flush=True)
    code, stopped = run_gradle(source, log)
    if stopped:
        print(f'构建没有完成：{stopped}', file=sys.stderr)
        return 3
    report_file = out / 'report.json'
    if not report_file.is_file():
        print(f'构建没有写出报告（Gradle 退出码 {code}），见 {log}', file=sys.stderr)
        return 3
    try:
        report = json.loads(report_file.read_text(encoding='utf-8'))
    except (OSError, json.JSONDecodeError) as error:
        print(f'报告读不了：{error}', file=sys.stderr)
        return 3
    ok = summarize(report, source)
    if code != 0:
        print(f'Gradle 退出码 {code}（构建工具出错，不是包的问题），见 {log}', file=sys.stderr)
        return 3
    return 0 if ok else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    command = commands.add_parser('build', help='构建一个大厅包源目录')
    command.add_argument('target', help='包 id（lobby-packs/<id>）或源目录路径')
    args = parser.parse_args()
    if args.command == 'build':
        return build(args.target)
    return 2


if __name__ == '__main__':
    sys.exit(main())
