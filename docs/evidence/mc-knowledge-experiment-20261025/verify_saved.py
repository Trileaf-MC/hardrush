#!/usr/bin/env python3
"""Verify the exported experiment using saved observable evidence only."""
import json, hashlib, re
from pathlib import Path
BASE=Path(__file__).resolve().parent
manifest=json.loads((BASE/'manifest.json').read_text())
summary=json.loads((BASE/'summary.json').read_text())
assert len(manifest['runs'])==9
assert summary['completed_scored_trials']==9
assert all(summary['groups'][g]['n']==3 for g in 'ABC')
for row in manifest['runs']:
    run=BASE/'runs'/row['id']
    metrics=json.loads((run/'metrics.json').read_text())
    events=[json.loads(s) for s in (run/'events.jsonl').read_text().splitlines()]
    observable=[json.loads(s) for s in (run/'observable.jsonl').read_text().splitlines()]
    native=next(e['usage'] for e in reversed(events) if e['type']=='turn.completed')
    assert native==metrics['usage']
    assert metrics['model_configurations']==[{'model':'gpt-6.1-sol','effort':'high'}]
    assert max(o['info']['last_token_usage']['input_tokens'] for o in observable if o['type']=='token_count')==metrics['peak_input_tokens']
    assert metrics['state']=='completed' and metrics['compactions']==0
    assert hashlib.sha256((run/'prompt.txt').read_bytes()).hexdigest()==row['prompt_sha256']
    audit=json.loads((run/'initial-context-audit.json').read_text())
    assert audit['memory_summary_mentions']==0
    assert (audit['minecraft_skill_mentions']>0)==(row['group']=='A')
    for match in re.finditer(r'\]\((/[^)]+)\)',(run/'answer-portable.md').read_text()):
        target=re.sub(r':\d+$','',match.group(1))
        assert Path(target).exists(),target
    assert not any(o.get('role') in ('developer','system') for o in observable)
mapping=json.loads((BASE/'blind-map.json').read_text())
for case in mapping.values():
    labels=json.loads((BASE/'noise-audit'/f'{case}-labels.json').read_text())
    assert len(labels)==32 and len({r['sample_id'] for r in labels})==32
    assert all(sum(r[k] for k in 'EOIF')==100 for r in labels)
    for grader in ['grading-1','grading-2']:
        score=json.loads((BASE/grader/f'{case}.json').read_text())
        assert sum(i['points'] for i in score['items'])==score['total']
assert all(sum(r['usage'][k] for r in summary['trials'])==v for k,v in summary['native_usage_total'].items())
print('PASS: 9 configurations, prompts, native usage/peak values, citations, 18 score sheets, 288 audit samples, and aggregate totals.')
