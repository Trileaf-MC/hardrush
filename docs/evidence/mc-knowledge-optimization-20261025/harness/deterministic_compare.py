#!/usr/bin/env python3
"""Fixed local queries, identical frozen CLI, JSON versus compact. No model calls."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
sys.path.insert(0, '/tmp/hardrush-mc-experiment-20261025/analysis-deps')
import tiktoken
from common import BASE, CONFIGS, common_env, dump, read, sha, verify_seal

ENC = tiktoken.get_encoding('o200k_base')
# Fixed before participant outcomes. Original compatibility-query indices retained.
# Error-recovery queries 9/10/11 and cache-state-dependent search 12 are excluded.
QUERIES = [
    {'id':'q03', 'compatibility_index':3, 'argv':['outline','Skeleton','--member','tick']},
    {'id':'q04', 'compatibility_index':4, 'argv':['source','Skeleton','--member','tick']},
    {'id':'q05', 'compatibility_index':5, 'argv':['source','Skeleton','--member','<init>']},
    {'id':'q06', 'compatibility_index':6, 'argv':['source','Wolf','--member','registerGoals']},
    {'id':'q07', 'compatibility_index':7, 'argv':['data','damage_type/mob_attack','--pointer','/scaling']},
    {'id':'q08', 'compatibility_index':8, 'argv':['data','tags/entity_type/freeze_immune_entity_types']},
]


def tokens(text):
    return len(ENC.encode(text, disallowed_special=()))


def parse_response(stdout, format_name):
    if format_name == 'json':
        metadata = json.loads(stdout)
        return metadata, metadata.get('text', '')
    header, separator, body = stdout.partition('\n')
    if not separator:
        raise ValueError('Compact output has no first-line metadata delimiter.')
    metadata = json.loads(header)
    if metadata.get('format') != 'compact':
        raise ValueError('Compact format marker missing.')
    count = metadata.get('metrics', {}).get('body_chars')
    if isinstance(count, int):
        # Renderer adds a final LF after the body; its own count is unambiguous.
        if body[count:] != '\n':
            raise ValueError('Compact body extent must be followed by exactly one LF.')
        body = body[:count]
    else:
        raise ValueError('Compact body_chars must be an integer.')
    return metadata, body


def call(cli, workspace, cache, query, format_name, cursor, target):
    target.mkdir(parents=True, exist_ok=False)
    argv = [sys.executable, str(cli), '--project', str(workspace), '--cache-dir', str(cache),
            *query, *cursor]
    env = {**common_env(), 'MC_KNOWLEDGE_FORMAT': format_name}
    dump(target/'invocation.json', {'argv':argv,'MC_KNOWLEDGE_FORMAT':format_name,
                                    'note':'No explicit output-format option; exercise environment default.'})
    try:
        child = subprocess.run(argv, capture_output=True, text=True, env=env, cwd=workspace, timeout=120)
        (target/'stdout.txt').write_text(child.stdout)
        (target/'stderr.txt').write_text(child.stderr)
        metadata, body = parse_response(child.stdout, format_name)
        result = {'state':'completed' if child.returncode == 0 and metadata.get('ok', True) else 'cli_error',
                  'exit_code':child.returncode, 'stdout_chars':len(child.stdout), 'stdout_tokens_o200k':tokens(child.stdout),
                  'body_chars':len(body), 'body_tokens_o200k':tokens(body),
                  'truncated':metadata.get('truncated', False),
                  'output_truncated':metadata.get('output_truncated', False),
                  'body_truncated':metadata.get('body_truncated', False),
                  'completeness':metadata.get('completeness'),
                  'returned_range':metadata.get('returned_range'), 'next':metadata.get('next'),
                  'evidence_reference_present':bool(metadata.get('evidence')),
                  'source_identity':metadata.get('source'), 'body_sha256':hashlib.sha256(body.encode()).hexdigest()}
        dump(target/'measurement.json', result)
        return result, metadata, body
    except (subprocess.TimeoutExpired, ValueError, OSError) as error:
        result = {'state':'measurement_error','error_type':type(error).__name__,'message':str(error)}
        dump(target/'measurement.json', result)
        return result, {}, ''


def provenance_read(metadata, workspace, target):
    """A separate explicit policy: obtain full JAR SHA256 for first-page provenance."""
    source = metadata.get('source', {})
    if source.get('jar_sha256'):
        return {'policy':'full first-page source JAR SHA256', 'evidence_reads':0,
                'extra_file_chars':0, 'extra_file_tokens_o200k':0, 'verified':True}
    path_value = metadata.get('evidence', {}).get('path')
    if not path_value:
        return {'policy':'full first-page source JAR SHA256', 'evidence_reads':0, 'verified':False,
                'reason':'No full JAR hash or readable evidence reference.'}
    path = Path(path_value).resolve()
    if not path.is_relative_to(workspace.resolve()):
        raise ValueError('Evidence reference outside this isolated deterministic workspace.')
    text = path.read_text()
    record = json.loads(text)
    digest = hashlib.sha256(text.encode()).hexdigest()
    if path.stem != digest:
        raise ValueError('Content-addressed evidence checksum mismatch.')
    (target/'first-page-evidence-read.txt').write_text(text)
    return {'policy':'full first-page source JAR SHA256', 'evidence_reads':1,
            'extra_file_chars':len(text), 'extra_file_tokens_o200k':tokens(text),
            'verified':bool(record.get('result', {}).get('source', {}).get('jar_sha256')),
            'sha256':digest,
            'note':'Explicit provenance-reading policy; not an assertion that an LLM would read this artifact.'}


def measure_format(cli, workspace, cache, query, format_name, target, max_pages):
    pages, bodies, cursor, seen = [], [], [], set()
    first_metadata = {}
    for page in range(1, max_pages+1):
        item, metadata, body = call(cli, workspace, cache, query['argv'], format_name, cursor,
                                    target/f'page-{page:03d}')
        pages.append(item)
        bodies.append(body)
        if page == 1:
            first_metadata = metadata
        if item['state'] != 'completed' or not metadata.get('next'):
            break
        following = metadata['next']
        position = (following.get('start_line'), following.get('start_char', 0))
        if position in seen or not isinstance(position[0], int):
            pages.append({'state':'continuation_error','message':'Repeated or invalid cursor.'})
            break
        seen.add(position)
        cursor = ['--start-line', str(position[0]), '--start-char', str(position[1])]
    complete = bool(pages) and all(p['state'] == 'completed' for p in pages) and not pages[-1].get('next')
    body = ''.join(bodies)
    provenance = provenance_read(first_metadata, workspace, target) if first_metadata else {'verified':False,'evidence_reads':0}
    result = {'format':format_name,'pages':pages,'complete_body_verified':complete,
              'cli_calls':sum('stdout_chars' in p for p in pages),
              'continuation_calls':max(0,sum('stdout_chars' in p for p in pages)-1),
              'first_stdout_chars':pages[0].get('stdout_chars'),
              'first_stdout_tokens_o200k':pages[0].get('stdout_tokens_o200k'),
              'all_stdout_chars':sum(p.get('stdout_chars',0) for p in pages),
              'all_stdout_tokens_o200k':sum(p.get('stdout_tokens_o200k',0) for p in pages),
              'truncated_response_count':sum(bool(p.get('truncated')) for p in pages),
              'output_truncated_response_count':sum(bool(p.get('output_truncated')) for p in pages),
              'evidence_reference_count':sum(bool(p.get('evidence_reference_present')) for p in pages),
              'combined_body_chars':len(body),'combined_body_sha256':hashlib.sha256(body.encode()).hexdigest(),
              'first_page_full_jar_provenance_policy':provenance}
    (target/'combined-body.txt').write_text(body)
    dump(target/'summary.json', result)
    return result


def compare(json_result, compact_result):
    value = {'both_complete':json_result['complete_body_verified'] and compact_result['complete_body_verified'],
             'same_complete_body':json_result['combined_body_sha256'] == compact_result['combined_body_sha256'],
             'different_first_body_slices':json_result['pages'][0].get('body_sha256') != compact_result['pages'][0].get('body_sha256')}
    for field in ('first_stdout_chars','first_stdout_tokens_o200k','all_stdout_chars','all_stdout_tokens_o200k',
                  'cli_calls','continuation_calls','truncated_response_count','output_truncated_response_count'):
        before, after = json_result.get(field), compact_result.get(field)
        value[field+'_compact_minus_json'] = after-before if before is not None and after is not None else None
        if field.endswith(('chars','o200k')):
            value[field+'_reduction_fraction'] = (before-after)/before if before else None
    for format_name, result in (('json', json_result), ('compact', compact_result)):
        provenance = result['first_page_full_jar_provenance_policy']
        value[format_name+'_provenance_evidence_reads'] = provenance.get('evidence_reads')
        value[format_name+'_first_response_plus_provenance_tokens_o200k'] = ((result['first_stdout_tokens_o200k'] or 0)
                                                   +provenance.get('extra_file_tokens_o200k',0))
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--plan', action='store_true')
    mode.add_argument('--run', action='store_true', help='Run local Python CLI only; never invokes a model.')
    parser.add_argument('--output', type=Path, default=BASE/'deterministic-output')
    parser.add_argument('--max-pages', type=int, default=100)
    options = parser.parse_args()
    if not options.run:
        print(json.dumps({'queries':QUERIES, 'would_call_models':False,
             'measures':['first response','complete-body continuation','optional full first-page JAR-provenance policy'],
             'default_json':'legacy unrestricted stdout; default body page limits retained',
             'default_compact':'6000-character whole-response budget; same body page limits',
             'note':'Actual local CLI calls require explicit --run after final CLI is sealed.'},ensure_ascii=False,indent=2))
        return
    if options.max_pages <= 0:
        parser.error('--max-pages must be positive')
    seal = verify_seal()
    output = options.output.resolve()
    if not output.is_relative_to(BASE.resolve()) or output.exists():
        raise SystemExit('Output must be a new directory inside this lab.')
    output.mkdir(parents=True)
    workspace = output/'workspace'
    workspace.mkdir()
    for rel in CONFIGS:
        destination = workspace/rel
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(BASE/'inputs'/rel,destination)
    cache = workspace/'.local/mc-knowledge'
    cache.mkdir()
    cli = BASE/'final/scripts/mc-knowledge.py'
    dump(output/'manifest.json', {'queries':QUERIES,'frozen_source_sha256':seal['sha256'],
         'cli_sha256':sha(cli), 'max_pages':options.max_pages,
         'model_calls':0, 'participant_results_read':False,
         'cache_policy':'Shared isolated generated cache. One JSON warm-up per query, then measured formats in alternating order. Raw Gradle inputs unchanged.',
         'measurement_scope':'Exact standalone CLI stdout, not tool wrappers or native model billing.',
         'limitations':['Fixed deterministic query policy; wall-clock timing fields can still vary.',
                        'Default compact whole-output cap differs from default JSON; first responses may contain different body extents.',
                        'Complete-body comparisons require equal reconstructed body hashes and no unresolved continuation.',
                        'Provenance file reads are a separately specified policy, not observed LLM behavior.',
                        'No inference about LLM correctness, search strategy, cumulative input or causal savings.']})
    results = []
    for index, query in enumerate(QUERIES):
        target = output/query['id']
        target.mkdir()
        warm, _, _ = call(cli, workspace, cache, query['argv'], 'json', [], target/'warmup')
        if warm['state'] != 'completed':
            results.append({'query':query,'state':'warmup_failed','warmup':warm})
            dump(output/'results.json',results)
            continue
        formats = ('json','compact') if index%2 == 0 else ('compact','json')
        measured = {format_name:measure_format(cli,workspace,cache,query,format_name,target/format_name,options.max_pages)
                    for format_name in formats}
        result = {'query':query,'state':'measured','launch_order':formats,**measured,
                  'comparison':compare(measured['json'],measured['compact'])}
        results.append(result)
        dump(output/'results.json',results)
        print(json.dumps({'query':query['id'],**result['comparison']},ensure_ascii=False),flush=True)
    csv_rows = [{'query_id':r['query']['id'],'query_argv':json.dumps(r['query']['argv']),
                 **r['comparison']} for r in results if r.get('state') == 'measured']
    with (output/'comparison.csv').open('w') as stream:
        writer = csv.DictWriter(stream,fieldnames=list(csv_rows[0]) if csv_rows else ['query_id'])
        writer.writeheader()
        writer.writerows(csv_rows)
    dump(output/'summary.json',{'assigned_queries':len(QUERIES),'measured_queries':len(csv_rows),
         'failed_queries':[r['query']['id'] for r in results if r.get('state') != 'measured'],
         'all_complete_bodies_equal':bool(csv_rows) and all(r['both_complete'] and r['same_complete_body'] for r in csv_rows),
         'model_calls':0,'causal_llm_benefit_claimed':False})


if __name__ == '__main__':
    main()
