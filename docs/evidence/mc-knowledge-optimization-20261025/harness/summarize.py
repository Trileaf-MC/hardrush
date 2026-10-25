#!/usr/bin/env python3
"""Repeatable descriptive metrics and review export. No reference-answer loading."""
import collections
import csv
import json
import statistics
from common import BASE, read, dump


def stats(values):
    values = [v for v in values if isinstance(v, (int, float))]
    return {'n': len(values), 'mean': statistics.mean(values) if values else None,
            'median': statistics.median(values) if values else None,
            'min': min(values) if values else None, 'max': max(values) if values else None}


def scalar(row):
    usage = row.get('native_cumulative_usage') or row.get('cli_cumulative_usage') or {}
    return {**{k: row.get(k) for k in ('id', 'topic', 'arm', 'repeat', 'wave', 'state', 'elapsed_seconds',
              'first_input_tokens', 'peak_input_tokens', 'model_calls', 'tool_responses',
              'tool_output_tokens_o200k', 'tool_output_chars', 'tool_output_decoded_text_tokens_o200k',
              'tool_output_decoded_text_chars','command_failures', 'model_effort_verified')},
            **{f'cumulative_{k}': usage.get(k) for k in ('input_tokens', 'cached_input_tokens', 'output_tokens', 'reasoning_output_tokens')},
            'initial_input_integrity_state':row.get('initial_input_integrity',{}).get('state'),
            'initial_input_protocol_deviation':row.get('initial_input_integrity',{}).get('protocol_deviation'),
            **{f'format_{k}': row.get('format_adoption', {}).get(k) for k in
               ('identified_invocations', 'confirmed_visible_format', 'unknown_visible_format', 'explicit_format_count', 'explicit_overrides_of_arm', 'protocol_deviations', 'confirmed_default_adoption_rate')},
            **{f'cli_event_format_{k}':row.get('cli_event_format_adoption',{}).get(k) for k in
               ('identified_invocations','confirmed_visible_format','unknown_visible_format','diagnostic_invocations_excluded','explicit_format_count','explicit_overrides_of_arm','protocol_deviations','documented_budget_error_fallbacks','adoption_eligible_invocations','adoption_eligible_confirmed_visible_format','confirmed_default_adoption_rate')}}


def numeric_scores(value, prefix=''):
    result = {}
    if isinstance(value, dict):
        for key, part in value.items():
            path = prefix+'.'+key if prefix else key
            if isinstance(part, (int, float)) and not isinstance(part, bool):
                result[path] = part
            elif isinstance(part, dict):
                result.update(numeric_scores(part, path))
    return result


def main():
    metrics = read(BASE/'metrics.json')
    rows = [scalar(row) for row in metrics]
    with (BASE/'results-table.csv').open('w') as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0]) if rows else ['id'])
        writer.writeheader()
        writer.writerows(rows)
    grouped = {}
    for group in sorted({(r['topic'], r['arm']) for r in rows}):
        selected = [r for r in rows if (r['topic'], r['arm']) == group]
        grouped['/'.join(group)] = {'assigned_n': len(selected),
            'states': dict(collections.Counter(r['state'] for r in selected)),
            'all_assigned_observed': {k: stats([r[k] for r in selected]) for k in
                ('elapsed_seconds', 'peak_input_tokens', 'cumulative_input_tokens', 'cumulative_output_tokens', 'tool_output_tokens_o200k')},
            'completed_only_observed': {k: stats([r[k] for r in selected if r['state'] == 'completed']) for k in
                ('elapsed_seconds', 'peak_input_tokens', 'cumulative_input_tokens', 'cumulative_output_tokens', 'tool_output_tokens_o200k')}}
    reviews, errors = [], []
    mapping = read(BASE/'blind-map.json') if (BASE/'blind-map.json').exists() else {}
    inverse = {v: k for k, v in mapping.items()}
    lookup = {r['id']: r for r in rows}
    for reviewer in (1, 2):
        seen = set()
        for path in sorted((BASE/f'grading-{reviewer}').glob('case-*.json')):
            value = read(path)
            case = value.get('case_id')
            if case not in inverse or case in seen:
                errors.append({'reviewer': reviewer, 'path': str(path.relative_to(BASE)), 'error': 'unknown_or_duplicate_case'})
                continue
            seen.add(case)
            trial = lookup[inverse[case]]
            reviews.append({'reviewer': reviewer, 'run_id': inverse[case], 'arm': trial['arm'],
                            'topic': trial['topic'], 'repeat': trial['repeat'], 'review': value})
        for case in sorted(set(inverse)-seen):
            errors.append({'reviewer': reviewer, 'case_id': case, 'error': 'missing_review'})
    dump(BASE/'reviews-unblinded.json', {'reviews': reviews, 'validation_errors': errors})
    comparisons = []
    by_case = collections.defaultdict(dict)
    score_groups = collections.defaultdict(list)
    for review in reviews:
        case = review['review']['case_id']
        scores = numeric_scores(review['review'].get('rubric_scores', {}))
        by_case[case][review['reviewer']] = scores
        for dimension, score in scores.items():
            score_groups[(review['topic'], review['arm'], review['reviewer'], dimension)].append(score)
    for case in sorted(by_case):
        first, second = by_case[case].get(1), by_case[case].get(2)
        if first is None or second is None:
            comparisons.append({'case_id':case, 'paired':False})
            continue
        dimensions = sorted(set(first)|set(second))
        comparisons.append({'case_id':case, 'paired':True,
            'dimensions': {dimension:{'reviewer_1':first.get(dimension),'reviewer_2':second.get(dimension),
                'difference_1_minus_2':first[dimension]-second[dimension] if dimension in first and dimension in second else None}
                for dimension in dimensions}})
    dump(BASE/'review-comparison.json', {'cases':comparisons,
         'dimension_statistics':[{'topic':topic,'arm':arm,'reviewer':reviewer,'dimension':dimension,**stats(values)}
             for (topic,arm,reviewer,dimension),values in sorted(score_groups.items())],
         'scoring_policy':'Preserve raw rubric numeric fields separately. No invented total, normalization, automatic adjudication or imputation.'})
    dump(BASE/'summary.json', {'primary_n': len(rows), 'groups': grouped,
         'review_count': len(reviews), 'review_validation_errors': errors,
         'limitations': ['Three repeats per topic/arm; descriptive pilot, not a broad efficacy claim.',
                        'Failures remain assigned samples. Completed-only metrics are explicitly labeled.',
                        'Preserved tool token metric estimates stored native output representation including serialization escaping. Supplemental decoded text-part estimates remain separate. Neither is billing or net CLI body.',
                        'Format adoption denominator excludes unknown outputs; unknown counts remain visible.',
                        'No automatic retries or imputation.', 'No rubric or reference answer was loaded by this script.']})
    print(json.dumps({'primary_n': len(rows), 'groups': len(grouped), 'reviews': len(reviews), 'review_validation_errors': len(errors)}))


if __name__ == '__main__':
    main()
