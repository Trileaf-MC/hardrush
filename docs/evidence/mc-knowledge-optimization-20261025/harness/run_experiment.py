#!/usr/bin/env python3
"""Prepare 27 isolated trials; execution requires explicit --run. No auto retries."""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import random
import shutil
import signal
import subprocess
import threading
import time
from common import (BASE, MODEL, EFFORT, SEED, TIMEOUT, ARMS, QUESTIONS, SKILL,
                    CONFIGS, COMMON, cli_files, common_env, env_audit, dump, read,
                    sha, sha_text, verify_seal, make_prompt, attempt_paths, public_event)


def populate(row, target):
    workspace = target / 'workspace'
    workspace.mkdir(parents=True, exist_ok=False)
    paths = [p.relative_to(BASE / 'final') for p in cli_files(BASE / 'final')]
    for rel in paths:
        dest = workspace / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(BASE / 'final' / rel, dest)
    for rel in CONFIGS:
        dest = workspace / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(BASE / 'inputs' / rel, dest)
    dest = workspace / SKILL
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(BASE / ARMS[row['arm']]['skill'] / SKILL, dest)
    (workspace / '.local/mc-knowledge').mkdir()
    (target / 'prompt.txt').write_text(make_prompt(row['topic']))
    dump(target / 'initial-files.json', {str(p.relative_to(workspace)): sha(p)
                                       for p in workspace.rglob('*') if p.is_file()})
    return workspace


def prepare():
    verify_seal()
    if (BASE / 'manifest.json').exists() or (BASE / 'runs').exists():
        raise SystemExit('Existing experiment found; prepare never overwrites results.')
    rng = random.Random(SEED)
    pairs = [(topic, repeat) for topic in QUESTIONS for repeat in range(1, 4)]
    rng.shuffle(pairs)
    rows = []
    for wave, (topic, repeat) in enumerate(pairs, 1):
        order = list(ARMS)
        rng.shuffle(order)
        for position, arm in enumerate(order, 1):
            row = {'id': f'run-{len(rows)+1:02d}', 'topic': topic, 'arm': arm,
                   'repeat': repeat, 'wave': wave, 'launch_position': position,
                   'format_env': ARMS[arm]['format'], 'skill_variant': ARMS[arm]['skill'],
                   'prompt_sha256': sha_text(make_prompt(topic))}
            target = BASE / 'runs' / row['id'] / 'attempt-01'
            workspace = populate(row, target)
            row['workspace'] = str(workspace)
            rows.append(row)
    manifest = {'schema': 1, 'model': MODEL, 'effort': EFFORT, 'seed': SEED,
                'timeout_seconds': TIMEOUT, 'concurrency': 3, 'waves': 9,
                'arms': ARMS, 'topics': QUESTIONS, 'runs': rows,
                'common_instructions': COMMON,
                'frozen_source_sha256': verify_seal()['sha256'],
                'cache_policy': 'Same existing raw Gradle inputs; independent initially empty .local/mc-knowledge per attempt. OS/page/network caches not reset.',
                'primary_policy': 'First attempt for each assigned trial. Failures remain in primary results. Retries are explicit, separate, and never replace primary samples.',
                'format_audit_policy': 'No wrapper or telemetry. Inspect explicit format arguments, arm environment and visible compact format marker. Ambiguous redirects/pipelines/outputs remain unknown.'}
    dump(BASE / 'manifest.json', manifest)
    return manifest


def command(workspace, target, format_env):
    return ['codex', '--no-daemon', 'exec', '--ignore-user-config', '--skip-git-repo-check',
            '-C', str(workspace), '-m', MODEL, '-c', 'model_reasoning_effort="high"',
            '-c', 'cli_auth_credentials_store="keyring"', '-c', 'project_doc_max_bytes=0',
            '-c', 'web_search="live"', '-c', 'sandbox_workspace_write.network_access=true',
            '-c', 'suppress_unstable_features_warning=true', '-c', 'agents.enabled=false',
            '-c', 'shell_environment_policy.set.MC_KNOWLEDGE_FORMAT=' + json.dumps(format_env),
            '-s', 'workspace-write', '--enable', 'skip_host_skill_discovery',
            '--disable', 'memories', '--disable', 'plugins', '--disable', 'apps',
            '--disable', 'multi_agent', '--disable', 'chronicle', '--disable', 'hooks',
            '--json', '-o', str(target / 'answer.md'), '-']


def execute(row, retry_failed, shared_env, start_after=None, started=None):
    attempts = attempt_paths(row)
    current = attempts[-1]
    previous = read(current / 'status.json') if (current / 'status.json').exists() else None
    if previous:
        # A success anywhere is immutable. A first-attempt failure remains in primary analysis.
        success = any((p / 'status.json').exists() and read(p / 'status.json')['state'] == 'completed'
                      for p in attempts)
        if success or not retry_failed:
            return {'id': row['id'], 'state': 'skipped_existing', 'previous_state': previous['state']}
        target = current.parent / f'attempt-{len(attempts)+1:02d}'
        workspace = populate(row, target)
    else:
        target = current
        workspace = target / 'workspace'
    initial = read(target / 'initial-files.json')
    actual = {str(p.relative_to(workspace)): sha(p) for p in workspace.rglob('*') if p.is_file()}
    if actual != initial:
        raise RuntimeError('Workspace changed before run: ' + str(target))
    if sha_text((target / 'prompt.txt').read_text()) != row['prompt_sha256']:
        raise RuntimeError('Prompt changed before run: ' + str(target))
    args = command(workspace, target, row['format_env'])
    env = {**shared_env, 'PWD': str(workspace), 'MC_KNOWLEDGE_FORMAT': row['format_env']}
    dump(target / 'invocation.json', {'argv': args, 'environment': env_audit(env),
                                    'common_environment_sha256': sha_text(json.dumps(shared_env, sort_keys=True)),
                                    'timeout_seconds': TIMEOUT})
    if start_after:
        start_after.wait()
    start = time.time()
    status = {**row, 'attempt': target.name, 'workspace': str(workspace),
              'state': 'running', 'start_unix': start}
    dump(target / 'status.json', status)
    counts = {'discarded_private_events': 0, 'malformed_lines': 0, 'observable_events': 0}
    with (target / 'prompt.txt').open('rb') as stdin, (target / 'events.jsonl').open('w') as event_log, (target / 'stderr.log').open('wb') as stderr:
        child = subprocess.Popen(args, cwd=workspace, env=env, stdin=stdin, stdout=subprocess.PIPE,
                                 stderr=stderr, text=False, start_new_session=True)
        if started:
            started.set()
        def collect():
            for raw in child.stdout:
                try:
                    event = json.loads(raw)
                except (ValueError, UnicodeDecodeError):
                    # Do not copy unparsed data that could contain private/internal content.
                    counts['malformed_lines'] += 1
                    continue
                event = public_event(event)
                if event is None:
                    counts['discarded_private_events'] += 1
                    continue
                event_log.write(json.dumps(event, ensure_ascii=False) + '\n')
                event_log.flush()
                counts['observable_events'] += 1
        collector = threading.Thread(target=collect, daemon=True)
        collector.start()
        try:
            code = child.wait(timeout=TIMEOUT)
            state = 'completed' if code == 0 else 'failed'
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
            code, state = child.returncode, 'timeout'
        collector.join(timeout=15)
        if collector.is_alive():
            os.killpg(child.pid, signal.SIGKILL)
            collector.join(timeout=5)
            counts['stdout_collection_incomplete'] = True
    answer = target / 'answer.md'
    if state == 'completed' and (not answer.exists() or not answer.read_text().strip()):
        state = 'failed_missing_answer'
    status.update(state=state, exit_code=code, end_unix=time.time(),
                  elapsed_seconds=round(time.time()-start, 3), event_capture=counts)
    for line in (target / 'events.jsonl').read_text().splitlines():
        event = json.loads(line)
        if event.get('type') == 'thread.started':
            status['thread_id'] = event.get('thread_id')
        elif event.get('type') == 'turn.completed':
            status['cli_cumulative_usage'] = event.get('usage')
    dump(target / 'status.json', status)
    print(json.dumps({k: status.get(k) for k in ('id', 'topic', 'arm', 'repeat', 'attempt', 'state', 'elapsed_seconds')}, ensure_ascii=False), flush=True)
    return status


def ordered_execute(row, retry_failed, shared_env, start_after, started):
    try:
        return execute(row, retry_failed, shared_env, start_after, started)
    except Exception as error:
        target = attempt_paths(row)[-1]
        status = read(target/'status.json') if (target/'status.json').exists() else {**row, 'attempt': target.name}
        if status.get('state') != 'completed':
            status.update(state='failed_harness', end_unix=time.time(),
                          harness_error={'type': type(error).__name__, 'message': str(error)})
            dump(target/'status.json', status)
        raise
    finally:
        # Unblock later launches even when this trial is skipped or fails to spawn.
        started.set()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--retry-failed', action='store_true', help='Explicit extra attempts, excluded from primary N=27.')
    parser.add_argument('--only-wave', type=int, choices=range(1, 10))
    opts = parser.parse_args()
    if not any((opts.prepare, opts.run, opts.dry_run)):
        parser.error('Choose --prepare, --dry-run or --run; no implicit model execution.')
    if opts.retry_failed and not opts.run:
        parser.error('--retry-failed requires --run')
    manifest = prepare() if opts.prepare else read(BASE / 'manifest.json')
    if opts.prepare:
        print(json.dumps({'prepared': len(manifest['runs']), 'manifest': str(BASE / 'manifest.json')}))
    if opts.dry_run:
        for row in manifest['runs']:
            target = BASE / 'runs' / row['id'] / 'attempt-01'
            print(json.dumps({'row': row, 'argv': command(target / 'workspace', target, row['format_env'])}, ensure_ascii=False))
    if opts.run:
        verify_seal()
        lock = BASE / 'run.lock'
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.write(fd, json.dumps({'pid': os.getpid(), 'created_unix': time.time()}).encode())
        os.close(fd)
        try:
            shared_env = common_env()
            env_path = BASE / 'runtime-environment.json'
            environment = {'common_environment_sha256': sha_text(json.dumps(shared_env, sort_keys=True)),
                           'environment': env_audit(shared_env)}
            if env_path.exists() and read(env_path) != environment:
                raise RuntimeError('Common runtime environment changed since first run; refusing mixed environments.')
            if not env_path.exists():
                dump(env_path, environment)
            for wave in range(1, 10):
                if opts.only_wave and wave != opts.only_wave:
                    continue
                rows = [r for r in manifest['runs'] if r['wave'] == wave]
                with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
                    futures, previous = [], None
                    for row in rows:
                        started = threading.Event()
                        futures.append(pool.submit(ordered_execute, row, opts.retry_failed, shared_env, previous, started))
                        previous = started
                    for future in futures:
                        # Surface exceptions instead of fabricating replacements.
                        future.result()
                statuses = []
                for row in manifest['runs']:
                    for attempt in attempt_paths(row):
                        if (attempt / 'status.json').exists():
                            statuses.append(read(attempt / 'status.json'))
                dump(BASE / 'results-all-attempts.json', statuses)
        finally:
            lock.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
