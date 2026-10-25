#!/usr/bin/env python3
"""Export a portable public-observation bundle. Defaults inside experiment; no repo writes."""
import argparse
import hashlib
from pathlib import Path
import shutil
from common import BASE, read, dump, attempt_paths

TOP_FILES = ['manifest.json', 'frozen-source-manifest.json', 'runtime-environment.json',
             'results-all-attempts.json', 'metrics.json', 'metrics-all-attempts.json',
             'summary.json', 'results-table.csv', 'isolation-audit.json', 'blind-map.json',
             'blind-audit.json', 'reviews-unblinded.json', 'review-comparison.json']
ATTEMPT_FILES = ['prompt.txt', 'answer.md', 'invocation.json', 'status.json', 'events.jsonl',
                 'metrics.json', 'observable.jsonl', 'initial-context-audit.json',
                 'tool-outputs.json', 'native-token-records.json', 'transcript.md',
                 'format-audit.json', 'cli-event-format-audit.json', 'initial-files.json', 'initial-input-integrity.json']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', type=Path, default=BASE/'export-bundle')
    opts = parser.parse_args()
    destination = opts.destination.resolve()
    if destination.exists():
        raise SystemExit('Destination exists; choose a new directory. No overwrite.')
    # Bound this helper to the authorized experimental directory.
    if not destination.is_relative_to(BASE.resolve()):
        raise SystemExit('Export destination must be inside this experimental directory.')
    destination.mkdir(parents=True)
    copied = []
    def cp(source, relative):
        if source.is_file():
            target = destination/relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            copied.append({'path': str(relative), 'bytes': source.stat().st_size,
                           'sha256': hashlib.sha256(source.read_bytes()).hexdigest()})
    for name in TOP_FILES:
        cp(BASE/name, Path(name))
    for directory in ('harness', 'final', 'baseline', 'inputs', 'blind', 'grading-1', 'grading-2'):
        for source in sorted((BASE/directory).rglob('*')):
            if source.is_file() and '__pycache__' not in source.parts:
                cp(source, source.relative_to(BASE))
    for source in sorted((BASE/'deterministic-output').rglob('*')):
        if source.is_file() and 'workspace' not in source.relative_to(BASE/'deterministic-output').parts:
            cp(source,source.relative_to(BASE))
    for row in read(BASE/'manifest.json')['runs']:
        for attempt in attempt_paths(row):
            for name in ATTEMPT_FILES:
                cp(attempt/name, attempt.relative_to(BASE)/name)
            # These are the paths recorded initially, copied at export/end state.
            # They may have changed. Frozen final/baseline/inputs restore start state.
            initial = read(attempt/'initial-files.json')
            states = []
            workspace_root = attempt/'workspace'
            valid_workspace = not workspace_root.is_symlink() and workspace_root.resolve().is_relative_to(attempt.resolve())
            for rel in initial:
                if Path(rel).is_absolute() or '..' in Path(rel).parts:
                    states.append({'path':rel,'exported':False,'reason':'unsafe_relative_path_not_read'})
                    continue
                source = attempt/'workspace'/rel
                restore = ('final/'+rel if rel.startswith('scripts/') else
                           row['skill_variant']+'/'+rel if rel == '.agents/skills/minecraft-knowledge/SKILL.md' else
                           'inputs/'+rel if rel in ('gradle.properties','.local/toolchains/java-home.txt') else None)
                safe = (valid_workspace and not source.is_symlink() and source.resolve().is_relative_to(workspace_root.resolve()))
                current_hash = hashlib.sha256(source.read_bytes()).hexdigest() if safe and source.is_file() else None
                if safe:
                    cp(source, attempt.relative_to(BASE)/'workspace-inputs'/rel)
                states.append({'path':rel,'expected_start_sha256':initial[rel],
                               'export_end_state_sha256':current_hash,
                               'matches_start':current_hash == initial[rel],
                               'start_snapshot_relative_path':restore,
                               'exported':safe and source.is_file()})
            dump(destination/attempt.relative_to(BASE)/'workspace-inputs-state.json',
                 {'snapshot_phase':'end_of_attempt_export','files':states,
                  'note':'workspace-inputs contains export/end-state bytes, not an initial snapshot. Use the listed frozen final/baseline/inputs path plus initial-files.json hashes to reconstruct start state. No participant file was repaired.'})
    dump(destination/'evidence-files.json', copied)
    (destination/'README.md').write_text('''这是原始实验的可移植可观测证据包。

prompt、参数、状态、过滤推理后的 CLI 事件、最终答案、原生数值 usage、外层工具返回、格式审计及匿名评审均按原始路径保存。harness/ 可重做分析；如要重跑，应使用新的实验目录，并按 README 操作，不能覆写已有结果。

不包含私有 system/developer 指令文本、内部推理、原始账户会话文件或 stderr。公开 transcript/工具返回属于模型实际可见的调查证据；仍应在对外发布前人工检查意外读到的私密资料。

答案内原始本地引用保留其文本，但默认只打包冻结初始 workspace 文件；完整生成的反编译/网页缓存不在此包中。如需移植这些引用，应在人工审查后另行打包，不将原路径误称为可移植链接。

参考答案与评分 rubric 不由此脚本加载或打包，应由主持人单独提供给评分者。blind-map.json 与 reviews-unblinded.json 仅供主持人，在独立盲评完成前不得提供给评分者。
''')
    print(destination)


if __name__ == '__main__':
    main()
