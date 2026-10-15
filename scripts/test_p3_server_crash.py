"""Signal ownership and stale-marker safety; no Java or real process is launched."""
from pathlib import Path
import importlib.util
import json
import hashlib
import struct
import signal
import tempfile
import unittest
import uuid
from unittest.mock import MagicMock, patch

SPEC = importlib.util.spec_from_file_location('p3_crash', Path(__file__).with_name('p3-server-crash.py'))
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class CrashSignalSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.spec = {'java': '/unused/java', 'jvmArgs': [], 'classpath': ['/unused/classes'],
                     'mainClass': 'unused.Main', 'programArgs': ['nogui']}
        self.process = MagicMock(pid=12345, returncode=-signal.SIGKILL)
        self.process.poll.return_value = None
        self.marker = {'status': 'KILL_READY', 'pid': 12345, 'scenario': 'AWARD',
                       'boundary': 'INDEX', 'operation': 'write'}

    def spawn(self, *args, **kwargs):
        (self.directory / 'kill-ready.json').write_text(json.dumps(self.emitted_marker))
        return self.process

    def launch(self):
        return RUNNER.launch(self.spec, self.directory, 'AWARD', 'INDEX', 'write', 0, 1)

    def test_wrong_pid_or_scenario_cannot_authorize_the_test_sigkill(self):
        for key, value in (('pid', 98765), ('scenario', 'TERMINAL'), ('status', 'PASS')):
            with self.subTest(key=key):
                marker = dict(self.marker, **{key: value})
                (self.directory / 'kill-ready.json').unlink(missing_ok=True)
                self.emitted_marker = marker
                with patch.object(RUNNER.subprocess, 'Popen', side_effect=self.spawn), \
                        patch.object(RUNNER.P1, 'cleanup_failed_process', return_value={'required': False}), \
                        patch.object(RUNNER.os, 'kill') as kill:
                    with self.assertRaisesRegex(RuntimeError, 'stale kill boundary'):
                        self.launch()
                    kill.assert_not_called()
                self.assertEqual('FAIL', json.loads((self.directory / 'write-evidence.json').read_text())['status'])

    def test_only_the_new_child_pid_receives_sigkill_after_a_matching_marker(self):
        self.emitted_marker = self.marker
        with patch.object(RUNNER.subprocess, 'Popen', side_effect=self.spawn), \
                patch.object(RUNNER, 'database_evidence', return_value={'checked': True}), \
                patch.object(RUNNER.os, 'kill') as kill:
            result = self.launch()
            kill.assert_called_once_with(self.process.pid, signal.SIGKILL)
            self.assertEqual('PASS', result['status'])

    def test_writer_that_exited_by_itself_is_never_counted_as_crash_evidence(self):
        self.emitted_marker = self.marker
        self.process.poll.return_value = 0
        with patch.object(RUNNER.subprocess, 'Popen', side_effect=self.spawn), \
                patch.object(RUNNER.P1, 'cleanup_failed_process', return_value={'required': False}), \
                patch.object(RUNNER.os, 'kill') as kill:
            with self.assertRaisesRegex(RuntimeError, 'before parent-issued SIGKILL'):
                self.launch()
            kill.assert_not_called()

    def test_stale_marker_prevents_launch_before_any_child_can_be_killed(self):
        (self.directory / 'kill-ready.json').write_text(json.dumps(self.marker))
        with patch.object(RUNNER.subprocess, 'Popen') as spawn, patch.object(RUNNER.os, 'kill') as kill:
            with self.assertRaisesRegex(RuntimeError, 'stale fixture marker'):
                self.launch()
            spawn.assert_not_called(); kill.assert_not_called()

    def test_retained_inventory_checks_profile_content_before_counting_it_as_evidence(self):
        root = self.directory.resolve() / 'world'
        region = root / 'dimensions/hardrush/fixture/overworld/region'
        region.mkdir(parents=True)
        (region / 'r.0.0.mca').write_bytes(b'native-region-sentinel')
        journal = root / 'hardrush/profiles/player/transition.bin'
        journal.parent.mkdir(parents=True)
        payload = b'profile-transition'
        journal.write_bytes(struct.pack('>i', len(payload)) + hashlib.sha256(payload).digest() + payload)
        self.assertEqual(2, len(RUNNER.retained(self.directory.resolve())))
        journal.write_bytes(journal.read_bytes()[:-1] + b'X')
        with self.assertRaisesRegex(RuntimeError, 'Corrupt profile journal'):
            RUNNER.retained(self.directory.resolve())

    def test_retained_inventory_rejects_links_instead_of_hashing_external_evidence(self):
        directory = self.directory / 'world/dimensions/hardrush'
        directory.mkdir(parents=True)
        (directory / 'foreign').symlink_to(self.directory)
        with self.assertRaisesRegex(RuntimeError, 'linked fixture file'):
            RUNNER.retained(self.directory)

    def test_profile_debugger_never_attaches_to_a_mismatched_child(self):
        baseline = dict(self.marker, scenario='PROFILE', pid=999, boundary='PREPARED')
        (self.directory / 'profile-baseline.json').write_text(json.dumps(baseline))
        (self.directory / 'write.log').write_text('Listening for transport dt_socket at address: 55444\n')
        with patch.object(RUNNER.subprocess, 'Popen') as spawn:
            with self.assertRaisesRegex(RuntimeError, 'baseline identity'):
                RUNNER.profile_barrier(self.spec, self.directory, self.process, 'PREPARED', 1)
            spawn.assert_not_called()

    def test_profile_debugger_refuses_missing_or_ambiguous_loopback_endpoint(self):
        baseline = dict(self.marker, scenario='PROFILE', boundary='PREPARED')
        (self.directory / 'profile-baseline.json').write_text(json.dumps(baseline))
        for content in ('', 'Listening for transport dt_socket at address: 0\n',
                        'Listening for transport dt_socket at address: 1111\nListening for transport dt_socket at address: 2222\n'):
            with self.subTest(content=content):
                (self.directory / 'write.log').write_text(content)
                with patch.object(RUNNER.subprocess, 'Popen') as spawn:
                    with self.assertRaisesRegex(RuntimeError, 'unique owned JDWP'):
                        RUNNER.profile_barrier(self.spec, self.directory, self.process, 'PREPARED', 1)
                    spawn.assert_not_called()

    def test_profile_hit_requires_frozen_vm_and_the_exact_journal_worker(self):
        baseline = dict(self.marker, scenario='PROFILE', boundary='PREPARED')
        hit = dict(baseline, suspendPolicy='ALL', thread='hardrush-p1-profiles', method='prepare')
        (self.directory / 'profile-baseline.json').write_text(json.dumps(baseline))
        for key, value in (('pid', 999), ('boundary', 'VERIFIED'), ('suspendPolicy', 'EVENT_THREAD'), ('thread', 'Server thread')):
            with self.subTest(key=key):
                (self.directory / 'profile-hit.json').write_text(json.dumps(dict(hit, **{key: value})))
                with self.assertRaisesRegex(RuntimeError, 'stale profile debugger hit'):
                    RUNNER.profile_marker(self.directory, self.process.pid, 'PREPARED')
        (self.directory / 'profile-hit.json').write_text(json.dumps(hit))
        self.assertEqual('KILL_READY', RUNNER.profile_marker(self.directory, self.process.pid, 'PREPARED')['status'])

    def profile_files(self, boundary, phase, target):
        player, round_id = str(uuid.uuid4()), str(uuid.uuid4())
        root = self.directory / 'world/hardrush/profiles' / player
        (root / 'archives').mkdir(parents=True)

        def envelope(path, payload):
            path.write_bytes(struct.pack('>i', len(payload)) + hashlib.sha256(payload).digest() + payload)

        envelope(root / 'transition.bin', struct.pack('>i16sqB', 1, uuid.UUID(round_id).bytes, 2, phase))
        envelope(root / 'archives/pre-hardrush.bin', b'sealed native original')
        if target:
            envelope(root / f'suspended-{round_id}.bin', b'one reset identity')
        native = self.directory / 'world/players/data' / f'{player}.dat'
        native.parent.mkdir(parents=True, exist_ok=True); native.write_bytes(b'native checkpoint')
        return {'playerId': player, 'boundary': boundary}, {'roundId': round_id, 'sequence': 2}

    def test_profile_evidence_distinguishes_all_four_real_write_windows(self):
        for boundary, phase, target in (('PREPARED', 0, False), ('SUSPENDED', 0, True), ('APPLIED', 1, True), ('VERIFIED', 2, True)):
            with self.subTest(boundary=boundary):
                marker, header = self.profile_files(boundary, phase, target)
                result = RUNNER.profile_evidence(self.directory, marker, header)
                self.assertEqual(phase, result['phase'])
                self.assertEqual(target, result['targetSha256'] is not None)

    def test_profile_evidence_rejects_a_later_write_or_unsealed_reset(self):
        marker, header = self.profile_files('PREPARED', 1, True)
        with self.assertRaisesRegex(RuntimeError, 'requested write boundary'):
            RUNNER.profile_evidence(self.directory, marker, header)
        marker, header = self.profile_files('PREPARED', 0, True)
        with self.assertRaisesRegex(RuntimeError, 'unexpectedly contains reset'):
            RUNNER.profile_evidence(self.directory, marker, header)
        marker, header = self.profile_files('APPLIED', 1, False)
        with self.assertRaisesRegex(RuntimeError, 'Invalid profile envelope'):
            RUNNER.profile_evidence(self.directory, marker, header)


if __name__ == '__main__':
    unittest.main()
