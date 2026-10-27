#!/usr/bin/env python3
"""Independent dedicated JVMs: SIGKILL, automatic same-round rollback, corrupt-image quarantine."""
from pathlib import Path
import importlib.util
import hashlib
import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('p1_restart', ROOT / 'scripts/p1-server-restart.py')
P1 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(P1)


def launch(specification, directory, operation):
    arguments = [value for value in specification['jvmArgs']
                 if not value.startswith(('-Dhardrush.p1.restart.', '-Dhardrush.p1.fullCheckpoint'))]
    arguments += ['-Dhardrush.p1.fullCheckpoint.test=true', '-Dhardrush.p1.fullCheckpointIntervalSeconds=3600',
                  '-Dhardrush.p1.restart.phase=RUNNING', f'-Dhardrush.p1.restart.operation={operation}',
                  f'-Dhardrush.p1.restart.directory={directory}']
    command = [specification['java'], *arguments, '-cp', os.pathsep.join(specification['classpath']),
               specification['mainClass'], *specification['programArgs']]
    environment = os.environ.copy()
    environment.update({key: str(value) for key, value in specification.get('environment', {}).items()})
    output = (directory / f'{operation}.log').open('wb')
    process = subprocess.Popen(command, cwd=directory, env=environment, stdout=output,
                               stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
    return process, output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launch-spec', type=Path, help='Reuse a completed isolated build while other checkout edits are in progress')
    parser.add_argument('--online-only', action='store_true', help='Only run online recovery, confirmation, fallback and close-recovery in one JVM')
    arguments = parser.parse_args()
    run = ROOT / '.local/p3-full-checkpoint' / str(uuid.uuid4())
    run.mkdir(parents=True)
    print(f'Full-checkpoint evidence: {run}', flush=True)
    source_paths = [
        'core/src/main/java/dev/hardrush/core/round/RoundEngine.java',
        'core/src/main/java/dev/hardrush/core/round/RoundSnapshotCodec.java',
        'persistence/src/main/java/dev/hardrush/persistence/SqliteRoundStore.java',
        'platforms/mc263/src/main/java/dev/hardrush/round/RoundRuntime.java',
        'platforms/mc263/src/main/java/dev/hardrush/round/RoundSession.java',
        'platforms/mc263/src/main/java/dev/hardrush/player/PlayerProfiles.java',
        'platforms/mc263/src/main/java/dev/hardrush/storage/FullCheckpoint.java',
        'platforms/mc263/src/main/java/dev/hardrush/storage/FullCheckpointRuntime.java',
        'platforms/mc263/src/main/java/dev/hardrush/storage/RecoveryRuntime.java',
        'platforms/mc263/src/main/java/dev/hardrush/admin/AdminCommands.java',
    ]
    sources = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in source_paths}
    eula = P1.accepted_eula()
    # A separate build output avoids concurrent developer builds removing one another's classes.
    init = run / 'build.init.gradle'
    init.write_text("gradle.beforeProject { p ->\n"
                    "  p.layout.buildDirectory.set(new File('" + str(run) + "/build', p.path.replace(':', '_')))\n"
                    "  p.plugins.withId('java') { p.sourceSets.all { s ->\n"
                    "    p.tasks.named(s.compileJavaTaskName) { destinationDirectory.set(p.layout.buildDirectory.dir('classes/java/' + s.name)) }\n"
                    "  } }\n"
                    "}\n")
    launch_spec = run / 'launch-spec.json'
    command = [str(ROOT / 'gradlew'), '--init-script', str(init),
               ':platforms:mc263:writeP1RestartLaunchSpec',
               f'-PhardrushP1RestartLaunchSpec={launch_spec}', '--console=plain']
    if arguments.launch_spec:
        shutil.copyfile(arguments.launch_spec, launch_spec)
    else:
        with (run / 'build.log').open('wb') as output:
            subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=300)
    specification = json.loads(launch_spec.read_text())
    if 'nogui' not in specification['programArgs']:
        raise RuntimeError('Headless dedicated server required')
    # Gradle may retire a previous compile task's output even with a unique build directory.
    # Freeze project classes/resources/jars before another build can reuse that task history.
    for index, value in enumerate(specification['classpath']):
        source = Path(value)
        if not source.is_relative_to(ROOT) or '/.gradle/' in value:
            continue
        suffix = re.search(r'(classes/java/[^/]+|resources/[^/]+)$', value)
        if not source.exists():
            if suffix and suffix.group(0) == 'classes/java/main':
                raise RuntimeError('Reused build has been retired by Gradle; run again without --launch-spec')
            continue
        target = run / 'runtime' / str(index) / (suffix.group(0) if suffix else source.name)
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.is_dir():
            shutil.copytree(source, target)
        else:
            shutil.copyfile(source, target)
        specification['classpath'][index] = str(target)
    # Loom's launch.cfg is shared by every build of this checkout. Freeze it, including the
    # exact class/resource groups; another build may have selected a different output directory.
    groups = []
    for sets in (('main',), ('gametest', 'development')):
        members = [value for value in specification['classpath'] if Path(value).exists()
                   and any(value.replace('\\', '/').endswith('/classes/java/' + name)
                           or value.replace('\\', '/').endswith('/resources/' + name) for name in sets)]
        if members:
            groups.append(os.pathsep.join(members))
    for index, argument in enumerate(specification['jvmArgs']):
        if argument.startswith('-Dfabric.dli.config='):
            contents = Path(argument.split('=', 1)[1]).read_text()
            contents = re.sub(r'(?m)^\s*fabric\.classPathGroups=.*$',
                              '\tfabric.classPathGroups=' + (os.pathsep * 2).join(groups), contents)
            frozen_config = run / 'launch.cfg'
            frozen_config.write_text(contents)
            specification['jvmArgs'][index] = '-Dfabric.dli.config=' + str(frozen_config)
    P1.write_json(run / 'resolved-launch-spec.json', specification)
    if arguments.online_only:
        directory = run / 'online'
        P1.prepare_scenario(directory, eula)
        child, output = launch(specification, directory, 'online')
        try:
            child.wait(timeout=300)
            result = json.loads((directory / 'online-result.json').read_text())
            if child.returncode != 0 or result.get('status') != 'PASS' or result.get('pid') != child.pid:
                raise RuntimeError(f'Online recovery failed; see {directory / "online.log"}')
            result['sourceSha256'] = sources
            result['sourceChangedDuringRun'] = [name for name, digest in sources.items()
                                              if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest]
            P1.write_json(run / 'result.json', result)
            print(f'PASS: {run / "result.json"}', flush=True)
            return
        finally:
            if child.poll() is None:
                P1.cleanup_failed_process(child)
            output.close()
    writer = run / 'writer'
    P1.prepare_scenario(writer, eula)
    child, output = launch(specification, writer, 'write')
    try:
        deadline = time.monotonic() + 300
        ready = writer / 'writer-ready.json'
        while not ready.exists():
            if child.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError(f'Writer failed before crash barrier; see {writer / "write.log"}')
            time.sleep(.2)
        marker = json.loads(ready.read_text())
        if marker['pid'] != child.pid:
            raise RuntimeError('Crash barrier does not belong to this child')
        child.send_signal(signal.SIGKILL)
        child.wait(timeout=30)
        if child.returncode != -signal.SIGKILL:
            raise RuntimeError('Writer was not killed by SIGKILL')
    finally:
        if child.poll() is None:
            P1.cleanup_failed_process(child)
        output.close()
    results = {'writerPid': child.pid, 'writerExitCode': child.returncode, 'recoveries': []}
    for operation in ('recover', 'recover-corrupt'):
        directory = run / operation
        shutil.copytree(writer, directory)
        if operation == 'recover-corrupt':
            save_root = directory / 'world/hardrush/full-checkpoints'
            image = save_root / (save_root / 'latest').read_text().strip()
            sentinel = next((image / 'data/dimensions').rglob('*.mca'))
            with sentinel.open('ab') as file:
                file.write(b'corrupt fixture')
        child, output = launch(specification, directory, operation)
        try:
            child.wait(timeout=300)
            result = json.loads((directory / f'{operation}-result.json').read_text())
            if child.returncode != 0 or result.get('status') != 'PASS' or result.get('pid') != child.pid:
                raise RuntimeError(f'{operation} failed; see {directory / (operation + ".log")}')
            results['recoveries'].append(result)
            print(f'{operation}: PASS (PID {child.pid})', flush=True)
        finally:
            if child.poll() is None:
                P1.cleanup_failed_process(child)
            output.close()
    normal = run / 'normal-RUNNING'
    P1.prepare_scenario(normal, eula)
    results['normalRestart'] = [P1.run_operation(specification, normal, 'RUNNING', operation, 300)
                                for operation in ('write', 'recover')]
    results['sourceSha256'] = sources
    results['reusedBuild'] = str(arguments.launch_spec.resolve()) if arguments.launch_spec else None
    results['sourceChangedDuringRun'] = [name for name, digest in sources.items()
                                        if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest]
    results['status'] = 'PASS'
    P1.write_json(run / 'result.json', results)
    print(f'PASS: {run / "result.json"}', flush=True)


if __name__ == '__main__':
    main()
