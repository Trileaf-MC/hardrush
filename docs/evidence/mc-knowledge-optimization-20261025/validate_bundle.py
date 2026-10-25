from pathlib import Path
import json,re,hashlib

ROOT=Path('/Users/leifwebber/projects/minecraft/hardrush')
D=ROOT/'docs/evidence/mc-knowledge-optimization-20261025'
B=Path(__file__).resolve().parent
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
 missing=[];checked=0
 for p in [D/'report.md',D/'README.md',D/'process-review.md',*D.glob('runs/run-*/attempt-01/portable-answer.md')]:
  for match in re.finditer(r'\[[^\]\n]*\]\((<[^>\n]+>|[^)\s]+)\)',p.read_text()):
   target=match.group(1).strip('<>')
   if re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:',target) and not target.startswith('/'):continue
   if target.startswith('#'):continue
   target=re.sub(r'(?::\d+|#L\d+)$','',target)
   resolved=Path(target) if target.startswith('/') else p.parent/target
   checked+=1
   if not resolved.exists():missing.append({'file':str(p.relative_to(D)),'target':target})
 assert not missing,missing
 metrics=json.loads((D/'metrics.json').read_text())
 assert len(metrics)==27 and all(r['state']=='completed' and r['model_effort_verified'] for r in metrics)
 assert len(list((D/'grading-1').glob('case-*.json')))==27 and len(list((D/'grading-2').glob('case-*.json')))==27
 for m in metrics:
  p=D/f'runs/{m["id"]}/attempt-01'
  for record in json.loads((p/'original-file-hashes.json').read_text()):
   assert sha(p/record['path'])==record['sha256'],(m['id'],record['path'])
 for name in ('scripts/mc-knowledge.py','scripts/mc_knowledge_output.py','scripts/mc_knowledge_wiki.py','.agents/skills/minecraft-knowledge/SKILL.md'):
  assert (ROOT/name).read_bytes()==(D/'delivery'/name).read_bytes(),name
 for name in ('scripts/mc_knowledge_wiki.py','scripts/test_mc_knowledge.py','scripts/test_mc_knowledge_wiki.py'):
  assert (ROOT/name).read_bytes()==(D/'baseline'/name).read_bytes(),name
 if B != D:
  assert (D/'process-review-original.md').read_bytes()==(B/'process-review.md').read_bytes()
 validation={'local_markdown_links_checked':checked,'missing_links':missing,'formal_runs':27,'independent_reviews':54,'original_answers_and_logs_sha256_match':True,'delivery_matches_workspace':True,'unrelated_existing_wiki_and_tests_unchanged':True,'web_citation_markup_answers':[m['id'] for m in metrics if '' in (D/f'runs/{m["id"]}/attempt-01/answer.md').read_text()]}
 (D/'bundle-validation.json').write_text(json.dumps(validation,ensure_ascii=False,indent=2)+'\n')
 files=[p for p in sorted(D.rglob('*')) if p.is_file() and p not in (D/'bundle-manifest.json',D/'SHA256SUMS')]
 manifest={'files':[{'path':str(p.relative_to(D)),'bytes':p.stat().st_size,'sha256':sha(p)} for p in files],'note':'Final bundle after report, portable sources and delivery documentation. Original formal answer/log hashes separately preserved.'}
 (D/'bundle-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
 files=[p for p in sorted(D.rglob('*')) if p.is_file() and p!=D/'SHA256SUMS']
 (D/'SHA256SUMS').write_text(''.join(sha(p)+'  '+str(p.relative_to(D))+'\n' for p in files))
 print(json.dumps({**validation,'bundle_files':len(files)+1,'bundle_bytes':sum(p.stat().st_size for p in files)+(D/'SHA256SUMS').stat().st_size},ensure_ascii=False))

if __name__=='__main__':main()
