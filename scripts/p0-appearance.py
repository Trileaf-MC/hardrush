#!/usr/bin/env python3
"""Reproducible manual appearance arena, restricted to isolated loopback probes.

Uses the server bridge only. HRProbeA/B remain unmodified official clients.
No account session, game input, launcher credentials or public server is touched.
"""
import argparse
import importlib.util
import json
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('hardrush_debug_tools', ROOT / 'scripts/debug-tools.py')
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)
NAMES = ('HRProbeA', 'HRProbeB')


def command(value):
    deadline = time.monotonic() + 10
    while True:
        result = bridge.rpc('server', 'command.run', {'command': value})
        if result['success'] or result['output'] != ['That position is not loaded'] or time.monotonic() >= deadline:
            break
        time.sleep(.2)
    # These native commands report an error for an already satisfied no-op.
    no_op = any(message in ('No blocks were filled', 'Nothing changed. The block could not be placed',
                            'Could not set the block',
                            'No chunks were marked for force loading')
                or (message.startswith('Game rule ') and ' is already set to ' in message)
                or message.startswith('No items were found on player ')
                or message.startswith('Target has no effects to remove') for message in result['output'])
    if not result['success'] and not no_op:
        raise RuntimeError('Fixture command failed: ' + value + ': ' + json.dumps(result['output']))
    return result


def ready():
    properties = dict(line.split('=', 1) for line in (bridge.BASE / 'server/server.properties').read_text().splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
    if properties.get('server-ip') != '127.0.0.1' or properties.get('server-port') != '25586':
        raise RuntimeError('This fixture is restricted to the isolated debug server on 127.0.0.1:25586.')
    status = bridge.rpc('server', 'info.status')
    if status.get('side') != 'dedicated_server' or not status.get('serverPresent'):
        raise RuntimeError('Start the isolated dedicated server first.')
    players = bridge.rpc('server', 'players.list')
    present = {entry['name'] for entry in players['players']}
    if present != set(NAMES):
        raise RuntimeError('Connect only HRProbeA and HRProbeB before preparing; present=' + ','.join(sorted(present)))


def prepare():
    ready()
    # Separate from the automated arena around (0,150,0) and all round worlds.
    # The destructive fill is confined to this explicit local test rectangle.
    commands = [
        'execute in minecraft:overworld run forceload add 47 47 81 81',
        'execute in minecraft:overworld run fill 47 148 47 81 158 81 air',
        'execute in minecraft:overworld run fill 48 149 48 80 149 80 smooth_stone',
        'execute in minecraft:overworld run fill 47 150 47 81 151 47 glass',
        'execute in minecraft:overworld run fill 47 150 81 81 151 81 glass',
        'execute in minecraft:overworld run fill 47 150 48 47 151 80 glass',
        'execute in minecraft:overworld run fill 81 150 48 81 151 80 glass',
        # A one-block-wide, two-block-high doorway and a slab/stair path.
        'execute in minecraft:overworld run fill 52 150 58 58 152 58 oak_planks',
        'execute in minecraft:overworld run fill 55 150 58 55 151 58 air',
        'execute in minecraft:overworld run setblock 55 150 58 oak_door[facing=north,half=lower,open=true]',
        'execute in minecraft:overworld run setblock 55 151 58 oak_door[facing=north,half=upper,open=true]',
        'execute in minecraft:overworld run setblock 68 150 56 stone_slab',
        'execute in minecraft:overworld run setblock 69 150 56 stone_stairs[facing=east]',
        'execute in minecraft:overworld run setblock 70 150 56 stone',
        # Pool with solid bottom, two water blocks deep, and stairs back out.
        'execute in minecraft:overworld run fill 68 147 68 76 147 76 smooth_stone',
        'execute in minecraft:overworld run fill 68 148 68 76 149 76 water',
        'execute in minecraft:overworld run fill 68 148 68 68 149 76 smooth_stone',
        'execute in minecraft:overworld run fill 76 148 68 76 149 76 smooth_stone',
        'execute in minecraft:overworld run fill 69 148 68 75 149 68 smooth_stone',
        'execute in minecraft:overworld run fill 69 148 76 75 149 76 smooth_stone',
        'execute in minecraft:overworld run setblock 72 148 69 stone_stairs[facing=north]',
        'execute in minecraft:overworld run setblock 72 149 68 stone_stairs[facing=north]',
        'execute in minecraft:overworld run setblock 64 149 64 gold_block',
        'execute in minecraft:overworld run setblock 64 149 61 emerald_block',
        'execute in minecraft:overworld run setblock 50 149 50 sea_lantern',
        'execute in minecraft:overworld run setblock 78 149 78 sea_lantern',
        'time set day', 'weather clear', 'gamerule keep_inventory true',
    ]
    for name in NAMES:
        commands.extend([
            f'hardrushp0 clear {name}', f'gamemode survival {name}', f'effect clear {name}',
            f'execute in minecraft:overworld run tp {name} 64.5 150 {64.5 if name == NAMES[0] else 61.5} {180 if name == NAMES[0] else 0} 0',
            f'execute in minecraft:overworld run spawnpoint {name} 64 150 64',
            f'clear {name}', f'give {name} diamond_sword', f'give {name} bow',
            f'give {name} arrow 64', f'give {name} cooked_beef 64',
            f'give {name} shield', f'give {name} diamond_helmet', f'give {name} diamond_chestplate',
            f'item replace entity {name} armor.head with diamond_helmet',
            f'item replace entity {name} armor.chest with diamond_chestplate',
            f'item replace entity {name} weapon.offhand with shield',
            f'effect give {name} instant_health 1 10', f'effect give {name} saturation 1 10',
        ])
    commands.append('hardrushp0 appearance HRProbeA zombie')
    results = [command(value) for value in commands]
    output = ROOT / '.local/research/observer-appearance-continuation/manual-fixture.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'server': '127.0.0.1:25586', 'target': NAMES[0], 'observer': NAMES[1],
                                  'arena': [47, 147, 47, 81, 158, 81], 'commands': results}, ensure_ascii=False, indent=2) + '\n')
    print('Ready: HRProbeA=zombie/owner; HRProbeB=observer. Arena: (64,150,64), doorway=(55,150,58), steps=(68,150,56), pool=(72,149,72).')
    print('Evidence: ' + str(output))


def snapshot():
    ready()
    results = {name: command('hardrushp0 status ' + name) for name in NAMES}
    results['players'] = bridge.rpc('server', 'players.list')
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'snapshot'))
    args = parser.parse_args()
    prepare() if args.action == 'prepare' else snapshot()
