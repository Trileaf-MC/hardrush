#!/usr/bin/env python3
"""Repeat native-client P0 lifecycle checks on the named local test identities.

No client mod or input automation. Death uses vanilla immediate respawn, reconnect
relaunches the isolated official app, and every refresh waits for network acks.
The caller must finish the human test before running this disruptive local fixture.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('hardrush_manual', ROOT / 'scripts/p0-appearance.py')
manual = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manual)
OUT = ROOT / '.local/research/observer-appearance-continuation/native-cycles'
OWNED = {}
RECORDS = []


def native_process(name):
    binary = ROOT / '.local/vanilla/26.3/apps' / ('HardRushProbe' + name[-1] + '.app') / 'Contents/MacOS' / ('HardRushProbe' + name[-1])
    matches = []
    for line in subprocess.check_output(['ps', '-axo', 'pid=,command='], text=True).splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) == 2 and parts[1].startswith(str(binary) + ' '):
            if '--username ' + name not in parts[1] or '--quickPlayMultiplayer 127.0.0.1:25586' not in parts[1]:
                raise RuntimeError('Unexpected probe launch; refusing to stop it: ' + str(binary))
            matches.append((int(parts[0]), parts[1]))
    if len(matches) > 1:
        raise RuntimeError('Multiple native apps for ' + name)
    return matches[0] if matches else None


def wait_until(predicate, timeout=40):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = predicate()
            if last:
                return last
        except RuntimeError as error:
            last = str(error)
        time.sleep(.15)
    raise RuntimeError('Native-client timeout; last=' + str(last))


def stop_client(name):
    process = native_process(name)
    if process:
        pid, expected = process
        actual = subprocess.check_output(['ps', '-p', str(pid), '-o', 'command='], text=True).strip()
        if actual != expected:
            raise RuntimeError('Probe PID identity changed')
        os.kill(pid, signal.SIGTERM)
        wait_until(lambda: native_process(name) is None)
    managed = OWNED.pop(name, None)
    if managed:
        managed.wait(timeout=20)
    wait_until(lambda: all(p['name'] != name for p in manual.bridge.rpc('server', 'players.list')['players']))


def start_client(name):
    if native_process(name):
        raise RuntimeError('Probe is already running: ' + name)
    log = OUT / (name + '-' + str(time.time_ns()) + '.log')
    with log.open('w') as output:
        process = subprocess.Popen([sys.executable, str(ROOT / 'scripts/p0-client.py'), 'launch-app', name,
                                    '--server', '127.0.0.1:25586'], cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
    OWNED[name] = process
    wait_until(lambda: next((p for p in manual.bridge.rpc('server', 'players.list')['players'] if p['name'] == name), None), 60)
    return stable(name)


def snapshot(name):
    result = manual.command('hardrushdebug snapshot ' + name)
    return json.loads(result['output'][0])


def stable(name):
    def acknowledged():
        data = snapshot(name)
        if data['loaded'] and not data['teleportPending'] and data['alive'] and 'pending-safe-point' not in data['appearance']:
            return data
    return wait_until(acknowledged)


def record(event, cycle, expected_kind=None, baseline=None, expected_dimension=None):
    data = stable('HRProbeA')
    if data['invisible'] or not data['nativeCamera'] or data['matchingEntities'] != 1:
        raise RuntimeError('Native visibility/camera/entity invariant failed: ' + json.dumps(data))
    expected = 'disguise=' + (expected_kind or 'off') + ';'
    if not data['appearance'].startswith(expected):
        raise RuntimeError('Appearance selection was lost: ' + json.dumps(data))
    if baseline:
        for key in ('uuid', 'team', 'permission', 'attributes', 'equipment'):
            if data[key] != baseline[key]:
                raise RuntimeError(event + ' changed ' + key + ': ' + json.dumps(data))
    if expected_dimension and not data['dimension'].endswith('/' + expected_dimension):
        raise RuntimeError('Wrong round dimension: ' + json.dumps(data))
    RECORDS.append({'event': event, 'cycle': cycle, 'state': data})
    (OUT / 'results.json').write_text(json.dumps({'status': 'running', 'records': RECORDS}, indent=2) + '\n')
    return data


def run(reuse_ready=False, phase='all'):
    manual.ready()
    OUT.mkdir(parents=True, exist_ok=True)
    if phase == 'dimensions':
        previous = json.loads((OUT / 'results.json').read_text())['records']
        from collections import Counter
        counts = Counter(entry['event'] for entry in previous)
        if counts['apply'] != 60 or counts['death/native-client-respawn'] != 20 or counts['disconnect/native-login'] != 20:
            raise RuntimeError('Earlier native phases are incomplete; cannot resume dimensions')
        RECORDS.extend(entry for entry in previous if not entry['event'].startswith('native-dimension-')
                       and entry['event'] != 'clear-after-dimension')
        reuse_ready = True
    # Reload only the owned local server, after disconnecting the two test apps.
    if not reuse_ready:
        stop_client('HRProbeA'); stop_client('HRProbeB')
        subprocess.run([sys.executable, str(ROOT / 'scripts/debug-tools.py'), 'stop', '--side', 'server'], check=True)
        subprocess.run([sys.executable, str(ROOT / 'scripts/debug-tools.py'), 'start', '--side', 'server'], check=True)
        start_client('HRProbeA'); start_client('HRProbeB')
    else:
        for name in manual.NAMES:
            if not native_process(name):
                raise RuntimeError('Ready native app missing: ' + name)
            stable(name)
    manual.prepare()
    manual.command('hardrushp0 clear HRProbeA')
    baseline = stable('HRProbeA')
    print('Native clients ready; baseline captured', flush=True)
    immediate = manual.command('gamerule immediate_respawn')['output'][0].endswith('true')
    try:
        for cycle in range(20 if phase == 'all' else 0):
            for kind in ('zombie', 'skeleton', 'creeper'):
                manual.command('hardrushp0 appearance HRProbeA ' + kind)
                record('apply', cycle, kind, baseline)
                manual.command('hardrushp0 clear HRProbeA')
                record('clear', cycle, baseline=baseline)
            if cycle % 5 == 4:
                print('Native apply/clear: ' + str(cycle + 1) + '/20 per kind', flush=True)
        manual.command('gamerule immediate_respawn true')
        for cycle in range(20 if phase == 'all' else 0):
            kind = ('zombie', 'skeleton', 'creeper')[cycle % 3]
            manual.command('hardrushp0 appearance HRProbeA ' + kind)
            before = stable('HRProbeA')
            manual.command('kill HRProbeA')
            wait_until(lambda: (data := snapshot('HRProbeA'))['instance'] != before['instance'] and data['alive'])
            record('death/native-client-respawn', cycle, kind, baseline)
            manual.command('hardrushp0 clear HRProbeA')
            record('clear-after-death', cycle, baseline=baseline)
        print('Native death/automatic respawn: 20/20' + (' (retained earlier results)' if phase != 'all' else ''), flush=True)
        manual.command('gamerule immediate_respawn ' + str(immediate).lower())
        for cycle in range(20 if phase == 'all' else 0):
            kind = ('zombie', 'skeleton', 'creeper')[cycle % 3]
            manual.command('hardrushp0 appearance HRProbeA ' + kind)
            before = stable('HRProbeA')
            stop_client('HRProbeA')
            start_client('HRProbeA')
            data = record('disconnect/native-login', cycle, kind, baseline)
            if data['instance'] == before['instance']:
                raise RuntimeError('Reconnect did not create a new server player')
            manual.command('hardrushp0 clear HRProbeA')
            record('clear-after-reconnect', cycle, baseline=baseline)
            print('Native reconnect: ' + str(cycle + 1) + '/20', flush=True)
        for name in manual.NAMES:
            manual.command('gamemode creative ' + name)
        # Vanilla creative mode changes block/entity interaction range. Compare
        # dimension transfers to that mode's baseline, not the survival baseline.
        def creative_baseline():
            data = stable('HRProbeA')
            if data['attributes'].get('minecraft:block_interaction_range') == 5.0 and data['attributes'].get('minecraft:entity_interaction_range') == 5.0:
                return data
        dimension_baseline = wait_until(creative_baseline)
        (OUT / 'dimension-baseline.json').write_text(json.dumps(dimension_baseline, indent=2) + '\n')
        for cycle in range(20):
            kind = ('zombie', 'skeleton', 'creeper')[cycle % 3]
            manual.command('hardrushp0 appearance HRProbeA ' + kind)
            for dimension in ('overworld', 'nether', 'end'):
                for name in manual.NAMES:
                    manual.command('hardrushp0 world enter ' + name + ' ' + dimension)
                    stable(name)
                record('native-dimension-' + dimension, cycle, kind, dimension_baseline, dimension)
            manual.command('hardrushp0 clear HRProbeA')
            record('clear-after-dimension', cycle, baseline=dimension_baseline)
            if cycle % 5 == 4:
                print('Native dimension loops: ' + str(cycle + 1) + '/20', flush=True)
        (OUT / 'results.json').write_text(json.dumps({'status': 'passed', 'counts': {
            'applyClearPerKind': 20, 'applyClearTotal': 60, 'deathNativeClientRespawn': 20,
            'nativeReconnect': 20, 'dimensionCycles': 20, 'dimensionTransfersPerClient': 60}, 'records': RECORDS}, indent=2) + '\n')
        print('Native lifecycle checks passed', flush=True)
    finally:
        manual.command('gamerule immediate_respawn ' + str(immediate).lower())
        for name in manual.NAMES:
            present = {p['name'] for p in manual.bridge.rpc('server', 'players.list')['players']}
            if name in present:
                manual.command('hardrushp0 clear ' + name)
                manual.command('execute in minecraft:overworld run tp ' + name + ' 64.5 150 ' + ('64.5' if name == 'HRProbeA' else '61.5') + ' 0 0')
                manual.command('gamemode survival ' + name)
                stable(name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', action='store_true', required=True,
                        help='Explicitly restart the named test apps/server and run disruptive local checks.')
    parser.add_argument('--reuse-ready', action='store_true', help='Use already reloaded managed probes with the read-only snapshot command.')
    parser.add_argument('--phase', choices=('all', 'dimensions'), default='all', help='Resume only dimension checks when earlier native phases are complete.')
    args = parser.parse_args()
    run(args.reuse_ready, args.phase)
