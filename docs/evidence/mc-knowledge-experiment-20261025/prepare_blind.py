#!/usr/bin/env python3
import json, random, re, sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent/'analysis-deps'))
import tiktoken
BASE=Path(__file__).resolve().parent
ENC=tiktoken.get_encoding('o200k_base')

def mask(text):
    text=re.sub(r'/private/tmp/hardrush-mc-experiment-20261025/run-\d+/workspace','<workspace>',text)
    text=re.sub(r'/tmp/hardrush-mc-experiment-20261025/run-\d+/workspace','<workspace>',text)
    return text

def main():
    manifest=json.loads((BASE/'manifest.json').read_text())
    ids=[r['id'] for r in manifest['runs']]
    random.Random(618032).shuffle(ids)
    mapping={name:f'case-{i+1:02d}' for i,name in enumerate(ids)}
    (BASE/'blind-map.json').write_text(json.dumps(mapping,indent=2)+'\n')
    blind=BASE/'blind';blind.mkdir(exist_ok=True)
    for row in manifest['runs']:
        run=BASE/row['id'];label=mapping[row['id']]
        if not (run/'status.json').exists():continue
        status=json.loads((run/'status.json').read_text())
        if status['state']!='completed':continue
        (blind/f'{label}-answer.md').write_text(mask((run/'answer.md').read_text()))
        outputs=json.loads((run/'tool-outputs.json').read_text())
        parts=[ENC.encode(o['text'],disallowed_special=()) for o in outputs]
        population=sum(map(len,parts));rng=random.Random(20261025+int(row['id'][-2:]))
        samples=[]
        for i in range(32):
            location=rng.randrange(i*population//32,(i+1)*population//32)
            rem=location
            for j,part in enumerate(parts):
                if rem<len(part):break
                rem-=len(part)
            start=max(0,min(rem-64,len(part)-128));end=min(len(part),start+128)
            samples.append(dict(sample_id=f'{label}-{i+1:02d}',tool_output_index=j+1,start_token=start,end_token=end,
                before=mask(ENC.decode(part[max(0,start-64):start])),
                target=mask(ENC.decode(part[start:end])),
                after=mask(ENC.decode(part[end:end+64]))))
        (blind/f'{label}-samples.json').write_text(json.dumps(samples,ensure_ascii=False,indent=2)+'\n')
    print('ready',len(list(blind.glob('*-answer.md'))))

if __name__=='__main__':main()
