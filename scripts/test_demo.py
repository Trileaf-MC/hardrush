"""Guards for the demo's production artifacts and isolated launch boundary."""

from pathlib import Path
import importlib.util
import io
import json
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('hardrush_demo', Path(__file__).with_name('demo.py'))
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)


def mod_jar(identifier, extra=None):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as archive:
        archive.writestr('fabric.mod.json', json.dumps({'id': identifier}))
        for name, data in (extra or {}).items():
            archive.writestr(name, data)
    return stream.getvalue()


class DemoTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.base = self.root / '.local/demo'
        self.patches = [patch.object(demo, 'ROOT', self.root), patch.object(demo, 'BASE', self.base),
                        patch.object(demo, 'java', return_value='/java25/bin/java')]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)
        self.bundle = self.base / 'bundles' / 'c7d185e0-678c-40f5-a356-321b81e1291f'
        (self.bundle / 'mods').mkdir(parents=True)
        (self.bundle / 'fabric-server.jar').write_bytes(b'launcher')
        (self.bundle / 'mods/hardrush.jar').write_bytes(mod_jar('hardrush'))
        demo.write_json(self.bundle / 'manifest.json', dict(schema=1, files={
            str(path.relative_to(self.bundle)): demo.digest(path)
            for path in self.bundle.rglob('*') if path.is_file()}))

    def prepare(self):
        return demo.prepare(self.bundle, 'hardcore', 25587)

    def accept_existing_eula(self):
        source = self.root / '.local/server/eula.txt'
        source.parent.mkdir(parents=True)
        source.write_text('eula=true\n')

    def test_nested_development_mod_is_rejected(self):
        bad = mod_jar('hardrush', {'META-INF/jars/innocent.jar':
                      mod_jar('extra', {'dev/hardrush/gametest/Fixture.class': b'test'})})
        with self.assertRaisesRegex(RuntimeError, '开发工具'):
            demo.audit_jar(bad, 'hardrush.jar')

    def test_operator_probe_code_is_rejected_even_without_a_development_mod_id(self):
        for path in ('dev/hardrush/development/HardRushDevelopment.class', 'dev/hardrush/development/world/DevelopmentWorlds.class',
                     'dev/hardrush/development/world/DevelopmentWorldManifest.class', 'dev/hardrush/development/world/DevelopmentRuntimeLevel.class'):
            with self.subTest(path=path), self.assertRaisesRegex(RuntimeError, '开发工具'):
                demo.audit_jar(mod_jar('hardrush', {path: b'probe'}), 'hardrush.jar')

    def test_nested_class_cannot_retain_mock_login_or_temporary_binding_entrypoints(self):
        for marker in demo.FORBIDDEN_CLASS_MARKERS:
            with self.subTest(marker=marker), self.assertRaisesRegex(RuntimeError, '测试辅助入口'):
                demo.audit_jar(mod_jar('hardrush', {'innocent/Runtime.class': marker}), 'hardrush.jar')

    def test_official_api_gametest_module_is_allowed(self):
        # Fabric API includes its own gametest API, unlike our test-only fixture mod.
        data = mod_jar('fabric-api', {'META-INF/jars/fabric-gametest-api-v1.jar':
                        mod_jar('fabric-gametest-api-v1')})
        self.assertEqual(demo.audit_jar(data, 'fabric-api.jar')['id'], 'fabric-api')

    def test_nested_development_mod_cannot_hide_behind_filename(self):
        data = mod_jar('hardrush', {'META-INF/jars/extra.jar': mod_jar('mcpfabric')})
        with self.assertRaisesRegex(RuntimeError, '开发工具元数据'):
            demo.audit_jar(data, 'hardrush.jar')

    def test_preparing_twice_never_reuses_world(self):
        first = self.prepare()
        (first / 'world').mkdir()
        saved = first / 'world/keep.dat'
        saved.write_bytes(b'keep')
        second = self.prepare()
        self.assertNotEqual(first, second)
        self.assertEqual(saved.read_bytes(), b'keep')
        self.assertFalse((second / 'world').exists())

    def test_absent_eula_is_not_accepted(self):
        run = self.prepare()
        self.assertIn('eula=false', (run / 'eula.txt').read_text())
        with self.assertRaisesRegex(RuntimeError, 'EULA'):
            demo.launch_command(run)

    def test_existing_acceptance_and_production_launch(self):
        self.accept_existing_eula()
        run = self.prepare()
        command = demo.launch_command(run)
        self.assertIn('-Dhardrush.competition.enabled=true', command)
        self.assertIn('-Dhardrush.round.initialMode=hardcore', command)
        self.assertEqual(command[-3:], ['-jar', 'fabric-server.jar', 'nogui'])
        self.assertEqual(demo.instance(run.name), run)

    def test_changed_runtime_jar_is_rejected(self):
        self.accept_existing_eula()
        run = self.prepare()
        (run / 'mods/hardrush.jar').write_bytes(b'changed')
        with self.assertRaisesRegex(RuntimeError, '不一致'):
            demo.launch_command(run)

    def test_extra_mod_is_rejected(self):
        self.accept_existing_eula()
        run = self.prepare()
        (run / 'mods/mcpfabric.jar').write_bytes(b'extra')
        with self.assertRaisesRegex(RuntimeError, '额外模组'):
            demo.launch_command(run)

    def test_bundle_checksum_is_enforced(self):
        (self.bundle / 'fabric-server.jar').write_bytes(b'changed')
        with self.assertRaisesRegex(RuntimeError, '变更'):
            self.prepare()

    def test_escape_and_symlink_instances_are_rejected(self):
        with self.assertRaises(ValueError):
            demo.instance('../../server')
        run = self.prepare()
        identifier = run.name
        shutil.rmtree(run)
        run.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, '独立目录'):
            demo.instance(identifier)

    def test_changed_network_boundary_is_rejected(self):
        self.accept_existing_eula()
        run = self.prepare()
        path = run / 'server.properties'
        path.write_text(path.read_text().replace('server-ip=127.0.0.1', 'server-ip=0.0.0.0'))
        with self.assertRaisesRegex(RuntimeError, '配置已变更'):
            demo.launch_command(run)

    def test_explicit_lan_binding_keeps_online_authentication(self):
        self.accept_existing_eula()
        run = demo.prepare(self.bundle, 'normal', 25588, '0.0.0.0')
        command = demo.launch_command(run)
        self.assertIn('-Dhardrush.round.initialMode=normal', command)
        self.assertIn('online-mode=true', (run / 'server.properties').read_text())


if __name__ == '__main__':
    unittest.main()
