"""Exercise the downloaded, checksum-pinned kit's real compare CLI.

Run with PYTHONPATH pointing at the extracted kit, after checksum verification.
These tests deliberately require the kit: missing tooling must fail PR checks.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

import sdk_buildkit


class PakCompareExitTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        kit = Path(sdk_buildkit.__file__).resolve().parent
        references = sorted((kit / 'reference_paks').glob('*.pak'))
        self.assertTrue(references, 'pinned kit must include reference paks')
        self.reference = references[0]
        # Propagate the same verified kit to child interpreters, including
        # when the parent was invoked with an absolute PYTHONPATH.
        self.env = dict(os.environ, PYTHONPATH=str(kit.parent))

    def compare(self, pak, *reference_args):
        return subprocess.run(
            [sys.executable, '-m', 'sdk_buildkit', 'pak-compare', str(pak),
             *map(str, reference_args)],
            env=self.env, capture_output=True, text=True, check=False,
        )

    def test_clean_comparison_exits_zero(self):
        result = self.compare(self.reference, '--reference-dir', self.reference.parent)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_blocking_findings_exit_nonzero(self):
        broken = self.root / 'missing-manifest.pak'
        with zipfile.ZipFile(self.reference) as original, zipfile.ZipFile(broken, 'w') as edited:
            for member in original.infolist():
                if member.filename != 'manifest.txt':
                    edited.writestr(member, original.read(member))
        result = self.compare(broken, '--reference-dir', self.reference.parent)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('BLOCKING', result.stdout + result.stderr)

    def test_empty_reference_directory_exits_nonzero(self):
        self.assertNotEqual(self.compare(
            self.reference, '--reference-dir', self.root).returncode, 0)

    def test_missing_input_exits_nonzero(self):
        self.assertNotEqual(self.compare(
            self.root / 'missing.pak', self.reference).returncode, 0)

    def test_corrupt_input_exits_nonzero(self):
        corrupt = self.root / 'corrupt.pak'
        corrupt.write_text('not a zip')
        self.assertNotEqual(self.compare(corrupt, self.reference).returncode, 0)


if __name__ == '__main__':
    unittest.main()
