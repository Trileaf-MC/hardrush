#!/usr/bin/env python3
"""Copy reproducible, non-secret observable evidence into the repository."""
import json, shutil, hashlib, re, tarfile
from pathlib import Path

BASE=Path(__file__).resolve().parent
DEST=Path('/Users/leifwebber/projects/minecraft/hardrush/docs/evidence/mc-knowledge-experiment-20261025')
REFERENCE=Path('/tmp/hardrush-mc-reference-20261025')
def dump(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def cp(a,b):
    b.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(a,b)

def main():
    DEST.mkdir(parents=True,exist_ok=True)
    manifest=json.loads((BASE/'manifest.json').read_text())
    for name in ['manifest.json','results.json','metrics.json','blind-map.json','summary.json','results-table.csv','adjudication.json','isolation-audit.json','comparison-review.md','report-audit.md','run_experiment.py','analyze.py','prepare_blind.py','export_bundle.py','summarize.py','build_report.py','verify_saved.py']:
        if (BASE/name).exists(): cp(BASE/name,DEST/name)
    for name in ['comparison-review.md','report-audit.md']:
        p=DEST/name
        if p.exists():
            text=p.read_text()
            text=re.sub(r'/(?:private/)?tmp/hardrush-mc-experiment-20261025/(run-\d+)',lambda m:str(DEST/'runs'/m.group(1)),text)
            text=re.sub(r'/(?:private/)?tmp/hardrush-mc-experiment-20261025',str(DEST),text)
            text=re.sub(r'/(?:private/)?tmp/hardrush-mc-reference-20261025',str(DEST/'reference'),text)
            p.write_text(text)
    for directory in ['grading-1','grading-2','noise-audit','blind']:
        if (BASE/directory).exists():
            for p in (BASE/directory).glob('*'):
                if p.is_file():cp(p,DEST/directory/p.name)
    for p in REFERENCE.rglob('*'):
        if p.is_file() and 'cache' not in p.relative_to(REFERENCE).parts:
            cp(p,DEST/'reference'/p.relative_to(REFERENCE))
    standard=(REFERENCE/'standard_answer.md').read_text()
    standard=standard.replace('/private/tmp/hardrush-mc-reference-20261025',str(DEST/'reference')).replace('/tmp/hardrush-mc-reference-20261025',str(DEST/'reference'))
    (DEST/'reference'/'standard_answer-portable.md').write_text(standard)
    copied=[]
    for row in manifest['runs']:
        run=BASE/row['id'];out=DEST/'runs'/row['id'];out.mkdir(parents=True,exist_ok=True)
        for name in ['prompt.txt','answer.md','invocation.json','status.json','metrics.json','transcript.md','tool-outputs.json','token-records.json']:
            if (run/name).exists():cp(run/name,out/name)
        if (run/'observable.jsonl').exists():
            obs=[json.loads(s) for s in (run/'observable.jsonl').read_text().splitlines()]
            public=[o for o in obs if o.get('type')!='message' or o.get('role')=='assistant']
            (out/'observable.jsonl').write_text(''.join(json.dumps(o,ensure_ascii=False)+'\n' for o in public))
        if (run/'events.jsonl').exists():
            events=[json.loads(s) for s in (run/'events.jsonl').read_text().splitlines()]
            events=[e for e in events if e.get('item',{}).get('type')!='reasoning']
            (out/'events.jsonl').write_text(''.join(json.dumps(e,ensure_ascii=False)+'\n' for e in events))
        if (run/'initial-context.json').exists():
            initial=json.loads((run/'initial-context.json').read_text())
            text=json.dumps(initial,ensure_ascii=False)
            dump(out/'initial-context-audit.json',{'message_count':len(initial),'minecraft_skill_mentions':text.count('minecraft-knowledge'),
                'memory_summary_mentions':text.count('MEMORY_SUMMARY'),'policy_text_exported':False,
                'text_sha256':hashlib.sha256(text.encode()).hexdigest()})
        workspace=run/'workspace'
        old=out/'workspace-evidence'
        if old.exists():shutil.rmtree(old)
        selected=[]
        for p in workspace.rglob('*'):
            if not p.is_file() or p.suffix not in {'.java','.txt','.json','.md','.py','.properties'}:continue
            if p.stat().st_size>8_000_000:continue
            rel=p.relative_to(workspace)
            if any(part in {'__pycache__','.git','node_modules','python-libs','python_lib','pydeps'} or part.endswith('.dist-info') for part in rel.parts):continue
            selected.append((p,rel))
            copied.append({'run':row['id'],'path':str(rel),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
        with tarfile.open(out/'workspace-evidence.tar.gz','w:gz') as archive:
            for p,rel in sorted(selected):archive.add(p,arcname=str(rel),recursive=False)
        if (run/'answer.md').exists():
            answer=(run/'answer.md').read_text();links={}
            def portable(match):
                target=match.group(1)
                line=re.search(r':(\d+)$',target)
                path=Path(target[:line.start()] if line else target)
                try:rel=path.resolve().relative_to(workspace.resolve())
                except ValueError:return match.group(0)
                if not path.is_file():return match.group(0)
                if str(rel) not in links:
                    name=f'{len(links)+1:02d}-{path.name}'
                    cp(path,out/'sources'/name)
                    links[str(rel)]=name
                replacement=str(out/'sources'/links[str(rel)])+(f':{line.group(1)}' if line else '')
                return ']('+replacement+')'
            answer=re.sub(r'\]\((/[^)]+)\)',portable,answer)
            (out/'answer-portable.md').write_text(answer)
            dump(out/'source-link-map.json',links)
    dump(DEST/'evidence-files.json',copied)
    print(json.dumps({'destination':str(DEST),'evidence_files':len(copied),'evidence_bytes':sum(p['bytes'] for p in copied)}))

if __name__=='__main__':main()
