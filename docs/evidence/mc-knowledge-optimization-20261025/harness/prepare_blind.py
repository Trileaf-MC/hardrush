#!/usr/bin/env python3
"""Create two independent blinded review packages, without opening any answer keys."""
import argparse
import json
import random
import re
from common import BASE, read, dump, sha_text

BLIND_SEED = 618032


def mask(text):
    text = re.sub(r'/(?:private/)?tmp/hardrush-mc-optimization-20261025/runs/run-\d+/attempt-\d+/workspace', '<workspace>', text)
    text = re.sub(r'/(?:private/)?tmp/hardrush-mc-optimization-20261025/runs/run-\d+/attempt-\d+', '<run>', text)
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    manifest = read(BASE / 'manifest.json')
    rows = list(manifest['runs'])
    ids = [r['id'] for r in rows]
    random.Random(BLIND_SEED).shuffle(ids)
    mapping = {name: f'case-{i+1:02d}' for i, name in enumerate(ids)}
    mapping_path = BASE / 'blind-map.json'
    if mapping_path.exists() and read(mapping_path) != mapping:
        raise SystemExit('Existing blind map differs; refuse to change case identity.')
    dump(mapping_path, mapping)
    counts = {}
    for reviewer in (1, 2):
        destination = BASE / 'blind' / f'reviewer-{reviewer}'
        destination.mkdir(parents=True, exist_ok=True)
        index = []
        for row in rows:
            label = mapping[row['id']]
            target = BASE / 'runs' / row['id'] / 'attempt-01'
            status = read(target / 'status.json') if (target/'status.json').exists() else {'state': 'not_started'}
            answer = (target/'answer.md').read_text() if (target/'answer.md').exists() else ''
            item = {'case_id': label, 'topic': row['topic'], 'execution_state': status['state'],
                    'question': manifest['topics'][row['topic']], 'answer': mask(answer),
                    'answer_present': bool(answer.strip()), 'partial_answer': status['state'] != 'completed'}
            dump(destination / f'{label}.json', item)
            index.append({k: item[k] for k in ('case_id', 'topic', 'execution_state', 'answer_present', 'partial_answer')})
        random.Random(BLIND_SEED+reviewer).shuffle(index)
        dump(destination/'index.json', index)
        (destination/'REVIEW.md').write_text('''请独立评审本目录中的匿名答案。请勿查看其他评分者的结果、blind-map.json、实验分组、工作目录、成本指标或运行顺序。

先仅使用 case 文件中的 question 与 answer，配合主持人另行提供的相应题目冻结 rubric 评估事实正确性、完整性、出处可复核性与版本限定。请按 rubric 的原始维度和分值评分，逐项写出证据，不因长短或表达自信加分。

如 execution_state 不是 completed 或 answer_present=false，保留该 case 并写明“执行失败/部分答案/不可评”，不得补造答案或跳过样本。不得向实验参与者或其工作目录提供 rubric 或参考答案。

将每个 case 的 JSON 结果写到主持人指定的 grading-N/case-NN.json，结构示例：
{"case_id":"case-01","topic":"T1","assessable":true,"rubric_scores":{},"findings":[],"citations":[]}
不要在评分文件中猜测分组。每位评分者独立完成后，再由主持人比对分歧。
''')
        counts[f'reviewer-{reviewer}'] = len(index)
    dump(BASE/'blind-audit.json', {'seed': BLIND_SEED, 'case_count': len(mapping), 'packages': counts,
          'primary_attempts_only': True, 'answer_keys_read_by_script': False,
          'mapping_sha256': sha_text(json.dumps(mapping, sort_keys=True)),
          'quantitative_noise_grading': False,
          'failure_policy': 'All assigned primary trials retained, including failed/incomplete trials.'})
    print(json.dumps(counts))


if __name__ == '__main__':
    main()
