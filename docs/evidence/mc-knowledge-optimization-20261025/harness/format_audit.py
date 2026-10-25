"""Conservative visible-evidence audit. No wrappers, telemetry or forced CLI flags."""
import ast
import json
import re
import shlex


def shell_commands(name, arguments):
    if isinstance(arguments, dict):
        return [arguments['cmd']] if isinstance(arguments.get('cmd'), str) else []
    try:
        parsed = json.loads(arguments)
    except (ValueError, TypeError):
        parsed = None
    if isinstance(parsed, dict) and isinstance(parsed.get('cmd'), str):
        return [parsed['cmd']]
    # functions.exec contains JavaScript, rather than a JSON exec_command argument.
    result = []
    for match in re.finditer(r'''(?:"cmd"|\bcmd)\s*:\s*("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')''', str(arguments)):
        try:
            result.append(ast.literal_eval(match.group(1)))
        except (ValueError, SyntaxError):
            pass
    return result


def marker(output):
    # Handles plain stdout and JSON-escaped stdout nested in a tool wrapper.
    plain = output.replace('\\"', '"')
    if re.search(r'"format"\s*:\s*"compact"', plain):
        return 'compact'
    # JSON's legacy output has no mandatory format marker. Infer only from a full,
    # valid standalone JSON response with a source/result payload.
    queue, seen = [output], set()
    while queue:
        text = queue.pop()
        if text in seen:
            continue
        seen.add(text)
        if '\nOutput:\n' in text:
            text = text.split('\nOutput:\n', 1)[1]
        try:
            value = json.loads(text.strip())
        except ValueError:
            # functions.exec may print one structured tool result per line.
            lines = [line for line in text.splitlines() if line.strip()]
            if len(lines) > 1:
                for line in lines:
                    try:
                        json.loads(line)
                    except ValueError:
                        continue
                    queue.append(line)
            continue
        if isinstance(value, dict) and any(k in value for k in ('version', 'source', 'error', 'command')):
            return 'json'
        def unwrap(part):
            if isinstance(part, str):
                queue.append(part)
            elif isinstance(part, list):
                for child in part:
                    unwrap(child)
            elif isinstance(part, dict):
                for key in ('output', 'value', 'result', 'content', 'text'):
                    if key in part:
                        unwrap(part[key])
        unwrap(value)
    return None


def audit_call(call, output, arm_format):
    """Return one row per identifiable invocation, retaining uncertain calls."""
    arguments = call.get('arguments', '')
    commands = shell_commands(call.get('name', ''), arguments)
    rows = []
    for command in commands:
        if 'mc-knowledge.py' not in command:
            continue
        try:
            lex = shlex.shlex(command, posix=True, punctuation_chars=True)
            lex.whitespace_split = True
            tokens = list(lex)
        except ValueError:
            rows.append({'certainty': 'unknown', 'reason': 'unparseable_shell', 'command': command})
            continue
        indices = [i for i, token in enumerate(tokens) if token.endswith('mc-knowledge.py')
                   and i > 0 and re.search(r'(?:^|/)python(?:3(?:\.\d+)?)?$', tokens[i-1])]
        if not indices:
            # Reading a file or naming a path is not counted as an invocation.
            if re.search(r'python\S*.*mc-knowledge\.py', command, re.S):
                rows.append({'certainty': 'unknown', 'reason': 'indirect_or_dynamic_invocation', 'command': command})
            continue
        for index in indices:
            end = next((i for i in range(index+1, len(tokens)) if tokens[i] in (';', '&&', '||', '|', '>', '>>', '<', '2>')), len(tokens))
            argv = tokens[index+1:end]
            explicit = None
            for i, token in enumerate(argv):
                if token.startswith('--format='):
                    explicit = token.split('=', 1)[1]
                elif token == '--format' and i+1 < len(argv):
                    explicit = argv[i+1]
            inline = re.findall(r'\bMC_KNOWLEDGE_FORMAT=(json|compact)\b', command[:command.find(tokens[index])])
            local_env = inline[-1] if inline else arm_format
            expected = explicit or local_env
            query_kind = ('help' if any(token in ('--help','-h') for token in argv) else
                          next((token for token in argv if token in ('doctor','find-class','outline','source','data','search','wiki')), 'unknown'))
            ambiguous = (len(indices) != 1 or len(commands) != 1
                         or len(re.findall(r'\btools\.\w+\s*\(', str(arguments))) > 1
                         or any(t in ('|', '>', '>>', '<', '2>', '&&', '||', ';') for t in tokens)
                         or '\n' in command or 'export ' in command or '$(' in command)
            observed = marker(output) if not ambiguous else None
            row = {'call_id': call.get('call_id'), 'command': command,
                   'environment_default': arm_format, 'command_environment_override': inline[-1] if inline else None,
                   'explicit_format': explicit, 'expected_format': expected,
                   'query_kind': query_kind,
                   'visible_output_format': observed,
                   'certainty': 'confirmed' if observed else 'unknown',
                   'reason': 'visible_marker_or_standalone_json' if observed else ('complex_shell_or_redirect' if ambiguous else 'no_unambiguous_visible_format'),
                   'follows_arm_default': observed == arm_format if observed else None,
                   'explicit_override_of_arm': explicit is not None and explicit != arm_format,
                   'protocol_deviation': expected != arm_format or (observed is not None and observed != arm_format)}
            rows.append(row)
    if not commands and 'mc-knowledge.py' in str(arguments):
        rows.append({'call_id': call.get('call_id'), 'certainty': 'unknown',
                     'reason': 'unparsed_tool_arguments', 'arguments_sha_only': True})
    return rows


def unwrap_shell(command):
    """Statically decode only an ordinary, three-argument shell -c wrapper."""
    try:
        words = shlex.split(command, posix=True)
    except ValueError:
        return None
    if words and words[0].rsplit('/',1)[-1] in ('sh','bash','zsh','dash','ksh'):
        if len(words) == 3 and words[1] in ('-c','-lc','-cl'):
            return words[2]
        return None
    return command


def audit_command_event(item, arm_format):
    command = item.get('command','')
    if 'mc-knowledge.py' not in command:
        return []
    inner = unwrap_shell(command)
    if inner is None:
        return [{'event_id':item.get('id'),'certainty':'unknown','reason':'shell_wrapper_not_statically_decodable',
                 'original_command':command,'exit_code':item.get('exit_code')}]
    rows = audit_call({'name':'exec_command','arguments':{'cmd':inner},'call_id':item.get('id')},
                      item.get('aggregated_output',''),arm_format)
    # This renderer intentionally uses a complete standalone JSON diagnostic
    # when the requested compact envelope cannot fit. Preserve wire format;
    # do not attribute that implementation fallback to the participant.
    try:
        diagnostic = json.loads(item.get('aggregated_output',''))
    except (ValueError,TypeError):
        diagnostic = None
    exact_budget_diagnostic = (isinstance(diagnostic,dict) and diagnostic.get('ok') is False
                              and isinstance(diagnostic.get('error'),dict)
                              and diagnostic['error'].get('code') == 'OUTPUT_BUDGET_TOO_SMALL')
    for row in rows:
        row.update(event_id=item.get('id'), original_command=command,
                   shell_wrapper_unwrapped=inner != command, exit_code=item.get('exit_code'),
                   output_source='CLI item.completed.command_execution.aggregated_output')
        no_other_format = (row.get('expected_format') == 'compact'
                           and row.get('environment_default') == 'compact'
                           and row.get('explicit_format') in (None,'compact')
                           and row.get('command_environment_override') in (None,'compact'))
        if (exact_budget_diagnostic and len(rows) == 1 and no_other_format
                and row.get('certainty') == 'confirmed' and row.get('visible_output_format') == 'json'):
            row.update(documented_budget_error_fallback=True,
                       diagnostic_error_code='OUTPUT_BUDGET_TOO_SMALL',
                       protocol_deviation=False,
                       reason='complete documented JSON budget-error fallback; no participant format override')
    return rows


def summarize_business(rows):
    business = [r for r in rows if r.get('query_kind') not in ('help','doctor')]
    summary = summarize(business)
    eligible = [r for r in business if not r.get('documented_budget_error_fallback')]
    eligible_summary = summarize(eligible)
    summary.update(diagnostic_invocations_excluded=sum(r.get('query_kind') in ('help','doctor') for r in rows),
                   all_event_invocations=len(rows),
                   documented_budget_error_fallbacks=sum(bool(r.get('documented_budget_error_fallback')) for r in business),
                   adoption_eligible_invocations=eligible_summary['identified_invocations'],
                   adoption_eligible_confirmed_visible_format=eligible_summary['confirmed_visible_format'],
                   confirmed_default_adoption_rate=eligible_summary['confirmed_default_adoption_rate'],
                   adoption_rate_denominator='Confirmed eligible business formats only; unknowns and documented budget-error fallbacks excluded. All wire-format counts remain reported separately.',
                   denominator_policy='Business invocations only. doctor/help excluded. Documented budget-error fallback JSON is counted separately and excluded from adoption-rate denominator. Complex commands and undecodable shells remain unknown.')
    return summary


def summarize(rows):
    invocations = [r for r in rows if 'expected_format' in r]
    confirmed = [r for r in invocations if r['certainty'] == 'confirmed']
    return {'identified_invocations': len(invocations),
            'unknown_candidates': sum('expected_format' not in r for r in rows),
            'confirmed_visible_format': len(confirmed),
            'unknown_visible_format': len(invocations)-len(confirmed),
            'explicit_format_count': sum(r['explicit_format'] is not None for r in invocations),
            'explicit_overrides_of_arm': sum(r['explicit_override_of_arm'] for r in invocations),
            'command_environment_overrides': sum(r['command_environment_override'] is not None for r in invocations),
            'protocol_deviations': sum(r['protocol_deviation'] for r in invocations),
            'confirmed_follows_arm_default': sum(r['follows_arm_default'] for r in confirmed),
            'confirmed_default_adoption_rate': (sum(r['follows_arm_default'] for r in confirmed)/len(confirmed)) if confirmed else None,
            'adoption_rate_denominator': 'Only invocations with unambiguous visible output; unknowns reported separately.'}
