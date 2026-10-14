import importlib.util
import json
import pathlib
import shutil
import datetime
import time

root = pathlib.Path.cwd()
spec = importlib.util.spec_from_file_location('hardrush_debug', root / 'scripts/debug-tools.py')
debug = importlib.util.module_from_spec(spec)
spec.loader.exec_module(debug)
evidence = root / '.local/research/world-boundary-restart'
status = debug.rpc('server', 'info.status')
assert status['playerCount'] == 0, 'Restart fixture requires an empty isolated server'
assert debug.process('server'), 'Restart fixture requires the managed debug server'
manifest = json.loads((root / '.local/debug/server/debug-world/hardrush-p0/world-manifest.json').read_text())
dimensions = debug.rpc('server', 'world.getDimensions')['dimensions']
own = next(d for d in dimensions if d.startswith('hardrush:p0_') and d.endswith('/overworld'))

def command(value):
    result = debug.rpc('server', 'command.run', {'command': value})
    assert result['success'], result
    return result

def state(dimension):
    return debug.rpc('server', 'world.getTimeAndWeather', {'dimension': dimension})

command('save-all flush')
folder = root / '.local/debug/server/debug-world/dimensions' / own.replace(':', '/') / 'data'
backups = []
for relative in ['hardrush/p0_clocks.dat', 'minecraft/weather.dat']:
    target = folder / relative
    backup = evidence / (target.stem + '.original.dat')
    shutil.copyfile(target, backup)
    backups.append((target, backup))
result = {'minecraft': '26.3', 'client': 'none', 'roundId': manifest['roundId'], 'dimension': own,
          'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'original': state(own), 'lobbyOriginal': state('minecraft:overworld')}
try:
    command(f'execute in {own} run time set 17000')
    command(f'execute in {own} run weather thunder 600s')
    command('save-all flush')
    deadline = time.monotonic() + 12
    while True:
        result['beforeRestart'] = state(own)
        if result['beforeRestart']['raining'] and result['beforeRestart']['thundering']:
            break
        assert time.monotonic() < deadline, 'Rendered weather did not reach thunder after transition'
        time.sleep(0.25)
    debug.stop('server')
    debug.start('server')
    result['afterRestart'] = state(own)
    result['lobbyAfterRestart'] = state('minecraft:overworld')
    after_manifest = json.loads((root / '.local/debug/server/debug-world/hardrush-p0/world-manifest.json').read_text())
    assert after_manifest == manifest, 'Round manifest changed'
    before = result['beforeRestart']
    after = result['afterRestart']
    assert after['raining'] and after['thundering'], 'Round weather did not persist'
    assert abs((after['dayTime'] - before['dayTime']) - (after['gameTime'] - before['gameTime'])) <= 2, 'Round day clock reset or ticked at the wrong rate'
    assert result['lobbyOriginal']['raining'] == result['lobbyAfterRestart']['raining'], 'Round weather leaked into lobby'
    result['passed'] = True
finally:
    debug.stop('server')
    assert not any(debug.listening(port) for port in debug.PORTS['server']), 'Refuse to restore while server is active'
    for target, backup in backups:
        shutil.copyfile(backup, target)
    debug.start('server')
    result['restored'] = state(own)
    (evidence / 'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))
