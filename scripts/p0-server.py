#!/usr/bin/env python3
"""Prepare or run an isolated loopback-only P0 server. Never accepts the EULA."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import uuid

ROOT = Path(__file__).resolve().parent.parent
RUN = ROOT / '.local' / 'server-p0'
NAMES = ('HRProbeA', 'HRProbeB')


def profile(name):
    identifier = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(), version=3)
    return {'uuid': str(identifier), 'name': name}


def prepare():
    RUN.mkdir(parents=True, exist_ok=True)
    files = {
        'server.properties': '\n'.join((
            '# HardRush isolated P0 only. Do not use this offline profile for a public server.',
            'server-ip=127.0.0.1', 'server-port=25585', 'online-mode=false',
            'enforce-secure-profile=false', 'white-list=true', 'enforce-whitelist=true',
            'enable-rcon=false', 'enable-query=false', 'max-players=4',
            'level-name=p0-lobby', 'level-seed=0', 'gamemode=creative', 'difficulty=normal',
            'spawn-protection=0', 'view-distance=6', 'simulation-distance=4',
            'pause-when-empty-seconds=0', 'motd=HardRush P0 local verification', '',
        )),
        'eula.txt': '# Review https://aka.ms/MinecraftEULA before changing this value.\neula=false\n',
        'whitelist.json': json.dumps([profile(name) for name in NAMES], indent=2) + '\n',
        'ops.json': json.dumps([dict(profile(name), level=4, bypassesPlayerLimit=False)
                               for name in NAMES], indent=2) + '\n',
    }
    for name, content in files.items():
        target = RUN / name
        if not target.exists():
            target.write_text(content)
    print(f'Isolated server: {RUN}\nAddress: 127.0.0.1:25585', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'run'))
    args = parser.parse_args()
    prepare()
    if args.action == 'prepare':
        return
    properties = dict(line.split('=', 1) for line in (RUN / 'server.properties').read_text().splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
    if properties.get('server-ip') != '127.0.0.1' or properties.get('server-port') != '25585':
        raise SystemExit('Refusing to run: this helper is restricted to 127.0.0.1:25585.')
    eula = [line.strip() for line in (RUN / 'eula.txt').read_text().splitlines()]
    if 'eula=true' not in eula:
        raise SystemExit(f'EULA acceptance is required before running. Review the terms, then edit {RUN / "eula.txt"}.')
    os.chdir(ROOT)
    os.execv(str(ROOT / 'scripts/p0-gradle.sh'), ['p0-gradle.sh', ':platforms:mc263:runServer', '--console=plain'])


if __name__ == '__main__':
    main()
