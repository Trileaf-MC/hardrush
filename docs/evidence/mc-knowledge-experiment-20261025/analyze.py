#!/usr/bin/env python3
"""Export observable logs and native usage; estimate text sizes with o200k_base."""
import collections
import hashlib
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent / 'analysis-deps'))
import tiktoken

BASE=Path(__file__).resolve().parent
ENC=tiktoken.get_encoding('o200k_base')
def tokens(text): return len(ENC.encode(text, disallowed_special=()))
def dump(path, value): path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
def main():
    manifest=json.loads((BASE/'manifest.json').read_text())
    metrics=[]
    for row in manifest['runs']:
        run=BASE/row['id']
        ep=run/'events.jsonl'
        if not ep.exists(): continue
        events=[json.loads(s) for s in ep.read_text().splitlines() if s.strip()]
        tid=next((e['thread_id'] for e in events if e['type']=='thread.started'),None)
        files=list(Path('/Users/leifwebber/.codex/sessions').glob(f'2026/10/25/*{tid}*'))
        if not files: continue
        raw=[json.loads(s) for s in files[0].read_text().splitlines() if s.strip()]
        # Do not export private account identifiers, encrypted reasoning, or internal thought content.
        observable=[]; outputs=[]; calls={}; token_records=[]; context=[]
        for record in raw:
            p=record.get('payload',{}); typ=record['type']
            if typ=='turn_context':
                observable.append({'timestamp':record['timestamp'],'type':'configuration',
                    'model':p.get('model'),'effort':p.get('effort'),'cwd':p.get('cwd'),
                    'sandbox_policy':p.get('sandbox_policy')})
            elif typ=='token_usage_record':
                token_records.append({'timestamp':record['timestamp'],'usage':p['usage']})
            elif typ=='event_msg' and p.get('type')=='token_count' and p.get('info'):
                observable.append({'timestamp':record['timestamp'],'type':'token_count','info':p['info']})
            elif typ=='response_item':
                t=p.get('type')
                if t=='message':
                    o={'timestamp':record['timestamp'],'type':'message','role':p.get('role'),'content':p.get('content',[])}
                    observable.append(o)
                    if p.get('role') in ('developer','user'): context.append(o)
                elif t in ('function_call','custom_tool_call'):
                    o={'timestamp':record['timestamp'],'type':t,'call_id':p.get('call_id'),'name':p.get('name'),
                       'arguments':p.get('arguments',p.get('input',''))}
                    calls[p.get('call_id')]=o; observable.append(o)
                elif t in ('function_call_output','custom_tool_call_output'):
                    output=p.get('output','')
                    if isinstance(output,list):
                        output='\n'.join(part.get('text','') if isinstance(part,dict) else str(part) for part in output)
                    elif not isinstance(output,str): output=json.dumps(output,ensure_ascii=False)
                    o={'timestamp':record['timestamp'],'type':t,'call_id':p.get('call_id'),'output':output}
                    observable.append(o)
                    outputs.append({'index':len(outputs)+1,'call_id':p.get('call_id'),'name':calls.get(p.get('call_id'),{}).get('name'),
                                    'chars':len(output),'estimated_tokens':tokens(output),'text':output,
                                    'call':calls.get(p.get('call_id'),{}).get('arguments','')})
        with (run/'observable.jsonl').open('w') as f:
            for o in observable: f.write(json.dumps(o,ensure_ascii=False)+'\n')
        dump(run/'initial-context.json',context)
        dump(run/'tool-outputs.json',outputs)
        dump(run/'token-records.json',token_records)
        usages=[o['info']['last_token_usage'] for o in observable if o['type']=='token_count']
        completed=[e['item'] for e in events if e['type']=='item.completed' and 'item' in e]
        status=json.loads((run/'status.json').read_text())
        final_usage=next((e['usage'] for e in reversed(events) if e['type']=='turn.completed'),None)
        m={**row,**{k:v for k,v in status.items() if k in ('state','elapsed_seconds','exit_code')},
           'thread_id':tid,'usage':final_usage,
           'first_input_tokens':usages[0]['input_tokens'] if usages else None,
           'peak_input_tokens':max((u['input_tokens'] for u in usages),default=None),
           'model_calls':len(token_records),
           'tool_responses':len(outputs),'tool_output_chars':sum(o['chars'] for o in outputs),
           'tool_output_tokens_o200k':sum(o['estimated_tokens'] for o in outputs),
           'tool_output_unique_tokens_o200k':sum(tokens(t) for t in set(o['text'] for o in outputs)),
           'outer_output_truncations':sum('Warning: truncated output' in o['text'] for o in outputs),
           'cli_item_counts':dict(collections.Counter(i['type'] for i in completed)),
           'command_failures':sum(i['type']=='command_execution' and i.get('exit_code',0)!=0 for i in completed),
           'initial_text_tokens_o200k':sum(tokens(c.get('text','')) for o in context for c in o['content']),
           'compactions':sum(r['type']=='compacted' for r in raw),
           'model_configurations':[{k:o.get(k) for k in ('model','effort')} for o in observable if o['type']=='configuration']}
        dump(run/'metrics.json',m); metrics.append(m)
        # Human-readable complete command/output log; excludes internal reasoning.
        lines=[f"# {row['id']} observable research log",'']
        for o in observable:
            if o['type']=='message' and o['role']=='assistant':
                lines += ['## Assistant message',* [c.get('text','') for c in o['content']], '']
            elif o['type'] in ('function_call','custom_tool_call'):
                lines += [f"## Tool call: {o['name']}",'```text',o['arguments'],'```','']
            elif o['type'] in ('function_call_output','custom_tool_call_output'):
                lines += ['## Tool result','```text',o['output'],'```','']
        (run/'transcript.md').write_text('\n'.join(lines))
    dump(BASE/'metrics.json',metrics)
    for m in metrics: print(json.dumps({k:m.get(k) for k in ('id','group','state','elapsed_seconds','first_input_tokens','peak_input_tokens','tool_output_chars','tool_output_tokens_o200k','model_calls')},ensure_ascii=False))

if __name__=='__main__': main()
