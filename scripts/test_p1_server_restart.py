#!/usr/bin/env python3
"""Bounded prefix compatibility checks; no server, filesystem fixture or client required."""

from pathlib import Path
import importlib.util
import struct
import unittest
import uuid

SPEC = importlib.util.spec_from_file_location('p1_server_restart', Path(__file__).with_name('p1-server-restart.py'))
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)
ROUND = uuid.UUID('f3e990c5-f902-4a20-bc6a-0be4a91d4c57')


def encoded_text(value):
    value = value.encode('utf-8')
    return struct.pack('>i', len(value)) + value


def prefix(schema=4, clean=1, uncertain=0):
    return (struct.pack('>Ii', 0x48525331, schema) + ROUND.bytes
            + struct.pack('>qqq', 3, -12345, 14) + encoded_text('minecraftVersion=26.3\nmode=NORMAL\n')
            + encoded_text('RUNNING') + encoded_text('SERVER_STOPPED')
            + struct.pack('>qqBB', 13_000_000_000, 0, clean, uncertain))


class SnapshotHeaderTest(unittest.TestCase):
    def test_all_known_schemas_share_the_identity_and_timing_prefix(self):
        for schema in range(1, 5):
            with self.subTest(schema=schema):
                result = RUNNER.read_snapshot_header(prefix(schema) + b'participant-tail-is-checked-by-JVM')
                self.assertEqual(result, {
                    'schema': schema, 'roundId': str(ROUND), 'sequence': 3, 'seed': -12345, 'version': 14,
                    'rules': 'minecraftVersion=26.3\nmode=NORMAL\n', 'phase': 'RUNNING',
                    'pauseReason': 'SERVER_STOPPED', 'challengeNanos': 13_000_000_000,
                    'remainingPhaseNanos': 0, 'cleanShutdown': True, 'timingUncertain': False,
                })

    def test_unknown_schema_and_magic_fail_closed(self):
        for schema in (-1, 0, 5, 2**31 - 1):
            with self.subTest(schema=schema), self.assertRaisesRegex(RuntimeError, 'Unsupported round snapshot schema'):
                RUNNER.read_snapshot_header(prefix(schema))
        with self.assertRaisesRegex(RuntimeError, 'Unsupported round snapshot format'):
            RUNNER.read_snapshot_header(b'BAD!' + prefix()[4:])

    def test_truncated_prefix_never_reads_partial_fields(self):
        value = prefix()
        for length in range(len(value)):
            with self.subTest(length=length), self.assertRaises(RuntimeError):
                RUNNER.read_snapshot_header(value[:length])

    def test_text_bounds_and_utf8_remain_strict(self):
        start = prefix()[:48]  # Magic/schema, UUID, sequence/seed/version; then rules byte length.
        for length in (-1, 131073, 2**31 - 1):
            with self.subTest(length=length), self.assertRaisesRegex(RuntimeError, 'Invalid snapshot text length'):
                RUNNER.read_snapshot_header(start + struct.pack('>i', length))
        with self.assertRaises(UnicodeDecodeError):
            RUNNER.read_snapshot_header(start + struct.pack('>i', 1) + b'\xff')
        before_phase = start + encoded_text('rules')
        for length in (-1, 65):
            with self.subTest(phase_length=length), self.assertRaisesRegex(RuntimeError, 'Invalid snapshot text length'):
                RUNNER.read_snapshot_header(before_phase + struct.pack('>i', length))

    def test_total_size_matches_java_codec_limit(self):
        for size in (4 * 1024 * 1024 + 1, 5 * 1024 * 1024):
            with self.subTest(size=size), self.assertRaisesRegex(RuntimeError, 'exceeds size limit'):
                RUNNER.read_snapshot_header(prefix() + bytes(size - len(prefix())))

    def test_clean_and_uncertain_flags_are_preserved_and_validated(self):
        for clean in (0, 1):
            for uncertain in (0, 1):
                with self.subTest(clean=clean, uncertain=uncertain):
                    result = RUNNER.read_snapshot_header(prefix(clean=clean, uncertain=uncertain))
                    self.assertEqual(result['cleanShutdown'], bool(clean))
                    self.assertEqual(result['timingUncertain'], bool(uncertain))
        for clean, uncertain in ((2, 0), (0, 2), (255, 255)):
            with self.subTest(clean=clean, uncertain=uncertain), self.assertRaisesRegex(RuntimeError, 'Invalid snapshot stop markers'):
                RUNNER.read_snapshot_header(prefix(clean=clean, uncertain=uncertain))


if __name__ == '__main__':
    unittest.main()
