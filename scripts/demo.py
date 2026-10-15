#!/usr/bin/env python3
"""Build and run an isolated, production-JAR-only HardRush 26.3 local demo."""

from pathlib import Path
import argparse
import hashlib
import importlib.util
import io
import ipaddress
import json
import os
import re
import shutil
import signal
import sqlite3
import subprocess
import sys
import time
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / '.local/demo'
LOCK = json.loads((ROOT / 'scripts/demo.lock.json').read_text())
FORBIDDEN = ('mcpfabric', 'spark', 'hardrush-gametest', 'hardrush-debug-tools',
             'dev/hardrush/gametest/', 'dev/hardrush/debug/')

# Reuse the independently checked snapshot prefix parser; it never starts a fixture.
snapshot_spec = importlib.util.spec_from_file_location('demo_snapshot', ROOT / 'scripts/p1-server-restart.py')
snapshot_reader = importlib.util.module_from_spec(snapshot_spec)
snapshot_spec.loader.exec_module(snapshot_reader)


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def java():
    home = os.environ.get('HARDRUSH_JAVA_HOME') or os.environ.get('JAVA_HOME')
    configured = ROOT / '.local/toolchains/java-home.txt'
    if not home and configured.is_file():
        home = configured.read_text().strip()
    executable = str(Path(home) / 'bin/java') if home else shutil.which('java')
    if not executable:
        raise RuntimeError('需要 Java 25；请设置 HARDRUSH_JAVA_HOME。')
    version = subprocess.run([executable, '-version'], capture_output=True, text=True, check=True)
    match = re.search(r'version "(\d+)', version.stderr + version.stdout)
    if not match or int(match[1]) != 25:
        raise RuntimeError('此 Demo 已验证 Java 25；请设置 HARDRUSH_JAVA_HOME 指向 Java 25。')
    return executable


def audit_jar(data, label):
    """Inspect nested mods too; release contents must not contain development code."""
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in archive.namelist():
            if any(token in name.lower() for token in FORBIDDEN):
                raise RuntimeError(f'Demo 含开发工具：{label}!{name}')
            if name.endswith('.jar'):
                audit_jar(archive.read(name), label + '!' + name)
        if 'fabric.mod.json' not in archive.namelist():
            raise RuntimeError(f'缺少 Fabric 元数据：{label}')
        metadata = json.loads(archive.read('fabric.mod.json'))
        if any(token in json.dumps(metadata).lower() for token in FORBIDDEN):
            raise RuntimeError(f'Demo 含开发工具元数据：{label}')
        return metadata


def launcher():
    cache = BASE / 'cache/fabric-server.jar'
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not cache.is_file() or digest(cache) != LOCK['launcher']['sha256']:
        with urllib.request.urlopen(LOCK['launcher']['url'], timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != LOCK['launcher']['sha256']:
            raise RuntimeError('Fabric 启动器校验失败；保留已有文件。')
        temporary = cache.with_suffix('.part')
        temporary.write_bytes(data)
        temporary.replace(cache)
    with zipfile.ZipFile(cache) as archive:
        properties = archive.read('install.properties').decode()
        if f'game-version={LOCK["minecraft"]}' not in properties or f'fabric-loader-version={LOCK["loader"]}' not in properties:
            raise RuntimeError('Fabric 启动器版本与锁文件不符。')
    return cache


def build():
    # Build through Gradle, but never use Loom to launch the demo server.
    subprocess.run([str(ROOT / 'scripts/p0-gradle.sh'), ':platforms:mc263:stageDemoMods',
                    '--console=plain'], cwd=ROOT, check=True)
    staged = ROOT / 'platforms/mc263/build/demo-mods'
    mods = sorted(staged.glob('*.jar'))
    metadata = {path: audit_jar(path.read_bytes(), path.name) for path in mods}
    if sorted(item['id'] for item in metadata.values()) != ['fabric-api', 'hardrush']:
        raise RuntimeError('Demo 必须恰好包含 HardRush 和 Fabric API 两个顶层模组。')
    properties = dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines() if '=' in line)
    if properties['minecraft_version'] != LOCK['minecraft'] or properties['loader_version'] != LOCK['loader']:
        raise RuntimeError('Gradle 版本已变更，请先更新并核验 Demo 锁文件。')
    server = launcher()
    bundle = BASE / 'bundles' / str(uuid.uuid4())
    (bundle / 'mods').mkdir(parents=True)
    shutil.copyfile(server, bundle / 'fabric-server.jar')
    for path in mods:
        shutil.copyfile(path, bundle / 'mods' / path.name)
    shutil.copyfile(ROOT / 'THIRD-PARTY.md', bundle / 'THIRD-PARTY.md')
    shutil.copytree(ROOT / 'licenses', bundle / 'licenses')
    source_paths = subprocess.check_output(['git', 'ls-files', '-co', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    source = {name: digest(ROOT / name) for name in sorted(set(source_paths)) if name and
              (name.endswith(('.java', '.gradle', '.properties')) or name in ('scripts/demo.py', 'scripts/demo.lock.json'))}
    files = {str(path.relative_to(bundle)): digest(path) for path in sorted(bundle.rglob('*')) if path.is_file()}
    write_json(bundle / 'manifest.json', dict(schema=1, minecraft=LOCK['minecraft'], loader=LOCK['loader'],
               gitHead=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip(),
               gitStatus=subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT).decode().splitlines(),
               sourceSha256=source, files=files))
    print(f'Demo 制品：{bundle}', flush=True)
    return bundle


def verify_bundle(bundle):
    manifest = json.loads((bundle / 'manifest.json').read_text())
    if manifest.get('schema') != 1:
        raise RuntimeError('不支持的 Demo 制品清单。')
    for name, expected in manifest['files'].items():
        path = bundle / name
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(bundle.resolve()) or digest(path) != expected:
            raise RuntimeError('Demo 制品缺失或已变更：' + name)
    expected_mods = {name for name in manifest['files'] if name.startswith('mods/')}
    if {str(p.relative_to(bundle)) for p in (bundle / 'mods').iterdir()} != expected_mods:
        raise RuntimeError('Demo 含清单外模组。')


def prepare(bundle, mode, port, bind='127.0.0.1'):
    verify_bundle(bundle)
    run = BASE / 'instances' / str(uuid.uuid4())
    run.mkdir(parents=True)
    shutil.copyfile(bundle / 'fabric-server.jar', run / 'fabric-server.jar')
    shutil.copytree(bundle / 'mods', run / 'mods')
    # Reuse only an existing human acceptance. Never infer or create eula=true.
    existing_eula = ROOT / '.local/server-p0/eula.txt'
    eula = '# Review https://aka.ms/MinecraftEULA\neula=false\n'
    if existing_eula.is_file() and not existing_eula.is_symlink():
        contents = existing_eula.read_text()
        if 'eula=true' in [line.strip() for line in contents.splitlines()]:
            eula = contents
    (run / 'eula.txt').write_text(eula)
    (run / 'server.properties').write_text('\n'.join((
        '# HardRush demo: authenticated vanilla clients; loopback by default.',
        f'server-ip={bind}', f'server-port={port}', 'online-mode=true',
        'enable-rcon=false', 'enable-query=false', 'white-list=false',
        'level-name=world', 'level-seed=12345', 'gamemode=survival', 'difficulty=hard',
        'max-players=20', 'spawn-protection=0', 'view-distance=6', 'simulation-distance=4',
        'pause-when-empty-seconds=0', 'motd=HardRush 26.3 local MVP demo', '',
    )))
    write_json(run / 'demo.json', dict(schema=1, bundle=bundle.name, initialMode=mode, port=port, bind=bind))
    print(f'Demo 实例：{run}\n启动：python3 scripts/demo.py run {run.name}\n监听：{bind}:{port}', flush=True)
    return run


def instance(identifier):
    # A UUID selects an owned instance; no arbitrary world path is accepted.
    if str(uuid.UUID(identifier)) != identifier:
        raise RuntimeError('实例 ID 必须为 prepare 输出的 UUID。')
    run = BASE / 'instances' / identifier
    if run.is_symlink() or not run.is_dir() or run.resolve().parent != (BASE / 'instances').resolve():
        raise RuntimeError('Demo 实例不存在或不是独立目录。')
    return run


def launch_command(run):
    config = json.loads((run / 'demo.json').read_text())
    if config.get('schema') != 1 or config.get('initialMode') not in ('normal', 'hardcore'):
        raise RuntimeError('无效的 Demo 配置。')
    bind = str(ipaddress.ip_address(config['bind']))
    bundle = BASE / 'bundles' / str(uuid.UUID(config['bundle']))
    verify_bundle(bundle)
    manifest = json.loads((bundle / 'manifest.json').read_text())
    for name, expected in manifest['files'].items():
        if name == 'fabric-server.jar' or name.startswith('mods/'):
            if (run / name).is_symlink() or digest(run / name) != expected:
                raise RuntimeError('实例制品与已验证构建不一致：' + name)
    if sorted(p.name for p in (run / 'mods').iterdir()) != sorted(p.name for p in (bundle / 'mods').iterdir()):
        raise RuntimeError('实例存在额外模组；请创建新的干净 Demo。')
    props = dict(line.split('=', 1) for line in (run / 'server.properties').read_text().splitlines()
                 if '=' in line and not line.lstrip().startswith('#'))
    if any(props.get(key) != value for key, value in {'server-ip': bind, 'server-port': str(config['port']), 'online-mode': 'true',
           'enable-rcon': 'false', 'enable-query': 'false', 'level-name': 'world'}.items()):
        raise RuntimeError('Demo 的正版认证、监听地址或独立存档配置已变更；请重新 prepare。')
    if 'eula=true' not in [line.strip() for line in (run / 'eula.txt').read_text().splitlines()]:
        raise RuntimeError(f'请阅读 EULA 并在 {run / "eula.txt"} 中自行确认。')
    return [java(), '-Xms512M', '-Xmx2G', '--enable-native-access=ALL-UNNAMED',
            '-Dhardrush.p1.enabled=true', '-Dhardrush.p1.initialMode=' + config['initialMode'],
            '-jar', 'fabric-server.jar', 'nogui']


def smoke_session(run, attempt, timeout):
    """Console-only health check. No client, network player, or test mod is loaded."""
    command = launch_command(run)
    log = run / f'smoke-{attempt}.log'
    result = dict(command=command, log=log.name, status='RUNNING')
    with log.open('w') as output:
        process = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=output,
                                   stderr=subprocess.STDOUT, text=True, start_new_session=True)
        result['pid'] = process.pid
        try:
            deadline = time.monotonic() + timeout
            next_query = 0
            while time.monotonic() < deadline:
                contents = log.read_text(errors='replace')
                if process.poll() is not None:
                    raise RuntimeError('服务端在就绪前退出。')
                if re.search(r'阶段 WAITING.*世界准备：已完成', contents):
                    result['statusLine'] = next(line for line in reversed(contents.splitlines()) if '阶段 WAITING' in line)
                    break
                if 'Done (' in contents and time.monotonic() >= next_query:
                    process.stdin.write('hardrush status\n')
                    process.stdin.flush()
                    next_query = time.monotonic() + 2
                time.sleep(0.2)
            else:
                raise RuntimeError('等待实际三维度准备超时。')
            process.stdin.write('stop\n')
            process.stdin.flush()
            process.wait(timeout=60)
            if process.returncode != 0:
                raise RuntimeError(f'正常停服失败：{process.returncode}')
            contents = log.read_text(errors='replace')
            if re.search(r'\bERROR\b|Failed to save|Could not save|Exception closing', contents):
                raise RuntimeError('服务端日志存在错误，保留现场待检查。')
            database = run / 'world/hardrush/p1.sqlite'
            with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as connection:
                if connection.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
                    raise RuntimeError('SQLite 完整性检查失败。')
                row = connection.execute('''
                    SELECT snapshots.round_id, snapshots.version, snapshots.payload, events.payload_digest
                    FROM round_snapshots AS snapshots JOIN store_events AS events ON events.sequence=snapshots.revision
                    ORDER BY snapshots.revision DESC LIMIT 1
                ''').fetchone()
                if row is None or hashlib.sha256(row[2]).digest() != row[3]:
                    raise RuntimeError('持久轮次缺失或提交摘要不一致。')
                snapshot = snapshot_reader.read_snapshot_header(row[2])
                if (not snapshot['cleanShutdown'] or snapshot['phase'] != 'WAITING'
                        or snapshot['roundId'] != row[0] or snapshot['version'] != row[1]
                        or snapshot['roundId'] not in result['statusLine']):
                    raise RuntimeError('正常停服标记、轮次或 WAITING 检查点不一致。')
            result.update(status='PASS', exitCode=process.returncode, databaseSha256=digest(database), snapshot=snapshot)
        except BaseException as error:
            result.update(status='FAIL', error=str(error))
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=10)
            raise
        finally:
            process.stdin.close()
            write_json(run / f'smoke-{attempt}.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    actions.add_parser('build', help='构建并审计生产 JAR，下载锁定的 Fabric 启动器')
    setup = actions.add_parser('prepare', help='构建并创建新 Demo；从不覆盖已有存档')
    setup.add_argument('--mode', choices=('normal', 'hardcore'), default='hardcore')
    setup.add_argument('--port', type=int, default=25587, choices=range(1024, 65536), metavar='PORT')
    setup.add_argument('--bind', type=lambda value: str(ipaddress.ip_address(value)), default='127.0.0.1',
                       help='默认仅本机；局域网双人演示可显式指定 0.0.0.0')
    start = actions.add_parser('run', help='前台启动已有实例；控制台 stop 正常保存')
    start.add_argument('instance')
    smoke = actions.add_parser('smoke', help='从生产 JAR 验证两种模式的准备、停服与重开，无客户端')
    smoke.add_argument('--timeout', type=int, default=240)
    args = parser.parse_args()
    if args.action == 'run':
        run = instance(args.instance)
        command = launch_command(run)
        os.chdir(run)
        os.execv(command[0], command)
    java()
    bundle = build()
    if args.action == 'prepare':
        prepare(bundle, args.mode, args.port, args.bind)
    elif args.action == 'smoke':
        results = []
        for mode in ('normal', 'hardcore'):
            run = prepare(bundle, mode, 0)
            first = smoke_session(run, 1, args.timeout)
            second = smoke_session(run, 2, args.timeout)
            round_ids = [re.search(r'（([0-9a-f-]{36})）', item['statusLine'])[1] for item in (first, second)]
            if first['pid'] == second['pid'] or round_ids[0] != round_ids[1]:
                raise RuntimeError('新进程重开未保留同一轮次。')
            expected_mode = '普通' if mode == 'normal' else '极限'
            if any(f'本轮模式 {expected_mode}' not in item['statusLine'] for item in (first, second)):
                raise RuntimeError('实例模式与实际持久轮次不符。')
            results.append(dict(mode=mode, instance=run.name, roundId=round_ids[0], sessions=[first, second]))
        write_json(bundle / 'smoke-result.json', dict(status='PASS', results=results))
        print(f'两种模式、4 个独立服务端 JVM 验证通过：{bundle / "smoke-result.json"}', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError, subprocess.SubprocessError) as error:
        print(f'Demo 失败：{error}', file=sys.stderr)
        sys.exit(1)
