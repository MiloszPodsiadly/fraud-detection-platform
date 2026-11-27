#!/usr/bin/env python3
import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name('verify-python-sbom-inventory.py')
SPEC = importlib.util.spec_from_file_location('verify_python_sbom_inventory', SCRIPT)
INVENTORY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INVENTORY)


class PythonSbomInventoryTest(unittest.TestCase):
    def parse(self, content, marker_environment):
        with tempfile.TemporaryDirectory(prefix='fdp157-python-lock-') as directory:
            path = Path(directory) / 'requirements.txt'
            path.write_text(content, encoding='utf-8')
            return INVENTORY.read_hashed_requirements(path, marker_environment)

    def test_reads_exact_hash_locked_versions_and_applies_python_markers(self):
        marker_environment = {'python_version': '3.12', 'sys_platform': 'linux'}
        digest = 'a' * 64
        content = '\n'.join([
            'alpha==1.2.3 \\',
            f'    --hash=sha256:{digest} \\',
            'beta==2.0.0; python_version >= "3.12" \\',
            f'    --hash=sha256:{digest} \\',
            'gamma==3.0.0; sys_platform == "win32" \\',
            f'    --hash=sha256:{digest}',
        ]) + '\n'
        result = self.parse(content, marker_environment)
        self.assertEqual([entry['name'] for entry in result['packages']], ['alpha', 'beta', 'gamma'])
        self.assertEqual([entry['markerApplies'] for entry in result['packages']], [True, True, False])
        self.assertTrue(all(entry['hashes'] == [digest] for entry in result['packages']))

    def test_rejects_missing_hashes_malformed_hashes_and_unpinned_versions(self):
        marker_environment = {'python_version': '3.12', 'sys_platform': 'linux'}
        digest = 'a' * 64
        with self.assertRaisesRegex(ValueError, 'missing SHA-256 hashes'):
            self.parse('alpha==1.2.3\n', marker_environment)
        malformed = '\n'.join(['alpha==1.2.3', f'--hash=sha256:{digest[:-1]}'])
        with self.assertRaisesRegex(ValueError, 'malformed SHA-256'):
            self.parse(malformed, marker_environment)
        unpinned = '\n'.join(['alpha>=1.2.3', f'--hash=sha256:{digest}'])
        with self.assertRaisesRegex(ValueError, 'not an exact version pin'):
            self.parse(unpinned, marker_environment)


if __name__ == '__main__':
    unittest.main()
