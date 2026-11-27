#!/usr/bin/env python3
"""Build a lobby pack headlessly (docs/plan/建筑清单与大厅包构建.md 2).

    python3 scripts/lobby-pack.py build <id | source directory>
    python3 scripts/lobby-pack.py sample <new source directory>

An id means lobby-packs/<id>; a path is used as given (its directory name is still the pack id). The source holds
lobby.json, exactly one build.schem / build.litematic / build.nbt and usually build.hardrush.json. A GameTest suite
imports and packs it in a fresh server and writes out/report.json (plus out/<id>-v<version>.hrlobby without errors).
This prints the report's summary and exits non-zero when the pack has errors or the build itself failed. The full
Gradle output goes to .local/lobby-pack/, never to this terminal. The build needs the Minecraft EULA already accepted
in .local/server/eula.txt; this script never accepts it.

`sample` writes a minimal source directory with every lobby function bound, for trying the build or for importing
build.schem with build.hardrush.json in the workshop by hand. It builds with no problems at all.

Exit codes: 0 built, 1 the pack has errors, 2 the source directory (or sample target) is wrong, 3 the tooling failed.
"""
from pathlib import Path
import argparse
import gzip
import json
import os
import re
import signal
import struct
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
EULA = ROOT / '.local' / 'server' / 'eula.txt'
SAMPLE_DATA_VERSION = 5023


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


def eula_accepted():
    """The user's own acceptance in .local/server/eula.txt, which runGameTest copies into its fresh storage."""
    try:
        return any(line.strip() == 'eula=true' for line in EULA.read_text(encoding='utf-8', errors='replace').splitlines())
    except OSError:
        return False


def build(target):
    source = source_directory(target)
    try:
        check_source(source)
    except SourceError as error:
        print(f'源目录有误：{error}', file=sys.stderr)
        return 2
    if not eula_accepted():
        print(f'构建要启动一个 Minecraft 服务端，需要先接受 Minecraft EULA：请阅读 https://aka.ms/MinecraftEULA ，'
              f'同意的话在 {EULA} 里写一行 eula=true（本脚本不会替你接受）。', file=sys.stderr)
        return 3
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


# --- sample ------------------------------------------------------------------------------------------------------

class Short(int):
    pass


class IntArray(list):
    pass


def nbt_payload(value):
    """(tag id, payload) for the few tag types a Sponge schematic needs; lists are always empty."""
    if isinstance(value, Short):
        return 2, struct.pack('>h', value)
    if isinstance(value, int):
        return 3, struct.pack('>i', value)
    if isinstance(value, (bytes, bytearray)):
        return 7, struct.pack('>i', len(value)) + bytes(value)
    if isinstance(value, IntArray):
        return 11, struct.pack('>i', len(value)) + b''.join(struct.pack('>i', v) for v in value)
    if isinstance(value, list):
        return 9, b'\x00' + struct.pack('>i', 0)
    if isinstance(value, dict):
        out = b''
        for key, child in value.items():
            tag, data = nbt_payload(child)
            name = key.encode('utf-8')
            out += bytes([tag]) + struct.pack('>H', len(name)) + name + data
        return 10, out + b'\x00'
    raise TypeError(type(value))


def schematic(size, blocks):
    """Sponge v3 .schem bytes: size (w, h, l), blocks {(x, y, z): 'id[states]'} relative to the minimum corner."""
    w, h, l = size
    palette = {'minecraft:air': 0}
    cells = [0] * (w * h * l)
    for (x, y, z), state in blocks.items():
        cells[x + z * w + y * w * l] = palette.setdefault(state, len(palette))
    data = bytearray()
    for index in cells:
        while True:
            byte, index = index & 0x7F, index >> 7
            data.append(byte | 0x80 if index else byte)
            if not index:
                break
    root = {'Schematic': {'Version': 3, 'DataVersion': SAMPLE_DATA_VERSION, 'Width': Short(w), 'Height': Short(h),
                          'Length': Short(l), 'Offset': IntArray([0, 0, 0]),
                          'Blocks': {'Palette': palette, 'Data': data, 'BlockEntities': []}}}
    tag, payload = nbt_payload(root)
    return gzip.compress(bytes([tag]) + struct.pack('>H', 0) + payload, mtime=0)


SAMPLE_ORIGIN = (100, 0, 200)


def sample_building():
    """A 21×2×21 stone yard, local cells: three wool patches for the zones, a fence row that only joins up through
    update_shapes, lecterns and stone bricks to click, both vaults and a sign for the status text."""
    blocks = {}
    for x in range(21):
        for z in range(21):
            blocks[(x, 0, z)] = 'minecraft:stone'
    for wool, (x0, z0) in (('lime', (2, 2)), ('red', (16, 2)), ('purple', (2, 16))):
        for x in range(x0, x0 + 3):
            for z in range(z0, z0 + 3):
                blocks[(x, 0, z)] = f'minecraft:{wool}_wool'
    for x in range(6, 15):
        blocks[(x, 1, 0)] = 'minecraft:oak_fence'
    blocks[(16, 1, 16)] = 'minecraft:vault[ominous=false]'
    blocks[(18, 1, 16)] = 'minecraft:vault[ominous=true]'
    for x in (6, 8):
        blocks[(x, 1, 18)] = 'minecraft:lectern'
    for x in (12, 14):
        blocks[(x, 1, 18)] = 'minecraft:stone_bricks'
    blocks[(10, 1, 20)] = 'minecraft:oak_sign'
    return (21, 2, 21), blocks


def sample_manifest():
    """Every function the lobby needs, in a frame where the building's minimum corner is SAMPLE_ORIGIN."""
    ox, oy, oz = SAMPLE_ORIGIN

    def at(x, y, z):
        return [ox + x, oy + y, oz + z]

    markers = [
        {'function': 'assembly', 'as': 'zone', 'shape': 'flood', 'at': at(3, 0, 3)},
        {'function': 'courtyard', 'as': 'zone', 'shape': 'flood', 'at': at(17, 0, 3)},
        {'function': 'altar_pad', 'as': 'zone', 'shape': 'flood', 'at': at(3, 0, 17)},
        {'function': 'landing_central', 'as': 'point', 'at': at(10, 1, 10), 'yaw': 180},
        {'function': 'landing_monster', 'as': 'point', 'at': at(17, 1, 8)},
    ]
    # Ten display slots: the comfortable minimum, so the build has no capacity warning either.
    markers += [{'function': 'display_slot', 'as': 'point', 'at': at(x, 1, z)} for z in (12, 14) for x in range(6, 15, 2)]
    markers += [{'function': f, 'as': 'model', 'at': at(x, 1, 8), 'yaw': 180}
                for f, x in (('role_zombie', 4), ('role_skeleton', 6), ('role_creeper', 13), ('merchant', 15))]
    markers += [{'function': f, 'as': 'click', 'at': at(x, 1, 18)}
                for f, x in (('rules', 6), ('history', 8), ('page_prev', 12), ('page_next', 14))]
    markers += [
        {'function': 'vault', 'as': 'block', 'at': at(16, 1, 16)},
        {'function': 'ominous_vault', 'as': 'block', 'at': at(18, 1, 16)},
        {'function': 'status', 'as': 'text', 'at': at(10, 1, 20)},
    ]
    signs = [{'at': at(10, 1, 20), 'front': ['示例大厅', '状态牌'], 'back': []}]
    return {'format': 1, 'origin': list(SAMPLE_ORIGIN), 'update_shapes': True, 'markers': markers, 'signs': signs}


def manifest_text(manifest):
    """One marker or sign per line, like a hand-written manifest."""
    lines = ['{']
    keys = list(manifest)
    for k, key in enumerate(keys):
        value, comma = manifest[key], ',' if k < len(keys) - 1 else ''
        if isinstance(value, list) and value and all(isinstance(item, dict) for item in value):
            lines.append(f'  {json.dumps(key)}: [')
            lines += [f'    {json.dumps(item, ensure_ascii=False)}{"," if i < len(value) - 1 else ""}' for i, item in enumerate(value)]
            lines.append(f'  ]{comma}')
        else:
            lines.append(f'  {json.dumps(key)}: {json.dumps(value, ensure_ascii=False)}{comma}')
    return '\n'.join(lines) + '\n}\n'


def sample(target):
    directory = Path(target).expanduser().resolve()
    if not ID.fullmatch(directory.name):
        print(f'目录名 {directory.name} 不是合法的包 id：只能用 1–32 个小写字母、数字、下划线或连字符', file=sys.stderr)
        return 2
    if directory.is_symlink() or (directory.exists() and (not directory.is_dir() or any(directory.iterdir()))):
        print(f'{directory} 已存在且不是空目录，不覆盖', file=sys.stderr)
        return 2
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'lobby.json').write_text(
        json.dumps({'name': '示例大厅', 'authors': ['lobby-pack.py'], 'version': 1}, ensure_ascii=False) + '\n', encoding='utf-8')
    size, blocks = sample_building()
    (directory / 'build.schem').write_bytes(schematic(size, blocks))
    (directory / 'build.hardrush.json').write_text(manifest_text(sample_manifest()), encoding='utf-8')
    print(f'已生成样例源目录：{directory}')
    print(f'构建：python3 scripts/lobby-pack.py build {directory}')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    command = commands.add_parser('build', help='构建一个大厅包源目录')
    command.add_argument('target', help='包 id（lobby-packs/<id>）或源目录路径')
    command = commands.add_parser('sample', help='生成一个功能齐全的最小样例源目录')
    command.add_argument('target', help='新目录的路径，目录名就是包 id；已存在时必须是空目录')
    args = parser.parse_args()
    if args.command == 'build':
        return build(args.target)
    if args.command == 'sample':
        return sample(args.target)
    return 2


if __name__ == '__main__':
    sys.exit(main())
