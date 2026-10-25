import subprocess,json,sys,pathlib
root=pathlib.Path('/tmp/hardrush-mc-optimization-20261025/reference')
name=sys.argv[1]; args=sys.argv[2:]
cmd=['python3','/tmp/hardrush-mc-optimization-20261025/baseline/scripts/mc-knowledge.py','--project','/Users/leifwebber/projects/minecraft/hardrush','--cache-dir',str(root/'cache'),*args]
p=subprocess.run(cmd,text=True,capture_output=True)
(root/'raw'/f'{name}.json').write_text(p.stdout)
with (root/'queries.jsonl').open('a') as f:f.write(json.dumps({'name':name,'argv':cmd,'exitcode':p.returncode,'stderr':p.stderr},ensure_ascii=False)+'\n')
print(p.stdout)
if p.stderr:print(p.stderr,file=sys.stderr)
sys.exit(p.returncode)
