#!/usr/bin/env python3
"""Export observable evidence and native numeric usage; never export private instructions/reasoning."""
import argparse
import collections
import json
from pathlib import Path
import sys
sys.path.insert(0, '/tmp/hardrush-mc-experiment-20261025/analysis-deps')
import tiktoken
from common import BASE, MODEL, EFFORT, read, dump, sha_text, attempt_paths
from format_audit import audit_call, summarize, audit_command_event, summarize_business

ENC = tiktoken.get_encoding('o200k_base')


def tokens(text):
    return len(ENC.encode(text, disallowed_special=()))


def jsonlines(path):
    if not path.exists():
        return []
    result = []
    for number, line in enumerate(path.read_text().splitlines(), 1):
        if line.strip():
            try:
                result.append(json.loads(line))
            except ValueError:
                result.append({'type': 'parse_error', 'line_number': number})
    return result


def session_path(thread_id, sessions_root):
    if not thread_id:
        return None
    candidates = sorted(sessions_root.glob(f'*/*/*/*{thread_id}*'))
    if len(candidates) > 1:
        raise RuntimeError('Multiple native rollout files found for ' + thread_id)
    return candidates[0] if candidates else None


def decoded_text_parts(value):
    if isinstance(value,str):
        return [value]
    if isinstance(value,list):
        return [text for part in value for text in decoded_text_parts(part)]
    if isinstance(value,dict):
        if isinstance(value.get('text'),str):
            return [value['text']]
        if isinstance(value.get('content'),list):
            return decoded_text_parts(value['content'])
    return []


def initial_integrity(target,status):
    if status.get('state') not in ('completed','failed','timeout','failed_missing_answer','failed_harness'):
        return {'state':'pending','protocol_deviation':None,'evaluated_files':0,'changed_paths':[],
                'note':'Not evaluated before terminal attempt status.'}
    workspace_path = target/'workspace'
    workspace = workspace_path.resolve()
    if workspace_path.is_symlink() or not workspace.is_relative_to(target.resolve()):
        return {'state':'unverifiable','protocol_deviation':True,'evaluated_files':0,
                'changed_paths':['workspace'],'reason':'workspace replaced by external/symlink path; not read'}
    path = target/'initial-files.json'
    if not path.exists():
        return {'state':'unverifiable','protocol_deviation':True,'evaluated_files':0,
                'changed_paths':[],'reason':'initial-files.json missing'}
    expected = read(path)
    files = []
    for relative,wanted in sorted(expected.items()):
        local = workspace/relative
        current = None
        if local.is_symlink():
            state = 'replaced_by_symlink'
        elif not local.resolve().is_relative_to(workspace):
            state = 'outside_workspace_not_read'
        elif not local.is_file():
            state = 'missing'
        else:
            current = __import__('hashlib').sha256(local.read_bytes()).hexdigest()
            state = 'unchanged' if current == wanted else 'modified'
        files.append({'path':relative,'expected_sha256':wanted,'current_sha256':current,'state':state})
    changed = [file['path'] for file in files if file['state'] != 'unchanged']
    return {'state':'changed' if changed else 'verified','protocol_deviation':bool(changed),
            'evaluated_files':len(files),'changed_paths':changed,'files':files,
            'note':'End-of-attempt comparison only. No repair, exclusion or status modification.'}


def analyze_attempt(row, target, sessions_root):
    status = read(target / 'status.json') if (target / 'status.json').exists() else {'state': 'not_started'}
    integrity = initial_integrity(target,status)
    dump(target/'initial-input-integrity.json',integrity)
    events = jsonlines(target / 'events.jsonl')
    tid = status.get('thread_id') or next((e.get('thread_id') for e in events if e.get('type') == 'thread.started'), None)
    source = session_path(tid, sessions_root)
    raw = jsonlines(source) if source else []
    observable, outputs, calls, native_usage, initial_audit, configs = [], [], {}, [], [], []
    provider, cli_version = None, None
    compactions = 0
    for record in raw:
        p = record.get('payload', {})
        typ = record.get('type')
        stamp = record.get('timestamp')
        if typ == 'session_meta':
            provider, cli_version = p.get('model_provider'), p.get('cli_version')
        elif typ == 'turn_context':
            config = {k: p.get(k) for k in ('model', 'effort', 'cwd', 'approval_policy', 'sandbox_policy')}
            configs.append(config)
            observable.append({'timestamp': stamp, 'type': 'configuration', **config})
        elif typ == 'token_usage_record':
            usage = {k: p.get(k) for k in ('usage', 'turn_token_usage', 'thread_token_usage')}
            native_usage.append({'timestamp': stamp, **usage})
        elif typ == 'compacted':
            compactions += 1
        elif typ == 'event_msg' and p.get('type') == 'token_count' and p.get('info'):
            observable.append({'timestamp': stamp, 'type': 'token_count', 'info': p['info']})
        elif typ == 'response_item':
            item_type = p.get('type')
            if item_type == 'message':
                role, channel = p.get('role'), p.get('channel')
                if role in ('system', 'developer', 'user'):
                    # An audit of private context without saving its text.
                    text = json.dumps(p.get('content', []), ensure_ascii=False)
                    initial_audit.append({'role': role, 'sha256': sha_text(text),
                        'chars': len(text), 'estimated_tokens_o200k': tokens(text),
                        'memory_summary_mentions': text.count('MEMORY_SUMMARY'),
                        'minecraft_skill_mentions': text.count('minecraft-knowledge'),
                        'text_exported': False})
                elif role == 'assistant' and channel != 'analysis':
                    content = [c.get('text', '') for c in p.get('content', [])
                               if isinstance(c, dict) and c.get('type') in ('output_text', 'text', 'input_text')]
                    if content:
                        observable.append({'timestamp': stamp, 'type': 'message', 'role': 'assistant',
                                           'channel': channel, 'content': content})
            elif item_type in ('function_call', 'custom_tool_call'):
                call = {'timestamp': stamp, 'type': item_type, 'call_id': p.get('call_id'),
                        'name': p.get('name'), 'arguments': p.get('arguments', p.get('input', ''))}
                calls[p.get('call_id')] = call
                observable.append(call)
            elif item_type in ('function_call_output', 'custom_tool_call_output'):
                value = p.get('output', '')
                output = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
                decoded = decoded_text_parts(value)
                observable.append({'timestamp': stamp, 'type': item_type, 'call_id': p.get('call_id'), 'output': output})
                call = calls.get(p.get('call_id'), {})
                outputs.append({'index': len(outputs)+1, 'call_id': p.get('call_id'), 'name': call.get('name'),
                                'chars': len(output), 'estimated_tokens_o200k': tokens(output), 'text': output,
                                'native_output_type': type(value).__name__, 'call': call.get('arguments', ''),
                                'decoded_text_part_count':len(decoded),
                                'decoded_text_chars':sum(len(part) for part in decoded),
                                'decoded_text_tokens_o200k':sum(tokens(part) for part in decoded)})
    # No answer key or reference directory is touched by this analyzer.
    format_rows = []
    for out in outputs:
        format_rows.extend(audit_call(calls.get(out['call_id'], {}), out['text'], row['format_env']))
    dump(target / 'format-audit.json', {'invocations': format_rows, 'summary': summarize(format_rows)})
    event_format_rows = []
    for event in events:
        if event.get('type') == 'item.completed' and event.get('item',{}).get('type') == 'command_execution':
            event_format_rows.extend(audit_command_event(event['item'],row['format_env']))
    dump(target/'cli-event-format-audit.json',{'invocations':event_format_rows,
         'summary':summarize_business(event_format_rows),
         'independent_from_native_outer_audit':True})
    (target / 'observable.jsonl').write_text(''.join(json.dumps(o, ensure_ascii=False)+'\n' for o in observable))
    dump(target / 'initial-context-audit.json', initial_audit)
    dump(target / 'tool-outputs.json', outputs)
    dump(target / 'native-token-records.json', native_usage)
    per_request = [r['usage'] for r in native_usage if r.get('usage')]
    cli_usage = next((e.get('usage') for e in reversed(events) if e.get('type') == 'turn.completed'), None)
    last_total = next((r.get('thread_token_usage') for r in reversed(native_usage) if r.get('thread_token_usage')), None)
    cumulative_sum = dict(collections.Counter({}))
    for usage in per_request:
        for key, value in usage.items():
            if isinstance(value, (int, float)):
                cumulative_sum[key] = cumulative_sum.get(key, 0) + value
    complete_items = [e['item'] for e in events if e.get('type') == 'item.completed' and isinstance(e.get('item'), dict)]
    metric = {**row, 'attempt': target.name, 'is_primary': target.name == 'attempt-01',
              'state': status.get('state'), 'exit_code': status.get('exit_code'),
              'elapsed_seconds': status.get('elapsed_seconds'), 'thread_id': tid,
              'native_rollout_found': source is not None, 'native_rollout_sha256': __import__('hashlib').sha256(source.read_bytes()).hexdigest() if source else None,
              'model_provider': provider, 'cli_version': cli_version,
              'native_model_configurations': configs,
              'model_effort_verified': bool(configs) and all(c['model'] == MODEL and c['effort'] == EFFORT for c in configs),
              'workspace_verified': bool(configs) and all(Path(c['cwd']).resolve() == (target/'workspace').resolve() for c in configs if c.get('cwd')),
              'cli_cumulative_usage': cli_usage,
              'native_cumulative_usage': last_total,
              'native_per_request_sum': cumulative_sum or None,
              'native_cli_usage_equal': all(cli_usage.get(k) == v for k, v in last_total.items() if k in cli_usage) if cli_usage and last_total else None,
              'first_input_tokens': per_request[0].get('input_tokens') if per_request else None,
              'peak_input_tokens': max((u.get('input_tokens', 0) for u in per_request), default=None),
              'model_calls': len(per_request), 'compactions': compactions,
              'tool_responses': len(outputs), 'tool_output_chars': sum(o['chars'] for o in outputs),
              'tool_output_tokens_o200k': sum(o['estimated_tokens_o200k'] for o in outputs),
              'tool_output_unique_tokens_o200k': sum(tokens(t) for t in set(o['text'] for o in outputs)),
              'tool_token_scope': 'Preserved original metric: native output strings, or stored JSON serialization of non-string outputs (including its escaping and wrappers). Storage-representation o200k estimate; not net model-visible body, not CLI-only stdout, not native billing.',
              'tool_output_decoded_text_chars':sum(o['decoded_text_chars'] for o in outputs),
              'tool_output_decoded_text_tokens_o200k':sum(o['decoded_text_tokens_o200k'] for o in outputs),
              'decoded_text_token_scope':'Supplemental: decoded native string/text parts, including tool wrappers. Tokens summed by text part. Does not replace preregistered storage-representation metric and is not net CLI body or billing.',
              'outer_output_truncations': sum('Warning: truncated output' in o['text'] for o in outputs),
              'cli_item_counts': dict(collections.Counter(i.get('type') for i in complete_items)),
              'command_failures': sum(i.get('type') == 'command_execution' and i.get('exit_code', 0) != 0 for i in complete_items),
              'initial_context_audit': {'messages': len(initial_audit),
                  'memory_summary_mentions': sum(a['memory_summary_mentions'] for a in initial_audit),
                  'private_text_exported': False},
              'format_adoption': summarize(format_rows),
              'cli_event_format_adoption':summarize_business(event_format_rows),
              'initial_input_integrity':{k:integrity[k] for k in ('state','protocol_deviation','evaluated_files','changed_paths')}}
    dump(target / 'metrics.json', metric)
    lines = [f"# {row['id']} {target.name} observable research log", '']
    for o in observable:
        if o['type'] == 'message':
            lines += ['## Assistant message', *o['content'], '']
        elif o['type'] in ('function_call', 'custom_tool_call'):
            args = o['arguments'] if isinstance(o['arguments'], str) else json.dumps(o['arguments'], ensure_ascii=False)
            lines += [f"## Tool call: {o['name']}", '```text', args, '```', '']
        elif o['type'] in ('function_call_output', 'custom_tool_call_output'):
            lines += ['## Tool result', '```text', o['output'], '```', '']
    (target / 'transcript.md').write_text('\n'.join(lines))
    return metric


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sessions-root', type=Path, default=Path.home()/'.codex/sessions')
    opts = parser.parse_args()
    manifest = read(BASE / 'manifest.json')
    metrics = [analyze_attempt(row, target, opts.sessions_root)
               for row in manifest['runs'] for target in attempt_paths(row)]
    dump(BASE / 'metrics-all-attempts.json', metrics)
    primary = [m for m in metrics if m['is_primary']]
    dump(BASE / 'metrics.json', primary)
    audit = {'assigned_primary_samples': len(manifest['runs']), 'primary_samples': len(primary),
             'state_counts': dict(collections.Counter(m['state'] for m in primary)),
             'all_attempts': len(metrics), 'extra_attempts': len(metrics)-len(primary),
             'native_model_effort_mismatches_or_unverified': [m['id'] for m in primary if not m['model_effort_verified']],
             'memory_contamination_mentions': [m['id'] for m in primary if m['initial_context_audit']['memory_summary_mentions']],
             'initial_input_protocol_deviations':[m['id'] for m in primary if m['initial_input_integrity']['protocol_deviation']],
             'private_text_exported': False, 'automatic_replacement_of_failures': False}
    dump(BASE / 'isolation-audit.json', audit)
    print(json.dumps(audit, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
