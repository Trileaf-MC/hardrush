#!/usr/bin/env python3
"""P1 normal-stop recovery matrix using separate, headless dedicated-server JVMs.

The fixture owns its assertions and requests a normal server stop. A zero JVM
exit and its SERVER_STOPPED PASS result are both required before storage checks.
Forced termination only cleans up a failed attempt; it is never recovery proof.
"""

from pathlib import Path
import argparse
import base64
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

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / '.local/p1-server-restart'
PHASES = ('PREPARING', 'WAITING', 'COUNTDOWN', 'RUNNING', 'RESCUE', 'SETTLING', 'CELEBRATING', 'ROTATING')
RESTART_PROPERTIES = ('phase', 'operation', 'directory')
SAVE_FAILURE = re.compile(
    r'Exception closing the level|Failed to unlock level|Failed to save|Could not save|'
    r'Error saving|Exception saving|Couldn.t save|Failed to store chunk', re.IGNORECASE)


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def sha256(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def accepted_eula():
    source = ROOT / '.local/server-p0/eula.txt'
    if not source.is_file() or source.is_symlink():
        raise RuntimeError(f'Missing accepted EULA: {source}')
    contents = source.read_text(encoding='utf-8')
    if 'eula=true' not in [line.strip() for line in contents.splitlines()]:
        raise RuntimeError(f'Review and accept the Minecraft EULA in {source} before running.')
    return contents


def prepare_scenario(directory, eula):
    directory.mkdir()
    (directory / 'eula.txt').write_text(eula, encoding='utf-8')
    (directory / 'server.properties').write_text('\n'.join((
        '# Isolated headless HardRush P1 restart regression; loopback only.',
        'server-ip=127.0.0.1', 'server-port=0', 'online-mode=false',
        'enforce-secure-profile=false', 'white-list=true', 'enforce-whitelist=true',
        'enable-rcon=false', 'enable-query=false', 'level-name=world',
        'level-seed=12345', 'gamemode=survival', 'difficulty=normal',
        'max-players=1', 'spawn-protection=0', 'view-distance=2',
        'simulation-distance=2', 'pause-when-empty-seconds=0',
        'motd=HardRush isolated P1 restart regression', '',
    )), encoding='utf-8')
    (directory / 'whitelist.json').write_text('[]\n', encoding='utf-8')


def cleanup_failed_process(process):
    """Terminate only this runner's child group. Return cleanup evidence, not PASS."""
    if process.poll() is not None:
        return {'required': False, 'exitCode': process.returncode}
    result = {'required': True, 'reason': 'Failure cleanup only; never counted as normal-stop recovery.'}
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass  # The child may have exited between poll() and killpg().
    try:
        process.wait(timeout=20)
        result['signal'] = 'SIGTERM'
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=10)
        result['signal'] = 'SIGKILL'
    result['exitCode'] = process.returncode
    return result


def build_launch(run, timeout):
    spec_path = run / 'launch-spec.json'
    command = [str(ROOT / 'scripts/p0-gradle.sh'), ':platforms:mc263:writeP1RestartLaunchSpec',
               f'-PhardrushP1RestartLaunchSpec={spec_path}', '--console=plain']
    evidence = {'command': command, 'log': 'build.log', 'status': 'RUNNING'}
    write_json(run / 'build-result.json', evidence)
    with (run / 'build.log').open('wb') as output:
        process = subprocess.Popen(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT,
                                   stdin=subprocess.DEVNULL, start_new_session=True)
        evidence['pid'] = process.pid
        try:
            process.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt) as error:
            evidence.update(status='FAIL', failure=type(error).__name__,
                            cleanup=cleanup_failed_process(process))
            write_json(run / 'build-result.json', evidence)
            raise RuntimeError(f'Launch build interrupted or timed out; see {run / "build.log"}') from error
    evidence.update(exitCode=process.returncode, status='PASS' if process.returncode == 0 else 'FAIL')
    write_json(run / 'build-result.json', evidence)
    if process.returncode != 0:
        raise RuntimeError(f'Launch build exited {process.returncode}; see {run / "build.log"}')
    spec = json.loads(spec_path.read_text(encoding='utf-8'))
    if spec.get('schema') != 1 or 'nogui' not in spec.get('programArgs', []):
        raise RuntimeError('Unsupported or graphical launch specification')
    if not Path(spec['java']).is_file() or not spec.get('classpath'):
        raise RuntimeError('Launch specification is missing Java or its classpath')
    if any('fabric-api.gametest' in argument or 'fabric-api.enableGameTest' in argument
           for argument in spec['jvmArgs']):
        raise RuntimeError('Refusing a GameTest-runner launch; a normal dedicated server is required')
    return spec


def read_snapshot_header(payload):
    """Read the shared schema-1..5 prefix through its clean-stop marker.

    RoundSnapshotCodec added protection (2), monster deployment (3), and skill
    cooldowns (4) only after this prefix, inside participant entries. Schema 5
    changes legacy terminal migration without changing the wire prefix. The JVM
    fixture decodes that complete payload; this independent check reads its
    bounded identity/timing prefix and compares the exact checkpoint bytes.
    """
    if len(payload) > 4 * 1024 * 1024:
        raise RuntimeError('Round snapshot exceeds size limit')
    offset = 0

    def take(size):
        nonlocal offset
        end = offset + size
        if size < 0 or end > len(payload):
            raise RuntimeError('Truncated round snapshot')
        result = payload[offset:end]
        offset = end
        return result

    def number(format_string):
        return struct.unpack(format_string, take(struct.calcsize(format_string)))[0]

    def text(limit):
        length = number('>i')
        if not 0 <= length <= limit:
            raise RuntimeError('Invalid snapshot text length')
        return take(length).decode('utf-8')

    if number('>I') != 0x48525331:
        raise RuntimeError('Unsupported round snapshot format')
    schema = number('>i')
    if not 1 <= schema <= 6:
        raise RuntimeError('Unsupported round snapshot schema')
    result = {'schema': schema, 'roundId': str(uuid.UUID(bytes=take(16))), 'sequence': number('>q'),
              'seed': number('>q'), 'version': number('>q'), 'rules': text(131072),
              'phase': text(64), 'pauseReason': text(64), 'challengeNanos': number('>q'),
              'remainingPhaseNanos': number('>q')}
    clean, uncertain = number('>B'), number('>B')
    if clean not in (0, 1) or uncertain not in (0, 1):
        raise RuntimeError('Invalid snapshot stop markers')
    result.update(cleanShutdown=bool(clean), timingUncertain=bool(uncertain))
    return result


def storage_evidence(directory, fixture):
    world = directory / 'world'
    database = world / 'hardrush/p1.sqlite'
    if not database.is_file() or database.is_symlink():
        raise RuntimeError('Fixture did not produce an owned P1 SQLite database')
    # Read-only mode cannot silently create a missing database. Both JVMs have exited.
    with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as connection:
        integrity = [row[0] for row in connection.execute('PRAGMA integrity_check')]
        if integrity != ['ok']:
            raise RuntimeError('SQLite integrity_check failed: ' + repr(integrity))
        row = connection.execute('''
            SELECT snapshot.round_id, snapshot.version, snapshot.payload, snapshot.revision, event.payload_digest
            FROM round_snapshots AS snapshot JOIN store_events AS event ON event.sequence=snapshot.revision
            ORDER BY snapshot.revision DESC LIMIT 1
        ''').fetchone()
        if row is None:
            raise RuntimeError('Missing persisted round snapshot')
        snapshot = read_snapshot_header(row[2])
        digest = hashlib.sha256(row[2]).digest()
        if digest != row[4]:
            raise RuntimeError('Round snapshot payload digest does not match its store event')
        if row[0] != snapshot['roundId'] or row[1] != snapshot['version']:
            raise RuntimeError('Round snapshot row identity or version mismatch')
        if snapshot['roundId'] != fixture['roundId'] or not snapshot['cleanShutdown']:
            raise RuntimeError('Fixture round differs from latest snapshot or normal-stop marker is missing')
        if base64.b64decode(fixture['checkpoint'], validate=True) != row[2]:
            raise RuntimeError('SERVER_STOPPED fixture checkpoint differs from persisted SQLite bytes')
        sqlite_evidence = {'path': str(database.relative_to(directory)), 'integrityCheck': integrity,
                           'userVersion': connection.execute('PRAGMA user_version').fetchone()[0],
                           'revision': row[3], 'payloadSha256': digest.hex(), 'snapshot': snapshot}

    manifests = []
    manifest_directory = world / 'hardrush/p1-worlds'
    for path in sorted(manifest_directory.glob('*.json')):
        if path.is_symlink() or not path.is_file():
            raise RuntimeError(f'Unsafe manifest: {path}')
        manifest = json.loads(path.read_text(encoding='utf-8'))
        if manifest.get('schema') != 1 or manifest.get('roundId') != path.stem:
            raise RuntimeError(f'Manifest schema or round identity mismatch: {path}')
        manifests.append({'path': str(path.relative_to(directory)), 'sha256': sha256(path),
                          'roundId': manifest['roundId'], 'seed': manifest.get('seed')})

    profiles = []
    profile_directory = world / 'hardrush/profiles'
    for path in sorted(profile_directory.rglob('*')):
        if path.is_symlink():
            raise RuntimeError(f'Unsafe profile path: {path}')
        if not path.is_file():
            continue
        contents = path.read_bytes()
        profile = {'path': str(path.relative_to(directory)), 'sha256': hashlib.sha256(contents).hexdigest()}
        if path.suffix == '.bin':
            if not 36 < len(contents) <= 16 * 1024 * 1024 + 36:
                raise RuntimeError(f'Invalid profile envelope size: {path}')
            length = struct.unpack('>i', contents[:4])[0]
            payload = contents[36:]
            if len(payload) != length or hashlib.sha256(payload).digest() != contents[4:36]:
                raise RuntimeError(f'Profile journal checksum mismatch: {path}')
            profile.update(envelopeChecksum='PASS', payloadSha256=contents[4:36].hex())
        profiles.append(profile)
    return {'sqlite': sqlite_evidence, 'manifests': manifests, 'profiles': profiles}


def run_operation(spec, directory, phase, operation, timeout):
    result_path = directory / f'{operation}-result.json'
    if result_path.exists():
        raise RuntimeError(f'Refusing stale fixture result: {result_path}')
    prefixes = tuple(f'-Dhardrush.p1.restart.{name}=' for name in RESTART_PROPERTIES)
    jvm_args = [arg for arg in spec['jvmArgs'] if not arg.startswith(prefixes) and not arg.startswith('-Dhardrush.p1.initialMode=')]
    jvm_args.append('-Dhardrush.p1.initialMode=' + ('hardcore' if phase == 'RESCUE' else 'normal'))
    jvm_args.extend((f'-Dhardrush.p1.restart.phase={phase}',
                     f'-Dhardrush.p1.restart.operation={operation}',
                     f'-Dhardrush.p1.restart.directory={directory}'))
    command = [spec['java'], *jvm_args, '-cp', os.pathsep.join(spec['classpath']),
               spec['mainClass'], *spec['programArgs']]
    environment = os.environ.copy()
    environment.update({key: str(value) for key, value in spec.get('environment', {}).items()})
    evidence_path = directory / f'{operation}-evidence.json'
    evidence = {'phase': phase, 'operation': operation, 'status': 'RUNNING',
                'log': f'{operation}.log', 'command': command, 'timeoutSeconds': timeout}
    started = time.monotonic()
    write_json(evidence_path, evidence)
    with (directory / f'{operation}.log').open('wb') as output:
        process = subprocess.Popen(command, cwd=directory, env=environment,
                                   stdout=output, stderr=subprocess.STDOUT,
                                   stdin=subprocess.DEVNULL, start_new_session=True)
        evidence['pid'] = process.pid
        write_json(evidence_path, evidence)
        try:
            process.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt) as error:
            evidence.update(status='FAIL', failure=type(error).__name__,
                            cleanup=cleanup_failed_process(process),
                            elapsedSeconds=round(time.monotonic() - started, 3))
            write_json(evidence_path, evidence)
            raise RuntimeError(f'{phase}/{operation} interrupted or timed out; cleanup is not recovery proof') from error
    evidence.update(exitCode=process.returncode, elapsedSeconds=round(time.monotonic() - started, 3))
    try:
        if process.returncode != 0:
            raise RuntimeError(f'JVM exited {process.returncode}')
        save_failures = [line for line in (directory / evidence['log']).read_text(
            encoding='utf-8', errors='replace').splitlines() if SAVE_FAILURE.search(line)]
        if save_failures:
            evidence['saveFailures'] = save_failures
            raise RuntimeError('Vanilla level save/close reported failure despite exit 0')
        if not result_path.is_file() or result_path.is_symlink():
            raise RuntimeError('Missing SERVER_STOPPED fixture result')
        fixture = json.loads(result_path.read_text(encoding='utf-8'))
        if fixture.get('status') != 'PASS':
            evidence['fixture'] = fixture
            raise RuntimeError('SERVER_STOPPED fixture failed: ' + str(fixture.get('error', fixture.get('status'))))
        if any(fixture.get(key) != value for key, value in
               (('operation', operation), ('phase', phase), ('pid', process.pid))):
            raise RuntimeError('Fixture operation, phase, or actual JVM PID differs from this attempt')
        if fixture.get('stoppedNativeCallbacksExecuted') != 16000 or fixture.get('shutdownReentrantCount') != 0:
            raise RuntimeError('Stopped native callbacks were skipped or left a corrupt owner counter')
        uuid.UUID(fixture['roundId'])
        evidence.update(fixture=fixture, storage=storage_evidence(directory, fixture), status='PASS')
    except (RuntimeError, ValueError, KeyError, OSError, sqlite3.Error) as error:
        evidence.update(status='FAIL', failure=str(error))
        write_json(evidence_path, evidence)
        raise RuntimeError(f'{phase}/{operation}: {error}; see {directory / evidence["log"]}') from error
    write_json(evidence_path, evidence)
    print(f'{phase}/{operation}: PASS (JVM {process.pid}, exit 0, clean SQLite)', flush=True)
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase', choices=PHASES, action='append', help='Run only selected phase(s); default: all eight')
    parser.add_argument('--timeout', type=int, default=600, help='Maximum seconds per JVM and launch build (default: 600)')
    arguments = parser.parse_args()
    if arguments.timeout < 1:
        parser.error('--timeout must be positive')
    phases = list(dict.fromkeys(arguments.phase or PHASES))
    eula = accepted_eula()
    BASE.mkdir(parents=True, exist_ok=True)
    run = BASE / str(uuid.uuid4())
    run.mkdir()
    summary = {'schema': 1, 'status': 'RUNNING', 'runDirectory': str(run),
               'phases': phases, 'scenarios': [], 'verification': 'Independent headless dedicated-server JVMs; no real clients.'}
    summary_path = run / 'result.json'
    write_json(summary_path, summary)
    print(f'P1 isolated restart evidence: {run}', flush=True)
    try:
        spec = build_launch(run, arguments.timeout)
        for phase in phases:
            directory = run / phase
            prepare_scenario(directory, eula)
            write = run_operation(spec, directory, phase, 'write', arguments.timeout)
            recover = run_operation(spec, directory, phase, 'recover', arguments.timeout)
            before, after = write['fixture'], recover['fixture']
            if write['pid'] == recover['pid']:
                raise RuntimeError('Recovery must use a different JVM PID')
            if (after.get('recoveredRoundId') != before['roundId']
                    or after.get('recoveredSequence') != before['sequence']):
                raise RuntimeError('Recovery must begin from the writer\'s persisted round identity')
            if phase == 'ROTATING':
                if after['roundId'] == before['roundId'] or after['sequence'] != before['sequence'] + 1:
                    raise RuntimeError('Recovered rotation must advance exactly one round')
            elif after['roundId'] != before['roundId'] or after['sequence'] != before['sequence']:
                raise RuntimeError('Recovery must retain the persisted round identity')
            repeated = None
            if phase == 'COUNTDOWN':
                if recover['fixture'].get('lateOriginalRestoresInsideWithoutReset') is not True:
                    raise RuntimeError('Late original UUID did not restore inside the resumed countdown')
                repeated = run_operation(spec, directory, phase, 'recover-again', arguments.timeout)
                if repeated['pid'] in (write['pid'], recover['pid']):
                    raise RuntimeError('Repeated COUNTDOWN recovery needs a third distinct JVM')
                if repeated['fixture'].get('recoveredRoundId') != before['roundId']:
                    raise RuntimeError('Repeated recovery must retain the original round UUID')
                for key in ('unrelatedUuidRejected', 'onlyOneOriginalResumesInside', 'staleRestoreRejected', 'unsafeRestoreOutside'):
                    if repeated['fixture'].get(key) is not True:
                        raise RuntimeError('Missing repeated spatial COUNTDOWN proof: ' + key)
            summary['scenarios'].append({'phase': phase, 'status': 'PASS',
                                         'writePid': write['pid'], 'recoverPid': recover['pid'],
                                         'recoveredRoundId': before['roundId'],
                                         'finalRoundId': after['roundId'], 'finalSequence': after['sequence'],
                                         'repeatedRecoveryPid': repeated['pid'] if repeated else None})
            write_json(summary_path, summary)
        summary['status'] = 'PASS'
        write_json(summary_path, summary)
        print(f'PASS: {len(phases)} independent-JVM normal-stop recoveries; {summary_path}', flush=True)
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        summary.update(status='FAIL', failure=str(error))
        write_json(summary_path, summary)
        print(f'FAIL: {error}\nEvidence retained at {run}', file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
