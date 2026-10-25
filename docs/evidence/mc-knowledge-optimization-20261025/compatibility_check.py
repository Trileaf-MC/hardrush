#!/usr/bin/env python3
"""Compare legacy and optimized JSON semantics against identical local artifacts."""
from pathlib import Path
import json, os, subprocess, time
BASE=Path(__file__).resolve().parent
REPO=Path('/Users/leifwebber/projects/minecraft/hardrush')
OLD=BASE/'baseline/scripts/mc-knowledge.py'
NEW=REPO/'scripts/mc-knowledge.py'
CACHE=BASE/'compatibility-cache'
OUT=BASE/'compatibility';OUT.mkdir(exist_ok=True)
QUERIES=[
 ['doctor'],['find-class','Skeleton'],['outline','Skeleton','--member','tick'],
 ['source','Skeleton','--member','tick'],['source','Skeleton','--member','<init>'],
 ['source','Wolf','--member','registerGoals'],
 ['data','damage_type/mob_attack','--pointer','/scaling'],
 ['data','tags/entity_type/freeze_immune_entity_types'],
 ['source','Mob','--member','convertTo'],
 ['source','TamableAnimal','--member','reassessTameGoals'],
 ['source','DefinitelyMissingEntity'],['search','ConversionTracker']]
VOLATILE={'elapsed_ms','output_chars','cache_hit'}
def check_subset(a,b,path=''):
 if isinstance(a,dict):
  assert isinstance(b,dict),(path,type(b))
  for key,value in a.items():
   if key in VOLATILE:continue
   # Human-readable error guidance can improve; error code and candidates stay meaningful.
   if path=='error' and key=='message':continue
   assert key in b,(path,key,'missing')
   check_subset(value,b[key],(path+'.'+key).lstrip('.'))
 elif isinstance(a,list):
  assert isinstance(b,list) and len(a)==len(b),(path,'list length',len(a),len(b))
  for i,(v,w) in enumerate(zip(a,b)):check_subset(v,w,f'{path}[{i}]')
 else:assert a==b,(path,a,b)
def call(script,query,extra=()):
 env=os.environ.copy();env.pop('MC_KNOWLEDGE_FORMAT',None)
 args=['python3',str(script),'--project',str(REPO),'--cache-dir',str(CACHE),*query,*extra]
 p=subprocess.run(args,capture_output=True,text=True,env=env,timeout=90)
 return p.returncode,json.loads(p.stdout),p.stderr
def main():
 results=[]
 for i,q in enumerate(QUERIES,1):
  old=call(OLD,q);new=call(NEW,q)
  # Repeated source metadata is content-addressed; compare after both share the same warmed cache.
  old=call(OLD,q);new=call(NEW,q)
  passed=True;error=None
  try:
   assert old[0]==new[0],('exit',old[0],new[0])
   check_subset(old[1],new[1])
  except AssertionError as e:passed=False;error=str(e)
  result={'index':i,'query':q,'passed':passed,'error':error,'old_exit':old[0],'new_exit':new[0]}
  (OUT/f'{i:02d}.json').write_text(json.dumps({'result':result,'legacy':old[1],'optimized':new[1]},ensure_ascii=False,indent=2)+'\n')
  results.append(result);print(json.dumps(result,ensure_ascii=False),flush=True)
 (OUT/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
 if not all(r['passed'] for r in results):raise SystemExit(1)
if __name__=='__main__':main()
