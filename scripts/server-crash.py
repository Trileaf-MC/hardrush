#!/usr/bin/env python3
"""SIGKILL a headless dedicated server at a real SQLite boundary; recover in two fresh JVMs."""

from pathlib import Path
import argparse
import base64
import importlib.util
import hashlib
import json
import os
import re
import signal
import sqlite3
import struct
import subprocess
import sys
import time
import uuid

SPEC = importlib.util.spec_from_file_location('server_restart', Path(__file__).with_name('server-restart.py'))
RESTART = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RESTART)
SCENARIOS = ('AWARD', 'CONSUME', 'TERMINAL', 'PROFILE')
BOUNDARIES = ('SNAPSHOT', 'INDEX', 'COMMIT')
PROFILE_BOUNDARIES = ('PREPARED', 'SUSPENDED', 'APPLIED', 'VERIFIED')
BASE = RESTART.ROOT / '.local/server-crash'


def retained(directory):
    world = directory / 'world'
    result = {}
    for relative in ('hardrush/round-worlds', 'hardrush/profiles', 'dimensions/hardrush'):
        candidate = world / relative
        for ancestor in (candidate, *candidate.parents):
            if ancestor.is_symlink():
                raise RuntimeError(f'Unexpected linked fixture file: {ancestor}')
        for path in sorted(candidate.rglob('*')):
            if path.is_symlink():
                raise RuntimeError(f'Unexpected linked fixture file: {path}')
            # The persistent shared camp remains loaded/ticking in quarantine. Its native region
            # timestamps, POI and status signs are mutable; it is NOT an old round-owned world.
            # Both native recovery fixtures separately require builtBlocks()==0 and the saved
            # lapis terrain sentinel. All round worlds and profile bytes remain exact below.
            if path.is_relative_to(world / 'dimensions/hardrush/lobby'):
                continue
            if path.is_file():
                if path.suffix == '.bin':
                    if not 36 < path.stat().st_size <= 16 * 1024 * 1024 + 36:
                        raise RuntimeError(f'Corrupt profile journal envelope: {path}')
                    data = path.read_bytes()
                    if struct.unpack('>i', data[:4])[0] != len(data) - 36 or hashlib.sha256(data[36:]).digest() != data[4:36]:
                        raise RuntimeError(f'Corrupt profile journal envelope: {path}')
                result[str(path.relative_to(world))] = RESTART.sha256(path)
    layout = world / 'hardrush/lobby-layout.txt'
    if layout.is_symlink() or not layout.is_file() or not layout.read_text().endswith('COMPLETE\n'):
        raise RuntimeError('Shared camp must retain its complete ownership manifest')
    result['hardrush/lobby-layout.txt'] = RESTART.sha256(layout)
    if not any(name.endswith('.mca') for name in result) or not any(name.endswith('.bin') for name in result):
        raise RuntimeError('Missing real native region files or durable player journal')
    return result


def database_evidence(directory, marker):
    database = directory / 'world/hardrush/rounds.sqlite'
    with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as connection:
        integrity = connection.execute('PRAGMA integrity_check').fetchall()
        if integrity != [('ok',)] or connection.execute('PRAGMA foreign_key_check').fetchall():
            raise RuntimeError('SQLite integrity/foreign key check failed')
        row = connection.execute('SELECT s.payload, e.payload_digest FROM round_snapshots s JOIN store_events e ON e.sequence=s.revision ORDER BY s.revision DESC LIMIT 1').fetchone()
        if row is None or hashlib.sha256(row[0]).digest() != row[1] or row[0] != base64.b64decode(marker['checkpoint'], validate=True):
            raise RuntimeError('Killed writer differs from the exact committed checkpoint')
        header = RESTART.read_snapshot_header(row[0])
        if header['cleanShutdown'] or header['roundId'] != marker['roundId']:
            raise RuntimeError('Writer must be dirty and retain the original round')
        spill_rows = connection.execute('SELECT count(*) FROM crash_spill').fetchone()[0] if marker['scenario'] != 'PROFILE' and marker['boundary'] != 'COMMIT' else 0
        if spill_rows != 0:
            raise RuntimeError('Uncommitted WAL spill survived rollback')
        before = RESTART.read_snapshot_header(base64.b64decode(marker['before'], validate=True))
        if header['version'] != before['version'] + (marker['boundary'] == 'COMMIT'):
            raise RuntimeError('Incorrect transaction boundary')
        result = {'integrityCheck': 'ok', 'foreignKeyCheck': 'ok', 'uncommittedSpillRows': spill_rows, 'snapshot': header,
                'historyCount': connection.execute('SELECT count(*) FROM history_rounds').fetchone()[0],
                'recordDigests': {table: [list(row) for row in connection.execute(f'SELECT round_id, hex(payload_digest) FROM {table} ORDER BY round_id, hex(payload_digest)')]
                                  for table in ('history_rounds', 'participant_records')},
                'tableCounts': {table: connection.execute(f'SELECT count(*) FROM {table}').fetchone()[0]
                                for table in ('store_events', 'evidence_events', 'round_terminals', 'participant_records', 'progress_events')}}
        if marker['scenario'] == 'PROFILE':
            result['profile'] = profile_evidence(directory, marker, header)
        return result


def profile_evidence(directory, marker, header):
    player = str(uuid.UUID(marker['playerId']))
    root = directory / 'world'
    journal = root / 'hardrush/profiles' / player

    def payload(path):
        if path.is_symlink() or not path.is_file() or not 36 < path.stat().st_size <= 16 * 1024 * 1024 + 36:
            raise RuntimeError('Invalid profile envelope')
        data = path.read_bytes()
        if struct.unpack('>i', data[:4])[0] != len(data) - 36 or hashlib.sha256(data[36:]).digest() != data[4:36]:
            raise RuntimeError('Corrupt profile envelope')
        return data[36:]

    transition = payload(journal / 'transition.bin')
    if len(transition) != 29:
        raise RuntimeError('Invalid profile transition length')
    format_version, round_bytes, sequence, phase = struct.unpack('>i16sqB', transition)
    expected_phase = {'PREPARED': 0, 'SUSPENDED': 0, 'APPLIED': 1, 'VERIFIED': 2}[marker['boundary']]
    if format_version != 1 or str(uuid.UUID(bytes=round_bytes)) != header['roundId'] or sequence != header['sequence'] or phase != expected_phase:
        raise RuntimeError('Profile journal did not stop at the requested write boundary')
    archived = journal / 'archives/pre-hardrush.bin'
    payload(archived)
    target = journal / f"suspended-{header['roundId']}.bin"
    if marker['boundary'] == 'PREPARED':
        if target.exists() or target.is_symlink():
            raise RuntimeError('PREPARED unexpectedly contains reset output')
        target_digest = None
    else:
        payload(target)
        target_digest = RESTART.sha256(target)
    native = root / 'players/data' / f'{player}.dat'
    if native.is_symlink() or not native.is_file():
        raise RuntimeError('Missing original native player checkpoint')
    return {'phase': phase, 'sequence': sequence, 'roundId': header['roundId'],
            'archiveSha256': RESTART.sha256(archived), 'targetSha256': target_digest, 'nativeSha256': RESTART.sha256(native)}


def profile_barrier(spec, directory, process, boundary, timeout):
    """Attach only to the random loopback JDWP endpoint printed by this exact child."""
    baseline = json.loads((directory / 'profile-baseline.json').read_text())
    if any(baseline.get(k) != v for k, v in (('pid', process.pid), ('boundary', boundary),
            ('scenario', 'PROFILE'), ('operation', 'write'))):
        raise RuntimeError('Wrong profile debugger baseline identity')
    ports = re.findall(r'^Listening for transport dt_socket at address: (\d+)\s*$',
                       (directory / 'write.log').read_text(errors='replace'), re.MULTILINE)
    if len(ports) != 1 or not 0 < int(ports[0]) < 65536:
        raise RuntimeError('Missing unique owned JDWP endpoint')
    command = [spec['java'], '--add-modules', 'jdk.jdi', str(RESTART.ROOT / 'scripts/ProfileCrashBarrier.java'),
               str(directory), ports[0], str(process.pid), str(timeout)]
    with (directory / 'profile-debugger.log').open('wb') as output:
        return subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                start_new_session=True)


def profile_marker(directory, pid, boundary):
    hit = json.loads((directory / 'profile-hit.json').read_text())
    if any(hit.get(k) != v for k, v in (('pid', pid), ('boundary', boundary), ('scenario', 'PROFILE'),
            ('status', 'KILL_READY'), ('operation', 'write'), ('suspendPolicy', 'ALL'), ('thread', 'hardrush-profiles'))):
        raise RuntimeError('Wrong or stale profile debugger hit')
    marker = json.loads((directory / 'profile-baseline.json').read_text())
    if marker.get('pid') != pid or marker.get('boundary') != boundary or marker.get('scenario') != 'PROFILE':
        raise RuntimeError('Wrong profile debugger baseline identity')
    expected_method = 'prepare' if boundary == 'PREPARED' else 'suspend' if boundary == 'SUSPENDED' else 'advance'
    if hit.get('method') != expected_method:
        raise RuntimeError('Profile debugger hit the wrong journal method')
    return dict(marker, **hit)


def launch(spec, directory, scenario, boundary, operation, attempt, timeout):
    label = 'write' if operation == 'write' else f'recover-{attempt}'
    properties = ('-Dhardrush.test.restart.', '-Dhardrush.round.initialMode=', '-Dhardrush.storage.fullCheckpointIntervalSeconds=', '-Dhardrush.test.crash.', '-Dhardrush.test.profileCrash.', '-agentlib:jdwp=')
    args = [arg for arg in spec['jvmArgs'] if not arg.startswith(properties)]
    args += [f'-Dhardrush.test.restart.phase=RUNNING', f'-Dhardrush.test.restart.operation={operation}',
             f'-Dhardrush.test.restart.directory={directory}', '-Dhardrush.round.initialMode=hardcore',
             '-Dhardrush.storage.fullCheckpointIntervalSeconds=0']
    if scenario == 'PROFILE':
        args += [f'-Dhardrush.test.profileCrash.boundary={boundary}']
        if operation == 'write':
            args += ['-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0']
    else:
        args += [f'-Dhardrush.test.crash.scenario={scenario}', f'-Dhardrush.test.crash.boundary={boundary}']
    command = [spec['java'], *args, '-cp', os.pathsep.join(spec['classpath']), spec['mainClass'], *spec['programArgs']]
    env = dict(os.environ, **{key: str(value) for key, value in spec.get('environment', {}).items()})
    evidence = {'status': 'RUNNING', 'operation': operation, 'scenario': scenario, 'boundary': boundary,
                'command': command, 'log': f'{label}.log'}
    evidence_path = directory / f'{label}-evidence.json'
    marker_path = directory / ('kill-ready.json' if operation == 'write' else 'recover-result.json')
    if marker_path.exists() or marker_path.is_symlink():
        raise RuntimeError('Refusing stale fixture marker before launching a JVM')
    started = time.monotonic()
    debugger = None
    with (directory / evidence['log']).open('wb') as output:
        process = subprocess.Popen(command, cwd=directory, env=env, stdin=subprocess.DEVNULL,
                                   stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        evidence['pid'] = process.pid
        RESTART.write_json(evidence_path, evidence)
        try:
            while process.poll() is None and not marker_path.is_file():
                if scenario == 'PROFILE' and operation == 'write':
                    if debugger is None and (directory / 'profile-arm.properties').is_file():
                        debugger = profile_barrier(spec, directory, process, boundary, timeout)
                        evidence['debuggerPid'] = debugger.pid
                    if (directory / 'profile-hit.json').is_file():
                        RESTART.write_json(marker_path, profile_marker(directory, process.pid, boundary))
                    elif debugger is not None and debugger.poll() is not None:
                        raise RuntimeError('Profile debugger exited before the requested boundary; see profile-debugger.log')
                if time.monotonic() - started > timeout:
                    raise RuntimeError('Fixture timed out before its verified boundary')
                time.sleep(.1)
            if operation == 'write':
                if process.poll() is not None:
                    raise RuntimeError('Writer exited before parent-issued SIGKILL')
                marker = json.loads(marker_path.read_text())
                if any(marker.get(k) != v for k, v in (('status', 'KILL_READY'), ('pid', process.pid),
                        ('scenario', scenario), ('boundary', boundary), ('operation', operation))):
                    raise RuntimeError('Wrong or stale kill boundary marker')
                # Only kill the child just launched, never a server located by name or port.
                os.kill(process.pid, signal.SIGKILL)
                process.wait(timeout=20)
                if process.returncode != -signal.SIGKILL:
                    raise RuntimeError('Writer did not die from the requested SIGKILL')
                evidence.update(signal='SIGKILL', fixture=marker, storage=database_evidence(directory, marker))
            else:
                process.wait(timeout=max(1, timeout - (time.monotonic() - started)))
                if process.returncode != 0 or not marker_path.is_file():
                    raise RuntimeError('Recovery did not complete a normal dedicated-server shutdown')
                marker = json.loads(marker_path.read_text())
                if any(marker.get(k) != v for k, v in (('status', 'PASS'), ('pid', process.pid),
                        ('scenario', scenario), ('boundary', boundary), ('operation', operation))):
                    raise RuntimeError('Recovery fixture failed or mismatched: ' + str(marker.get('error', marker.get('status'))))
                if RESTART.SAVE_FAILURE.search((directory / evidence['log']).read_text(errors='replace')):
                    raise RuntimeError('Native save/close failed during recovery')
                evidence['fixture'] = marker
                marker_path.rename(directory / f'{label}-result.json')
            evidence.update(status='PASS', exitCode=process.returncode,
                            elapsedSeconds=round(time.monotonic() - started, 3))
        except BaseException as error:
            evidence.update(status='FAIL', failure=str(error), cleanup=RESTART.cleanup_failed_process(process))
            RESTART.write_json(evidence_path, evidence)
            raise
        finally:
            if debugger is not None:
                try:
                    debugger.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    evidence['debuggerCleanup'] = RESTART.cleanup_failed_process(debugger)
                evidence['debuggerExitCode'] = debugger.returncode
            RESTART.write_json(evidence_path, evidence)
    print(f'{scenario}/{boundary}/{label}: PASS (JVM {process.pid}, exit {process.returncode})', flush=True)
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=SCENARIOS, action='append')
    parser.add_argument('--boundary', choices=(*BOUNDARIES, *PROFILE_BOUNDARIES), action='append')
    parser.add_argument('--timeout', type=int, default=420)
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error('--timeout must be positive')
    for scenario in args.scenario or ():
        supported = PROFILE_BOUNDARIES if scenario == 'PROFILE' else BOUNDARIES
        if args.boundary and not any(boundary in supported for boundary in args.boundary):
            parser.error(f'No requested boundary applies to {scenario}')
    eula = RESTART.accepted_eula()
    BASE.mkdir(parents=True, exist_ok=True)
    run = BASE / str(uuid.uuid4()); run.mkdir()
    summary = {'schema': 1, 'status': 'RUNNING', 'runDirectory': str(run), 'scenarios': [],
               'verification': 'Headless dedicated servers, embedded native connections, actual parent SIGKILL; no real client.'}
    RESTART.write_json(run / 'result.json', summary)
    print(f'Isolated server crash evidence: {run}', flush=True)
    try:
        spec = RESTART.build_launch(run, args.timeout)
        sources = [Path(__file__).resolve(), Path(__file__).with_name('server-restart.py').resolve(),
                   Path(__file__).with_name('ProfileCrashBarrier.java').resolve(),
                   *sorted((RESTART.ROOT / 'platforms/mc263/src/gametest/java/dev/hardrush/gametest').glob('*Regression.java'))]
        classes = sorted((RESTART.ROOT / 'platforms/mc263/build/classes/java/gametest/dev/hardrush/gametest').glob('*Regression*.class'))
        RESTART.write_json(run / 'launch-fingerprints.json', {str(p.relative_to(RESTART.ROOT)): RESTART.sha256(p) for p in (*sources, *classes)})
        for scenario in dict.fromkeys(args.scenario or SCENARIOS):
            supported = PROFILE_BOUNDARIES if scenario == 'PROFILE' else BOUNDARIES
            for boundary in (b for b in dict.fromkeys(args.boundary or supported) if b in supported):
                directory = run / f'{scenario}-{boundary}'
                RESTART.prepare_scenario(directory, eula)
                writer = launch(spec, directory, scenario, boundary, 'write', 0, args.timeout)
                inventory = retained(directory)
                RESTART.write_json(directory / 'retained-files.json', inventory)
                pids = [writer['pid']]
                for attempt in (1, 2):
                    reader = launch(spec, directory, scenario, boundary, 'recover', attempt, args.timeout)
                    pids.append(reader['pid'])
                    if database_evidence(directory, writer['fixture']) != writer['storage']:
                        raise RuntimeError('Recovery changed durable events, records or the original terminal')
                    if retained(directory) != inventory:
                        raise RuntimeError('Quarantine changed or deleted retained worlds/profile evidence')
                if len(set(pids)) != 3:
                    raise RuntimeError('Every recovery requires a different JVM PID')
                summary['scenarios'].append({'scenario': scenario, 'boundary': boundary, 'status': 'PASS',
                                             'pids': pids, 'retainedFileCount': len(inventory)})
                RESTART.write_json(run / 'result.json', summary)
        summary['status'] = 'PASS'
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.TimeoutExpired, sqlite3.Error, KeyboardInterrupt) as error:
        summary.update(status='FAIL', failure=str(error))
        print(f'FAIL: {error}; evidence retained at {run}', file=sys.stderr, flush=True)
    RESTART.write_json(run / 'result.json', summary)
    return 0 if summary['status'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
