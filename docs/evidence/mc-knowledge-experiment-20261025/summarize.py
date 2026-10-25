#!/usr/bin/env python3
import csv, json, statistics
from pathlib import Path
BASE=Path(__file__).resolve().parent

def main():
    mapping=json.loads((BASE/'blind-map.json').read_text())
    metrics=json.loads((BASE/'metrics.json').read_text())
    decisions={'case-02':{'S6':6},'case-06':{'S6':6}}
    rows=[]
    for m in metrics:
        case=mapping[m['id']]
        p1=BASE/'grading-1'/f'{case}.json';p2=BASE/'grading-2'/f'{case}.json'
        pn=BASE/'noise-audit'/f'{case}-labels.json'
        if m['state']!='completed' or not all(p.exists() for p in [p1,p2,pn]):continue
        a=json.loads(p1.read_text());b=json.loads(p2.read_text())
        by_a={i['id']:i for i in a['items']};by_b={i['id']:i for i in b['items']}
        points={}
        for key,ia in by_a.items():
            ib=by_b[key]
            if ia['points']==ib['points']:points[key]=ia['points']
            elif key in decisions.get(case,{}):points[key]=decisions[case][key]
            else:raise ValueError(f'Unresolved grading {case} {key}: {ia["points"]} vs {ib["points"]}')
        labels=json.loads(pn.read_text())
        assert len(labels)==32 and all(sum(r[k] for k in 'EOIF')==100 for r in labels)
        noise={k:statistics.mean(r[k] for r in labels) for k in 'EOIF'}
        row={**m,'case':case,'quality':sum(points.values()),'factual':sum(points.values())-points['E1'],'evidence':points['E1'],
             'score_items':points,'rater_1':a['total'],'rater_2':b['total'],
             'noise_percent':noise,'irrelevant_tokens_o200k_estimate':m['tool_output_tokens_o200k']*noise['I']/100,
             'irrelevant_and_failure_tokens_o200k_estimate':m['tool_output_tokens_o200k']*(noise['I']+noise['F'])/100,
             'uncached_input_tokens':m['usage']['input_tokens']-m['usage']['cached_input_tokens'],
             'peak_above_first_input':m['peak_input_tokens']-m['first_input_tokens']}
        rows.append(row)
    groups={}
    keys=['quality','factual','evidence','elapsed_seconds','peak_input_tokens','peak_above_first_input','tool_output_tokens_o200k','tool_output_chars',
          'model_calls','uncached_input_tokens','irrelevant_tokens_o200k_estimate','irrelevant_and_failure_tokens_o200k_estimate']
    for group in 'ABC':
        rs=[r for r in rows if r['group']==group]
        if not rs:continue
        g={'n':len(rs),'median':{},'range':{},'mean':{}}
        values={key:[r[key] for r in rs] for key in keys}
        for key in ['input_tokens','cached_input_tokens','output_tokens','reasoning_output_tokens']:
            values[key]=[r['usage'][key] for r in rs]
        for key in 'EOIF':values[f'noise_{key}_percent']=[r['noise_percent'][key] for r in rs]
        for key,vs in values.items():
            g['median'][key]=statistics.median(vs);g['range'][key]=[min(vs),max(vs)];g['mean'][key]=statistics.mean(vs)
        groups[group]=g
    summary={'trials':rows,'groups':groups,'completed_scored_trials':len(rows),
             'native_usage_total':{k:sum(r['usage'][k] for r in rows) for k in ['input_tokens','cached_input_tokens','output_tokens','reasoning_output_tokens']},
             'adjudications':decisions}
    (BASE/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    (BASE/'adjudication.json').write_text(json.dumps(decisions,ensure_ascii=False,indent=2)+'\n')
    with (BASE/'results-table.csv').open('w') as f:
        names=['id','group','repeat','quality','elapsed_seconds','peak_input_tokens','tool_output_tokens_o200k','input_tokens','cached_input_tokens','output_tokens','reasoning_output_tokens','noise_I_percent','irrelevant_tokens_o200k_estimate']
        w=csv.DictWriter(f,fieldnames=names);w.writeheader()
        for r in rows:w.writerow({k:(r['usage'][k] if k in r['usage'] else r['noise_percent']['I'] if k=='noise_I_percent' else r[k]) for k in names})
    for key,g in groups.items():print(key,g['n'],json.dumps(g['median'],ensure_ascii=False))

if __name__=='__main__':main()
