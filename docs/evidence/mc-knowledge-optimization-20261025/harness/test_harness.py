#!/usr/bin/env python3
"""Synthetic self-tests only. Never invokes codex, a model, or reference answers."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import common
import format_audit
import run_experiment
import analyze
import deterministic_compare
import package_sources
import tarfile


class HarnessTests(unittest.TestCase):
    def test_schedule_is_balanced_and_inputs_isolated(self):
        with tempfile.TemporaryDirectory(prefix='harness-selftest-', dir=common.BASE) as directory:
            base = Path(directory)
            for variant in ('final', 'baseline'):
                skill = base/variant/common.SKILL
                skill.parent.mkdir(parents=True)
                skill.write_text('synthetic '+variant+' skill\n')
            for name in ('mc-knowledge.py', 'mc_knowledge_wiki.py', 'mc_knowledge_output.py'):
                path = base/'final/scripts'/name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('# synthetic fixture\n')
            for rel in common.CONFIGS:
                path = base/'inputs'/rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('synthetic configuration\n')
            with patch.object(common, 'BASE', base), patch.object(run_experiment, 'BASE', base):
                common.dump(base/'frozen-source-manifest.json', {'sha256': common.source_inventory()})
                manifest = run_experiment.prepare()
                self.assertEqual(len(manifest['runs']), 27)
                self.assertEqual(len({(r['topic'], r['arm'], r['repeat']) for r in manifest['runs']}), 27)
                for wave in range(1, 10):
                    rows = [r for r in manifest['runs'] if r['wave'] == wave]
                    self.assertEqual({r['arm'] for r in rows}, set('JOS'))
                    self.assertEqual(len({(r['topic'], r['repeat']) for r in rows}), 1)
                    self.assertEqual(len({r['prompt_sha256'] for r in rows}), 1)
                    for row in rows:
                        workspace = Path(row['workspace'])
                        self.assertEqual(list((workspace/'.local/mc-knowledge').iterdir()), [])
                        self.assertFalse((workspace/'AGENTS.md').exists())
                        self.assertEqual((workspace/'scripts/mc_knowledge_output.py').read_text(), '# synthetic fixture\n')
                        self.assertEqual((workspace/common.SKILL).read_text(), 'synthetic '+common.ARMS[row['arm']]['skill']+' skill\n')
                with self.assertRaises(SystemExit):
                    run_experiment.prepare()

    def test_invocation_has_tool_shell_env_and_isolation(self):
        argv = run_experiment.command(Path('/synthetic/workspace'), Path('/synthetic/attempt'), 'compact')
        self.assertIn('shell_environment_policy.set.MC_KNOWLEDGE_FORMAT="compact"', argv)
        self.assertIn('project_doc_max_bytes=0', argv)
        self.assertIn('skip_host_skill_discovery', argv)
        for feature in ('memories', 'plugins', 'apps', 'multi_agent', 'chronicle', 'hooks'):
            index = argv.index(feature)
            self.assertEqual(argv[index-1], '--disable')

    def test_private_events_never_exported(self):
        for kind in ('reasoning', 'analysis'):
            self.assertIsNone(common.public_event({'type':'item.completed', 'item':{'type':kind,'text':'private'}}))
        self.assertIsNone(common.public_event({'item':{'role':'developer','text':'private'}}))
        self.assertNotIn('encrypted_content', common.public_event({'encrypted_content':'private', 'usage':{'input_tokens':1}}))

    def test_format_confirmation_and_override_audit(self):
        call = {'name':'exec_command', 'arguments':json.dumps({'cmd':'python3 scripts/mc-knowledge.py source X --format compact'}), 'call_id':'synthetic'}
        output = '{"format":"compact","version":"synthetic"}\n---\nbody'
        rows = format_audit.audit_call(call, output, 'json')
        self.assertEqual(rows[0]['visible_output_format'], 'compact')
        self.assertTrue(rows[0]['explicit_override_of_arm'])
        self.assertTrue(rows[0]['protocol_deviation'])
        self.assertEqual(format_audit.summarize(rows)['protocol_deviations'], 1)
        call['arguments'] = json.dumps({'cmd':'python3 scripts/mc-knowledge.py source X > capture.txt'})
        row = format_audit.audit_call(call, output, 'compact')[0]
        self.assertEqual(row['certainty'], 'unknown')
        self.assertIsNone(row['visible_output_format'])
        call['arguments'] = json.dumps({'cmd':'cat scripts/mc-knowledge.py'})
        self.assertEqual(format_audit.audit_call(call, output, 'compact'), [])

    def test_success_is_never_reexecuted(self):
        with tempfile.TemporaryDirectory(prefix='harness-selftest-', dir=common.BASE) as directory:
            base = Path(directory)
            target = base/'runs/run-01/attempt-01'
            target.mkdir(parents=True)
            common.dump(target/'status.json', {'state':'completed'})
            with patch.object(common, 'BASE', base), patch.object(run_experiment, 'BASE', base):
                with patch.object(run_experiment.subprocess, 'Popen', side_effect=AssertionError('Must not launch')):
                    self.assertEqual(run_experiment.execute({'id':'run-01'}, True, {})['state'], 'skipped_existing')

    def test_native_analysis_excludes_private_text_and_preserves_usage(self):
        with tempfile.TemporaryDirectory(prefix='harness-selftest-', dir=common.BASE) as directory:
            base = Path(directory)
            target = base/'runs/run-01/attempt-01'
            workspace = target/'workspace'
            workspace.mkdir(parents=True)
            row = {'id':'run-01','topic':'T1','arm':'O','repeat':1,'wave':1,'format_env':'compact'}
            common.dump(target/'status.json', {'state':'completed','thread_id':'synthetic-thread','elapsed_seconds':1})
            (target/'events.jsonl').write_text(json.dumps({'type':'turn.completed','usage':{'input_tokens':300}})+'\n')
            sessions = base/'sessions/2026/10/25'
            sessions.mkdir(parents=True)
            records = [
                {'type':'session_meta','payload':{'model_provider':'synthetic'}},
                {'type':'turn_context','payload':{'model':common.MODEL,'effort':common.EFFORT,'cwd':str(workspace)}},
                {'type':'response_item','payload':{'type':'message','role':'developer','content':[{'type':'input_text','text':'PRIVATE_SENTINEL'}]}},
                {'type':'response_item','payload':{'type':'reasoning','summary':[{'text':'PRIVATE_SENTINEL'}]}},
                {'type':'response_item','payload':{'type':'function_call','name':'exec_command','call_id':'synthetic-call','arguments':json.dumps({'cmd':'python3 scripts/mc-knowledge.py source X'})}},
                {'type':'response_item','payload':{'type':'function_call_output','call_id':'synthetic-call','output':'{"format":"compact","version":"synthetic"}\n---\nbody'}},
                {'type':'token_usage_record','payload':{'usage':{'input_tokens':100},'thread_token_usage':{'input_tokens':100}}},
                {'type':'token_usage_record','payload':{'usage':{'input_tokens':200},'thread_token_usage':{'input_tokens':300}}},
            ]
            (sessions/'rollout-synthetic-thread.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in records))
            with patch.object(analyze, 'BASE', base):
                metric = analyze.analyze_attempt(row, target, base/'sessions')
            self.assertEqual(metric['peak_input_tokens'], 200)
            self.assertEqual(metric['native_cumulative_usage']['input_tokens'], 300)
            self.assertEqual(metric['native_per_request_sum']['input_tokens'], 300)
            self.assertTrue(metric['model_effort_verified'])
            self.assertEqual(metric['format_adoption']['confirmed_visible_format'], 1)
            for name in ('observable.jsonl','initial-context-audit.json','transcript.md','tool-outputs.json'):
                self.assertNotIn('PRIVATE_SENTINEL', (target/name).read_text())

    def test_deterministic_compact_body_preserves_exact_extent(self):
        body = 'synthetic body\n'
        header = {'format':'compact','metrics':{'body_chars':len(body)}}
        plain = json.dumps(header)+'\n'+body+'\n'
        self.assertEqual(deterministic_compare.parse_response(plain,'compact')[1],body)
        dash_body = '---\n'+body
        dash_header = {'format':'compact','metrics':{'body_chars':len(dash_body)}}
        self.assertEqual(deterministic_compare.parse_response(json.dumps(dash_header)+'\n'+dash_body+'\n','compact')[1],dash_body)
        with self.assertRaises(ValueError):
            deterministic_compare.parse_response(plain+'\n','compact')
        with self.assertRaises(ValueError):
            deterministic_compare.parse_response('{"format":"json"}\nbody','compact')

    def test_deterministic_continuation_counts(self):
        with tempfile.TemporaryDirectory(prefix='harness-selftest-',dir=common.BASE) as directory:
            target = Path(directory)
            def synthetic_call(cli,workspace,cache,query,format_name,cursor,destination):
                first = not cursor
                body = 'abc' if first else 'def'
                following = {'start_line':2,'start_char':0} if first else None
                page = {'state':'completed','stdout_chars':10,'stdout_tokens_o200k':5,'next':following,
                        'body_sha256':common.sha_text(body),'truncated':first,'output_truncated':first}
                metadata = {'source':{'jar_sha256':'synthetic'},'next':following}
                return page,metadata,body
            with patch.object(deterministic_compare,'call',side_effect=synthetic_call):
                result = deterministic_compare.measure_format(Path('/synthetic'),target,target,{'argv':['synthetic']},'compact',target,4)
            self.assertEqual(result['cli_calls'],2)
            self.assertEqual(result['continuation_calls'],1)
            self.assertEqual(result['output_truncated_response_count'],1)
            self.assertTrue(result['complete_body_verified'])
            self.assertEqual(result['combined_body_sha256'],common.sha_text('abcdef'))
            self.assertEqual(result['first_page_full_jar_provenance_policy']['evidence_reads'],0)

    def test_cli_event_audit_is_independent_and_excludes_diagnostics(self):
        event = {'id':'synthetic','command':"/bin/zsh -lc 'python3 scripts/mc-knowledge.py source X'",
                 'aggregated_output':'{"format":"compact","metrics":{"body_chars":4}}\nbody\n','exit_code':0}
        rows = format_audit.audit_command_event(event,'compact')
        self.assertEqual(rows[0]['certainty'],'confirmed')
        self.assertEqual(rows[0]['visible_output_format'],'compact')
        self.assertTrue(rows[0]['shell_wrapper_unwrapped'])
        for query in ('doctor','--help'):
            event['command'] = '/bin/zsh -lc "python3 scripts/mc-knowledge.py '+query+'"'
            diagnostic = format_audit.audit_command_event(event,'compact')
            summary = format_audit.summarize_business(diagnostic)
            self.assertEqual(summary['diagnostic_invocations_excluded'],1)
            self.assertEqual(summary['identified_invocations'],0)
        event['command'] = "/bin/zsh -lc 'python3 scripts/mc-knowledge.py source X > output.txt'"
        self.assertEqual(format_audit.audit_command_event(event,'compact')[0]['certainty'],'unknown')
        event['command'] = "/bin/zsh -lc 'python3 scripts/mc-knowledge.py source X --format json'"
        self.assertTrue(format_audit.audit_command_event(event,'compact')[0]['explicit_override_of_arm'])

    def test_decoded_text_parts_do_not_count_json_escape_wrapper(self):
        value = [{'type':'text','text':'line one\n"line two"'},{'type':'text','text':'tail'}]
        self.assertEqual(analyze.decoded_text_parts(value),['line one\n"line two"','tail'])
        stored = json.dumps(value,ensure_ascii=False)
        self.assertGreater(len(stored),sum(map(len,analyze.decoded_text_parts(value))))

    def test_source_packaging_scope_privacy_and_portable_links(self):
        with tempfile.TemporaryDirectory(prefix='harness-selftest-',dir=common.BASE) as directory:
            base = Path(directory)
            workspace = base/'workspace'
            source = workspace/'.local/mc-knowledge/cache/Synthetic.java'
            source.parent.mkdir(parents=True)
            source.write_text('class Synthetic {}\n')
            (workspace/'notes.md').write_text('Synthetic generated note\n')
            (workspace/'binary.jar').write_bytes(b'not copied')
            (workspace/'.env').write_text('password="synthetic-secret-value"')
            outside = base/'outside.md'
            outside.write_text('password="synthetic-secret-value"')
            (workspace/'external.md').symlink_to(outside)
            selected,skipped,hits = package_sources.collect_sources(workspace,10000)
            self.assertEqual(hits,[])
            self.assertEqual(len(selected),2)
            self.assertEqual({item['category'] for item in selected},{'cli_cache','other_workspace_text'})
            self.assertEqual(len(skipped),3)
            answer = '[ref]('+str(source)+':7)\n[missing]('+str(workspace/'missing.java')+')\n'
            answer += '```md\n[code]('+str(source)+')\n```\n'
            portable,counts,unresolved = package_sources.portable_answer(answer,workspace,selected)
            self.assertIn('[ref](sources/.local/mc-knowledge/cache/Synthetic.java:7)',portable)
            self.assertIn('[code]('+str(source)+')',portable)
            self.assertEqual(counts['rewritten_links'],1)
            self.assertEqual(counts['unresolved_workspace_links'],1)
            self.assertEqual(len(unresolved),1)
            output = base/'output'
            output.mkdir()
            package_sources.write_archive(workspace,selected,output)
            with tarfile.open(output/'sources.tar.gz') as archive:
                self.assertEqual(set(archive.getnames()),{item['relative_path'] for item in selected})
            self.assertEqual(source.read_text(),'class Synthetic {}\n')

    def test_privacy_scan_contains_positions_not_values(self):
        secret = 'sk-'+'a'*32
        hits = package_sources.scan_text('first\n'+secret+'\n','synthetic.md')
        self.assertEqual(hits[0]['line'],2)
        self.assertEqual(hits[0]['type'],'openai_style_key')
        self.assertNotIn(secret,json.dumps(hits))
        assignment = package_sources.scan_text('{"password":"synthetic-secret-value"}','synthetic.json')
        self.assertEqual(assignment[0]['type'],'credential_assignment')
        self.assertNotIn('synthetic-secret-value',json.dumps(assignment))

    def test_portable_relative_manifest_links_and_unknown_targets(self):
        with tempfile.TemporaryDirectory(prefix='harness-selftest-',dir=common.BASE) as directory:
            workspace = Path(directory)/'workspace'
            source = workspace/'.local/evidence/note.md'
            source.parent.mkdir(parents=True)
            source.write_text('synthetic source\n')
            (workspace/'unlisted.md').write_text('existing but outside manifest\n')
            manifest = [{'relative_path':'.local/evidence/note.md'}]
            text = '[plain](.local/evidence/note.md:7)\n[dotted](./.local/evidence/note.md#L9)\n'
            unchanged = [
                '[unknown](.local/evidence/missing.md)',
                '[unlisted](unlisted.md)',
                '[parent](../workspace/.local/evidence/note.md)',
                '[dotted-parent](./../workspace/.local/evidence/note.md)',
                '[web](https://example.com/.local/evidence/note.md)',
                '[anchor](#section)',
                '[file-anchor](.local/evidence/note.md#section)',
                '[query](.local/evidence/note.md?download=1)',
                '[relative-file-uri](file://.local/evidence/note.md)',
            ]
            text += '\n'.join(unchanged)+'\n'
            portable,counts,unresolved = package_sources.portable_answer(text,workspace,manifest)
            self.assertIn('[plain](sources/.local/evidence/note.md:7)',portable)
            self.assertIn('[dotted](sources/.local/evidence/note.md#L9)',portable)
            for target in unchanged:
                self.assertIn(target,portable)
            self.assertEqual(counts['rewritten_links'],2)
            self.assertEqual(counts['nonworkspace_links'],len(unchanged))
            self.assertEqual(unresolved,[])
            source.unlink()
            portable,counts,unresolved = package_sources.portable_answer('[missing](./.local/evidence/note.md:7)',workspace,manifest)
            self.assertEqual(portable,'[missing](./.local/evidence/note.md:7)')
            self.assertEqual(counts['unresolved_workspace_links'],1)
            self.assertEqual(unresolved[0]['type'],'missing_or_outside_workspace')

    def test_end_input_integrity_pending_and_changed(self):
        with tempfile.TemporaryDirectory(prefix='harness-selftest-',dir=common.BASE) as directory:
            target = Path(directory)
            source = target/'workspace/scripts/mc-knowledge.py'
            source.parent.mkdir(parents=True)
            source.write_text('synthetic initial\n')
            common.dump(target/'initial-files.json',{'scripts/mc-knowledge.py':common.sha(source)})
            self.assertEqual(analyze.initial_integrity(target,{'state':'running'})['state'],'pending')
            self.assertIsNone(analyze.initial_integrity(target,{'state':'running'})['protocol_deviation'])
            self.assertEqual(analyze.initial_integrity(target,{'state':'completed'})['state'],'verified')
            source.write_text('synthetic modified\n')
            report = analyze.initial_integrity(target,{'state':'completed'})
            self.assertEqual(report['state'],'changed')
            self.assertTrue(report['protocol_deviation'])
            self.assertEqual(source.read_text(),'synthetic modified\n')

    def test_exact_compact_budget_fallback_is_not_participant_override(self):
        diagnostic = json.dumps({'ok':False,'error':{'code':'OUTPUT_BUDGET_TOO_SMALL','budget':6000}})+'\n'
        event = {'id':'synthetic','command':"/bin/zsh -lc 'python3 scripts/mc-knowledge.py source X'",
                 'aggregated_output':diagnostic,'exit_code':1}
        row = format_audit.audit_command_event(event,'compact')[0]
        self.assertTrue(row['documented_budget_error_fallback'])
        self.assertEqual(row['visible_output_format'],'json')
        self.assertFalse(row['protocol_deviation'])
        summary = format_audit.summarize_business([row])
        self.assertEqual(summary['documented_budget_error_fallbacks'],1)
        self.assertEqual(summary['identified_invocations'],1)
        self.assertEqual(summary['adoption_eligible_confirmed_visible_format'],0)
        self.assertIsNone(summary['confirmed_default_adoption_rate'])
        event['command'] = "/bin/zsh -lc 'python3 scripts/mc-knowledge.py source X --format compact'"
        self.assertTrue(format_audit.audit_command_event(event,'compact')[0]['documented_budget_error_fallback'])
        event['command'] = "/bin/zsh -lc 'python3 scripts/mc-knowledge.py source X --format json'"
        override = format_audit.audit_command_event(event,'compact')[0]
        self.assertNotIn('documented_budget_error_fallback',override)
        self.assertTrue(override['protocol_deviation'])
        event['command'] = "/bin/zsh -lc 'MC_KNOWLEDGE_FORMAT=json python3 scripts/mc-knowledge.py source X --format compact'"
        self.assertNotIn('documented_budget_error_fallback',format_audit.audit_command_event(event,'compact')[0])

    def test_budget_fallback_requires_complete_exact_top_level_diagnostic(self):
        event = {'id':'synthetic','command':"/bin/zsh -lc 'python3 scripts/mc-knowledge.py source X'",'exit_code':1}
        for value in (
            {'ok':False,'error':{'code':'OTHER_ERROR','message':'OUTPUT_BUDGET_TOO_SMALL'}},
            {'ok':True,'error':{'code':'OUTPUT_BUDGET_TOO_SMALL'}},
            {'result':{'ok':False,'error':{'code':'OUTPUT_BUDGET_TOO_SMALL'}}},
        ):
            event['aggregated_output'] = json.dumps(value)+'\n'
            self.assertFalse(format_audit.audit_command_event(event,'compact')[0].get('documented_budget_error_fallback',False))
        event['aggregated_output'] = '{"ok":false,"error":{"code":"OUTPUT_BUDGET_TOO_SMALL"}}\nextra output\n'
        self.assertFalse(format_audit.audit_command_event(event,'compact')[0].get('documented_budget_error_fallback',False))


if __name__ == '__main__':
    unittest.main()
