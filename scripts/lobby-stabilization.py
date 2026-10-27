#!/usr/bin/env python3
"""Run command-free 26.3 headless suites sequentially, each with newly owned storage."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('restart_cleanup', ROOT / 'scripts/server-restart.py')
RESTART = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RESTART)
GRADLE = [str(ROOT / 'gradlew')]
GAME = GRADLE + [':platforms:mc263:runGameTest', '-PhardrushCompetition=true']
CHECKS = {
    'base': GRADLE + ['build', '-PhardrushCompetition=true', '--rerun-tasks', '--console=plain'],
    'stabilization': GAME + ['-PhardrushStabilization=true', '--console=plain'],
    'full-flow': GAME + ['-PhardrushFullFlow=true', '--console=plain'],
    'revival-fault': GAME + ['-PhardrushFullFlow=true', '-PhardrushFullFlowFault=revival', '--console=plain'],
    'personal': GAME + ['-PhardrushPersonalHunt=true', '--console=plain'],
    'personal-storage': GAME + ['-PhardrushPersonalHunt=true', '-PhardrushPersonalHuntFault=storage', '--console=plain'],
    'foundation': GAME + ['-PhardrushLobbyFoundation=true', '--console=plain'],
    'human': GAME + ['-PhardrushHumanEntry=true', '--console=plain'],
    'movement': GAME + ['-PhardrushLobbyMovement=true', '--console=plain'],
    'lobby-runtime': GAME + ['-PhardrushLobbyRuntime=true', '--console=plain'],
    'celebration': GAME + ['-PhardrushCelebration=true', '--console=plain'],
    'ten-rounds': GAME + ['-PhardrushTenRounds=true', '--console=plain'],
    'recovery': GAME + ['-PhardrushRecovery=true', '--console=plain'],
    'storage': GRADLE + [':platforms:mc263:runGameTest', '-PhardrushStorageReserve=true', '--console=plain'],
    'restart': [sys.executable, str(ROOT / 'scripts/server-restart.py'), '--phase', 'COUNTDOWN',
                '--phase', 'RUNNING', '--phase', 'RESCUE', '--phase', 'CELEBRATING', '--phase', 'ROTATING'],
    'profile-crash': [sys.executable, str(ROOT / 'scripts/server-crash.py'), '--scenario', 'PROFILE', '--boundary', 'PREPARED'],
    'python': [sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts', '-p', 'test_*.py', '-v'],
}


def sources():
    names = subprocess.check_output(['git', 'ls-files', '-co', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    return {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in sorted(set(names))
            if name and (ROOT / name).is_file() and (name.startswith(('core/', 'persistence/', 'platforms/', 'scripts/', 'gradle/'))
                         or name in ('gradle.properties', 'build.gradle', 'settings.gradle', 'gradlew'))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='append', choices=CHECKS, help='Selected checks; default runs the complete matrix.')
    parser.add_argument('--timeout', type=int, default=1200, help='Seconds per owned check process (restart has per-JVM limits too).')
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error('--timeout must be positive')
    directory = ROOT / '.local/lobby-stabilization-matrix' / str(uuid.uuid4())
    directory.mkdir(parents=True)
    baseline = sources()
    RESTART.write_json(directory / 'source-files.json', baseline)
    result = dict(schema=1, status='RUNNING', directory=str(directory), checks=[],
                  head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip(),
                  sourceManifest='source-files.json', scope='Headless native protocol/state only; no graphical or performance acceptance.')
    print(f'Stabilization evidence: {directory}', flush=True)
    try:
        for name in dict.fromkeys(args.check or CHECKS):
            if sources() != baseline:
                raise RuntimeError('Source files changed during validation; rerun against one frozen source tree.')
            command = CHECKS[name]
            entry = dict(name=name, command=command, log=name + '.log', status='RUNNING')
            result['checks'].append(entry)
            RESTART.write_json(directory / 'result.json', result)
            print(f'RUN {name}', flush=True)
            started = time.monotonic()
            with (directory / entry['log']).open('wb') as output:
                process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL, stdout=output,
                                           stderr=subprocess.STDOUT, start_new_session=True)
                entry['pid'] = process.pid
                try:
                    process.wait(timeout=args.timeout)
                except (subprocess.TimeoutExpired, KeyboardInterrupt):
                    entry.update(status='FAIL', cleanup=RESTART.cleanup_failed_process(process))
                    raise RuntimeError(f'{name} interrupted/timed out; only this runner child group was cleaned up.')
            entry.update(exitCode=process.returncode, elapsedSeconds=round(time.monotonic()-started, 3),
                         logSha256=RESTART.sha256(directory / entry['log']))
            if process.returncode != 0:
                entry['status'] = 'FAIL'
                raise RuntimeError(f'{name} exited {process.returncode}; see {directory / entry["log"]}')
            entry['status'] = 'PASS'
            print(f'PASS {name} ({entry["elapsedSeconds"]}s)', flush=True)
        if sources() != baseline:
            raise RuntimeError('Source files changed before validation completed.')
        result['status'] = 'PASS'
        print(f'PASS matrix: {directory / "result.json"}', flush=True)
    except (RuntimeError, OSError, ValueError) as error:
        result.update(status='FAIL', error=str(error))
        print(str(error), file=sys.stderr, flush=True)
    finally:
        RESTART.write_json(directory / 'result.json', result)
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
