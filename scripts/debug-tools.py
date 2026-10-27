#!/usr/bin/env python3
"""Reproducible local Minecraft debug tools. Never touches running gameplay instances."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import secrets
import shutil
import signal
import socket
import subprocess
import tarfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / '.local/debug'
CACHE = ROOT / '.local/devtools'
LOCK = json.loads((ROOT / 'scripts/debug-tools.lock.json').read_text())
MCP = CACHE / ('mcpfabric-' + LOCK['mcpfabricSource']['commit']) / 'mcp-server'
PORTS = {'server': (25586, 25599, 5005), 'client': (25601, 5006)}


def artifact(path, spec, algorithm):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and hashlib.new(algorithm, path.read_bytes()).hexdigest() == spec[algorithm]:
        return
    print('Downloading ' + path.name, flush=True)
    with urllib.request.urlopen(spec['url'], timeout=60) as response:
        contents = response.read()
    if hashlib.new(algorithm, contents).hexdigest() != spec[algorithm]:
        raise RuntimeError('Checksum mismatch: ' + path.name)
    tmp = path.with_suffix('.part')
    tmp.write_bytes(contents)
    tmp.replace(path)


def accepted_eula():
    path = ROOT / '.local/server/eula.txt'
    if path.exists() and 'eula=true' in [s.strip() for s in path.read_text().splitlines()]:
        return path.read_text()
    raise RuntimeError('Read https://aka.ms/MinecraftEULA and accept it in .local/server/eula.txt first.')


def prepare():
    eula = accepted_eula()
    CACHE.mkdir(parents=True, exist_ok=True)
    for mod in LOCK['mods']:
        artifact(CACHE / 'mods' / mod['filename'], mod, 'sha512')
    archive = CACHE / 'mcpfabric-source.tar.gz'
    artifact(archive, LOCK['mcpfabricSource'], 'sha256')
    if not MCP.exists():
        with tarfile.open(archive) as tar:
            tar.extractall(CACHE, filter='data')
    pnpm_lock = ROOT / 'scripts/mcpfabric-pnpm-lock.yaml'
    shutil.copyfile(pnpm_lock, MCP / 'pnpm-lock.yaml')
    if not (MCP / 'dist/index.js').exists() or not (MCP / 'node_modules').exists():
        subprocess.run(['corepack', 'pnpm', 'install', '--frozen-lockfile'], cwd=MCP, check=True)
        subprocess.run(['corepack', 'pnpm', 'run', 'build'], cwd=MCP, check=True)
    for side, port in [('server', 25599), ('client', 25601)]:
        run = BASE / side
        (run / 'mods').mkdir(parents=True, exist_ok=True)
        (run / 'config').mkdir(exist_ok=True)
        for mod in LOCK['mods']:
            shutil.copyfile(CACHE / 'mods' / mod['filename'], run / 'mods' / mod['filename'])
        config = run / 'config/mcpfabric.config.json'
        if not config.exists():
            config.write_text(json.dumps(dict(host='127.0.0.1', port=port, token=secrets.token_hex(32),
                requireAuth=True, callTimeoutMs=30000, worldIdKey=secrets.token_hex(32),
                enableWorldWrite=True, enableCommands=True, enablePlayerControl=True, enableVision=True), indent=2))
            config.chmod(0o600)
        validate_config(side)
        (run / 'eula.txt').write_text(eula)
        spark = run / 'config/spark/config.json'
        if not spark.exists():
            spark.parent.mkdir(parents=True, exist_ok=True)
            spark.write_text(json.dumps({'backgroundProfiler': False}, indent=2))
        if side == 'client':
            options = run / 'options.txt'
            lines = options.read_text().splitlines() if options.exists() else []
            lines = [s for s in lines if not s.startswith('onboardAccessibility:')]
            options.write_text('\n'.join(lines + ['onboardAccessibility:false']) + '\n')
    server = BASE / 'server'
    properties = server / 'server.properties'
    if not properties.exists():
        properties.write_text('\n'.join([
            '# Isolated HardRush development only', 'server-ip=127.0.0.1', 'server-port=25586',
            'online-mode=false', 'enforce-secure-profile=false', 'white-list=true', 'enforce-whitelist=true',
            'enable-rcon=false', 'enable-query=false', 'level-name=debug-world', 'level-seed=12345',
            'gamemode=creative', 'difficulty=normal', 'max-players=4', 'spawn-protection=0',
            'view-distance=4', 'simulation-distance=4', 'pause-when-empty-seconds=0',
            'motd=HardRush isolated debug', '']))
    identities = [dict(name=name, uuid=str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(), version=3)))
                  for name in ('HRDebug', 'HRDevA', 'HRDevB')]
    for name in ('whitelist.json', 'ops.json'):
        path = server / name
        existing = json.loads(path.read_text()) if path.exists() else []
        for identity in identities:
            matches = [entry for entry in existing if entry['uuid'] == identity['uuid'] or entry['name'] == identity['name']]
            if matches:
                if any(entry['uuid'] != identity['uuid'] or entry['name'] != identity['name'] for entry in matches):
                    raise RuntimeError('Unexpected isolated development identity in ' + name)
                continue
            existing.append(identity if name == 'whitelist.json' else dict(identity, level=4, bypassesPlayerLimit=False))
        path.write_text(json.dumps(existing, indent=2) + '\n')
    # Server GameTest and in-process client test servers use a separate EULA file.
    for side in ['gameTest', 'clientGameTest']:
        run = ROOT / 'platforms/mc263/build/run' / side
        run.mkdir(parents=True, exist_ok=True)
        (run / 'eula.txt').write_text(eula)
    print('Debug tools prepared; credentials remain in .local/debug/*/config.', flush=True)


def validate_config(side):
    cfg = json.loads((BASE / side / 'config/mcpfabric.config.json').read_text())
    expected = 25599 if side == 'server' else 25601
    if cfg['host'] != '127.0.0.1' or cfg['port'] != expected or not cfg['requireAuth'] or not cfg['token']:
        raise RuntimeError('Unexpected bridge endpoint/auth configuration for ' + side)
    return cfg


def rpc(side, method, params=None):
    cfg = validate_config(side)
    req = urllib.request.Request(f'http://127.0.0.1:{cfg["port"]}/rpc',
        json.dumps(dict(jsonrpc='2.0', id=1, method=method, params=params or {})).encode(),
        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + cfg['token']})
    with urllib.request.urlopen(req, timeout=60) as response:
        result = json.load(response)
    if 'error' in result:
        raise RuntimeError(json.dumps(result['error']))
    return result['result']


def listening(port):
    with socket.socket() as sock:
        sock.settimeout(.2)
        return sock.connect_ex(('127.0.0.1', port)) == 0


def process(side):
    path = BASE / side / 'process.json'
    if not path.exists():
        return None
    data = json.loads(path.read_text())
    command = subprocess.run(['ps', '-p', str(data['pid']), '-o', 'command='], capture_output=True, text=True).stdout.strip()
    # Verify identity before signaling; stale or reused PID must never affect other applications.
    if command != data['command']:
        return None
    return data


def start(side):
    if side == 'client': start('server')
    prepare()
    if process(side):
        status = rpc(side, 'info.status')
        if side == 'client': rpc(side, 'player.getState')
        elif not status.get('serverPresent'): raise RuntimeError('Managed server is not ready; inspect launch.log')
        print(side + ' already managed/ready')
        return
    if any(listening(p) for p in PORTS[side]):
        raise RuntimeError('Debug port already occupied for ' + side)
    task = ':platforms:mc263:runDebug' + side.title()
    log = BASE / side / 'launch.log'
    with log.open('w') as output:
        p = subprocess.Popen([str(ROOT / 'gradlew'), task, '--console=plain'],
            cwd=ROOT, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    time.sleep(.5)
    command = subprocess.run(['ps', '-p', str(p.pid), '-o', 'command='], capture_output=True, text=True).stdout.strip()
    (BASE / side / 'process.json').write_text(json.dumps(dict(pid=p.pid, command=command)))
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if p.poll() is not None:
            raise RuntimeError('Launch exited; inspect ' + str(log))
        try:
            status = rpc(side, 'info.status')
            if side == 'client':
                rpc(side, 'player.getState')
            if side == 'client' or (status.get('serverPresent') and listening(25586)):
                print(json.dumps(status, indent=2))
                return
        except (OSError, RuntimeError):
            pass
        time.sleep(1)
    raise RuntimeError('Startup timed out; inspect ' + str(log))


def stop(side):
    data = process(side)
    if not data:
        print('No owned ' + side + ' process to stop')
        return
    if side == 'server':
        try:
            rpc(side, 'command.run', {'command': 'stop'})
        except (OSError, RuntimeError):
            pass
    else:
        os.killpg(data['pid'], signal.SIGTERM)
    for _ in range(30):
        if not any(listening(p) for p in PORTS[side]):
            break
        time.sleep(1)
    if any(listening(p) for p in PORTS[side]):
        raise RuntimeError('Shutdown did not finish; inspect the owned process before retrying')
    (BASE / side / 'process.json').unlink(missing_ok=True)
    print(side + ' stopped')


def configure_mcp(register=False):
    prepare()
    node = shutil.which('node')
    if not node:
        raise RuntimeError('Node.js >=22.16 is required')
    text = '# Generated by scripts/debug-tools.py configure-mcp; tokens are read at launch.\n'
    for side in ['server', 'client']:
        text += (f'\n[mcp_servers.hardrush_{side}]\ncommand = {json.dumps(node)}\n'
                 f'args = [{json.dumps(str(ROOT / "scripts/minecraft-mcp.mjs"))}, "{side}"]\n'
                 f'cwd = {json.dumps(str(ROOT))}\nstartup_timeout_sec = 30\ntool_timeout_sec = 60\n')
    target = ROOT / '.codex/config.toml'
    if target.exists() and not target.read_text().startswith('# Generated by scripts/debug-tools.py configure-mcp;'):
        raise RuntimeError('Existing project config requires manual merge; refusing to overwrite ' + str(target))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text)
    print('Project MCP configuration: ' + str(target))
    if register:
        for side in ['server', 'client']:
            name = 'hardrush_' + side
            command = [node, str(ROOT / 'scripts/minecraft-mcp.mjs'), side]
            existing = subprocess.run(['codex', 'mcp', 'get', name, '--json'], capture_output=True, text=True)
            if existing.returncode == 0:
                transport = json.loads(existing.stdout)['transport']
                if transport.get('command') != command[0] or transport.get('args') != command[1:]:
                    raise RuntimeError('Existing MCP entry differs; refusing to replace ' + name)
                print('MCP entry already registered: ' + name)
            elif 'No MCP server named' in existing.stderr:
                subprocess.run(['codex', 'mcp', 'add', name, '--', *command], check=True)
            else:
                raise RuntimeError(existing.stderr)


def profile(side, seconds):
    name = 'spark' if side == 'server' else 'sparkc'
    method = 'command.run' if side == 'server' else 'chat.send'
    def send(command):
        return rpc(side, method, {'command': command} if side == 'server' else {'message': '/' + command})
    started = time.time()
    print(json.dumps(send(name + ' profiler start --thread * --force-java-sampler')))
    time.sleep(seconds)
    print(json.dumps(send(name + ' profiler stop --save-to-file')))
    # spark command completion is asynchronous; require an actual nonempty file.
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        files = [p for p in (BASE / side / 'config/spark').glob('*.sparkprofile') if p.stat().st_mtime >= started]
        if files:
            latest = max(files, key=lambda p: p.stat().st_mtime)
            if latest.stat().st_size > 0:
                print(str(latest))
                java_home = os.environ.get('HARDRUSH_JAVA_HOME') or os.environ.get('JAVA_HOME') or (ROOT / '.local/toolchains/java-home.txt').read_text().strip()
                summary = subprocess.run([str(Path(java_home) / 'bin/java'), '--class-path',
                    str(CACHE / 'mods/spark-1.10.187-fabric.jar'), str(ROOT / 'scripts/SparkProfileSummary.java'), str(latest)],
                    check=True, capture_output=True, text=True).stdout
                latest.with_suffix('.txt').write_text(summary)
                print(summary)
                return
        time.sleep(.5)
    raise RuntimeError('No spark profile produced')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['prepare', 'configure-mcp', 'register-mcp', 'start', 'stop', 'status', 'rpc', 'command', 'profile'])
    p.add_argument('--side', choices=['server', 'client'], default='server')
    p.add_argument('--method', default='info.status')
    p.add_argument('--params', default='{}')
    p.add_argument('--command')
    p.add_argument('--seconds', type=int, choices=range(1, 61), default=10)
    a = p.parse_args()
    if a.action == 'prepare': prepare()
    elif a.action == 'configure-mcp': configure_mcp()
    elif a.action == 'register-mcp': configure_mcp(register=True)
    elif a.action == 'start': start(a.side)
    elif a.action == 'stop': stop(a.side)
    elif a.action == 'profile': profile(a.side, a.seconds)
    else:
        if a.action == 'command' and not a.command: p.error('--command is required')
        method = 'command.run' if a.action == 'command' else a.method
        params = {'command': a.command} if a.action == 'command' else json.loads(a.params)
        print(json.dumps(rpc(a.side, method, params), indent=2))


if __name__ == '__main__':
    main()
