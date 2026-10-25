from pathlib import Path
from collections import Counter
import csv, json, statistics as st

BASE=Path(__file__).resolve().parent
MAXIMA={
 'T1':{'V1':5,'S1':5,'S2':10,'S3':5,'S4':10,'S5':5,'S6':10,'W1':10,'W2':10,'W3':10,'W4':10,'E1':10},
 'T2':dict(zip(['A1','A2','A3','A4','A5','A6','E1'],[12,24,14,10,14,16,10])),
 'T3':dict(zip(['P1','P2','P3','P4','P5','P6','P7','E1'],[18,8,16,8,10,16,14,10]))}

def read(p):return json.loads(p.read_text())
def stats(v):
 v=[x for x in v if isinstance(x,(int,float))]
 return {'n':len(v),'median':st.median(v) if v else None,'mean':st.mean(v) if v else None,'min':min(v) if v else None,'max':max(v) if v else None}

def main():
 metrics=read(BASE/'metrics.json');mapping=read(BASE/'blind-map.json');rows=[];issues=[]
 for m in metrics:
  case=mapping[m['id']];reviews=[read(BASE/f'grading-{n}/{case}.json') for n in (1,2)]
  for n,g in enumerate(reviews,1):
   maximum=MAXIMA[g['topic']];items=g['items'];points={i['id']:i['earned'] for i in items};s=g['rubric_scores']
   assert len(points)==len(items) and set(points)==set(maximum),(case,n,'items')
   assert all(i['maximum']==maximum[i['id']] and 0<=i['earned']<=i['maximum'] for i in items),(case,n,'range')
   assert s['total']==sum(points.values()) and s['evidence_quality']==points['E1'] and s['factual_accuracy']+s['evidence_quality']==s['total'],(case,n,'sum')
  quality={k:st.mean(g['rubric_scores'][k] for g in reviews) for k in ('factual_accuracy','evidence_quality','total')}
  usage=m.get('native_cumulative_usage') or m.get('cli_cumulative_usage') or {}
  r={**{k:m.get(k) for k in ('id','topic','arm','repeat','state','elapsed_seconds','first_input_tokens','peak_input_tokens','model_calls','command_failures','tool_responses','tool_output_tokens_o200k','tool_output_decoded_text_tokens_o200k')},
     **{'cumulative_'+k:usage.get(k) for k in ('input_tokens','cached_input_tokens','output_tokens','reasoning_output_tokens')},
     'case_id':case,'grade_1':reviews[0]['rubric_scores']['total'],'grade_2':reviews[1]['rubric_scores']['total'],
     'quality_mean':quality['total'],'facts_mean':quality['factual_accuracy'],'evidence_mean':quality['evidence_quality'],
     'grade_gap':abs(reviews[0]['rubric_scores']['total']-reviews[1]['rubric_scores']['total'])}
  rows.append(r)
  issues.append({'id':m['id'],'case_id':case,'topic':m['topic'],'arm':m['arm'],'reviews':[{'reviewer':n,'major_errors':g.get('major_errors',[]),'omissions':g.get('omissions',[])} for n,g in enumerate(reviews,1)]})
 fields=['peak_input_tokens','tool_output_tokens_o200k','tool_output_decoded_text_tokens_o200k','elapsed_seconds','quality_mean','facts_mean','evidence_mean','model_calls','cumulative_input_tokens','cumulative_cached_input_tokens','cumulative_output_tokens']
 groups={}
 for topic in ('all','T1','T2','T3'):
  for arm in ('J','O','S'):
   selected=[r for r in rows if r['arm']==arm and (topic=='all' or r['topic']==topic)]
   groups[topic+'/'+arm]={k:stats([r[k] for r in selected]) for k in fields}
 contrasts={}
 for topic in ('all','T1','T2','T3'):
  for a,b in (('J','O'),('O','S'),('J','S')):
   contrast={}
   for field in fields:
    before=groups[topic+'/'+a][field]['median'];after=groups[topic+'/'+b][field]['median']
    contrast[field]={'before_median':before,'after_median':after,'change':after-before if before is not None and after is not None else None,'relative_change':(after-before)/before if before else None}
   contrasts[f'{topic}/{a}->{b}']=contrast
 paired=[]
 for topic in ('T1','T2','T3'):
  for repeat in (1,2,3):
   selected={r['arm']:r for r in rows if r['topic']==topic and r['repeat']==repeat}
   for a,b in (('J','O'),('O','S')):
    paired.append({'topic':topic,'repeat':repeat,'contrast':a+'->'+b,**{field:selected[b][field]-selected[a][field] if selected[a][field] is not None and selected[b][field] is not None else None for field in fields}})
 format_summary={}
 for arm in ('J','O','S'):
  selected=[m.get('cli_event_format_adoption',{}) for m in metrics if m['arm']==arm]
  format_summary[arm]=dict(sum((Counter({k:v for k,v in x.items() if isinstance(v,int) and not isinstance(v,bool)}) for x in selected),Counter()))
 result={'n':len(rows),'states':dict(Counter(r['state'] for r in rows)),'groups':groups,'contrasts':contrasts,'paired_differences':paired,'grader_gap':stats([r['grade_gap'] for r in rows]),'grader_gap_at_least_5':[r['case_id'] for r in rows if r['grade_gap']>=5],'all_model_effort_verified':all(m['model_effort_verified'] for m in metrics),'all_native_cli_usage_equal':all(m['native_cli_usage_equal'] for m in metrics),'format_event_summary':format_summary,'rows':rows,'quality_findings':issues}
 (BASE/'analysis-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 with (BASE/'graded-results.csv').open('w') as f:
  w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
 print(json.dumps({k:v for k,v in result.items() if k not in ('quality_findings','rows','paired_differences','groups')},ensure_ascii=False,indent=2))

if __name__=='__main__':main()
