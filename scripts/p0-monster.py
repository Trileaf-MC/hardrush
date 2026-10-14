#!/usr/bin/env python3
"""Prepare the native-monster human test in the named isolated P0 arena."""
import argparse
import importlib.util
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('appearance_manual',ROOT/'scripts/p0-appearance.py')
manual=importlib.util.module_from_spec(spec); spec.loader.exec_module(manual)


def cleanup_arena():
    result=manual.bridge.rpc('server','command.run',{'command':
        'execute in minecraft:overworld run kill @e[type=!minecraft:player,x=40,y=130,z=40,dx=50,dy=50,dz=50]'})
    if not result['success'] and not any(message in ('No entity was found','No entities were found')
                                        for message in result['output']):
        raise RuntimeError('Arena cleanup failed: '+json.dumps(result['output']))
    return result


def prepare():
    manual.ready()
    players=manual.bridge.rpc('server','players.list')['players']
    dead=[player['name'] for player in players if player['health']<=0]
    if dead:
        raise RuntimeError('Respawn these native clients before preparing: '+','.join(dead))
    safety=[manual.command(value) for value in (
        'gamerule spawn_mobs false', 'gamerule spawn_phantoms false',
        'gamerule advance_time false',
    )]
    safety.append(cleanup_arena())
    manual.prepare()
    # Clearing a previous role returns its picked gear to the world. Remove
    # those reset leftovers before entering a new bare-handed zombie role.
    safety.append(cleanup_arena())
    commands=[
        'hardrushp0 clear HRProbeA','clear HRProbeA',
        'item replace entity HRProbeA armor.head with air',
        'item replace entity HRProbeA armor.chest with air',
        'item replace entity HRProbeA armor.legs with air',
        'item replace entity HRProbeA armor.feet with air',
        'item replace entity HRProbeA weapon.offhand with air',
        'time set midnight',
        'execute in minecraft:overworld run setblock 60 150 62 chest',
        'execute in minecraft:overworld run item replace block 60 150 62 container.0 with ender_pearl 16',
        'execute in minecraft:overworld run item replace block 60 150 62 container.1 with bow',
        'execute in minecraft:overworld run item replace block 60 150 62 container.2 with diamond_helmet',
        'execute in minecraft:overworld run item replace block 60 150 62 container.3 with diamond_sword',
        'execute in minecraft:overworld run item replace block 60 150 62 container.4 with elytra',
        'execute in minecraft:overworld run item replace block 60 150 62 container.5 with bow[enchantments={power:3}]',
        'hardrushp0 disguise HRProbeA zombie',
    ]
    results=[manual.command(command) for command in commands]
    if not any('monster=zombie;' in message for message in results[-1]['output']):
        raise RuntimeError('The live zombie role was not applied: '+json.dumps(results[-1]['output']))
    out=ROOT/'.local/research/monster-roles/manual-fixture.json'
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps({'server':'127.0.0.1:25586','monster':'HRProbeA','human':'HRProbeB',
                              'safetyCommands':safety,'commands':results,
                              'stage':'operator role prototype; formal death/lobby/rewards pending'},ensure_ascii=False,indent=2)+'\n')
    print('Ready: A=zombie with automatic gear, B=human. Hotbar 1=actual weapon, 2=two baby zombies; B may take sword/helmet/elytra/Power III bow from chest (60,150,62) and drop them for A. A cannot open the chest.')


def snapshot():
    manual.ready()
    print(json.dumps({name:manual.command('hardrushdebug snapshot '+name) for name in manual.NAMES},ensure_ascii=False,indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=('prepare','snapshot'))
    args=parser.parse_args()
    prepare() if args.action=='prepare' else snapshot()
