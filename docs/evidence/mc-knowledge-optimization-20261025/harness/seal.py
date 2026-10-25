#!/usr/bin/env python3
"""Seal root-prepared final snapshot. Does not run research or read references."""
import argparse
import shutil
from common import BASE, REPO, CONFIGS, SKILL, cli_files, dump, source_inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config-root', default=str(REPO))
    args = parser.parse_args()
    seal = BASE / 'frozen-source-manifest.json'
    if seal.exists():
        raise SystemExit('Seal already exists; refusing to replace it.')
    if not (BASE / 'final' / SKILL).is_file():
        raise SystemExit('Root must freeze final/.agents/skills/minecraft-knowledge/SKILL.md first.')
    for p in cli_files(BASE / 'final'):
        if not p.is_file():
            raise SystemExit('Missing final CLI: ' + str(p))
    for rel in CONFIGS:
        target = BASE / 'inputs' / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(__import__('pathlib').Path(args.config_root) / rel, target)
    dump(seal, {'schema': 1, 'sha256': source_inventory(),
                'note': 'Same final CLI in all arms; baseline skill J/O and final skill S. Config frozen once.'})
    print(seal)


if __name__ == '__main__':
    main()
