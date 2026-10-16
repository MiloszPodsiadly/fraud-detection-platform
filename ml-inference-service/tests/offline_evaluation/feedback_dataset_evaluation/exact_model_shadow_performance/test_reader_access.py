from __future__ import annotations

import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path

from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.reader_access import (
    READINESS_LABEL,
    READINESS_MARKER,
    ExactModelReaderAccessError,
    prepare_artifact_root,
    verify_artifact_root_access,
    verify_prepared_artifact_root,
)


@unittest.skipUnless(sys.platform.startswith("linux"), "POSIX ACL contract requires Linux")
class ExactModelReaderAccessTest(unittest.TestCase):
    def test_prepares_exclusive_reader_acl_and_canonical_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory) / "generated"
            root = parent / "model-shadow-performance"

            prepare_artifact_root(root, parent)

            verify_artifact_root_access(root)
            verify_prepared_artifact_root(root, parent)
            self.assertEqual(READINESS_LABEL, (root / READINESS_MARKER).read_text(encoding="utf-8"))
            self.assertEqual(0, stat.S_IMODE(root.stat().st_mode) & stat.S_IRWXO)

    def test_old_owner_only_root_fails_instead_of_publishing_unreadable_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "model-shadow-performance"
            root.mkdir(mode=0o700)

            with self.assertRaisesRegex(ExactModelReaderAccessError, "required POSIX ACL is missing"):
                verify_artifact_root_access(root)

    def test_rejects_symlinked_parent_before_external_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            external = base / "external"
            external.mkdir(mode=0o711)
            marker = external / "unchanged"
            marker.write_text("unchanged\n", encoding="utf-8")
            original_mode = stat.S_IMODE(external.stat().st_mode)
            configured_parent = base / "generated"
            try:
                configured_parent.symlink_to(external, target_is_directory=True)
            except OSError as exception:
                self.skipTest(f"symlink creation unavailable: {exception}")

            with self.assertRaisesRegex(ExactModelReaderAccessError, "unsafe"):
                prepare_artifact_root(configured_parent / "model-shadow-performance", configured_parent)

            self.assertEqual("unchanged\n", marker.read_text(encoding="utf-8"))
            self.assertEqual(original_mode, stat.S_IMODE(external.stat().st_mode))
            self.assertFalse((external / "model-shadow-performance").exists())

    def test_rejects_symlinked_marker_without_replacing_its_target(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory) / "generated"
            root = parent / "model-shadow-performance"
            root.mkdir(parents=True)
            external = Path(directory) / "external-marker"
            external.write_text("external\n", encoding="utf-8")
            try:
                (root / READINESS_MARKER).symlink_to(external)
            except OSError as exception:
                self.skipTest(f"symlink creation unavailable: {exception}")

            with self.assertRaisesRegex(ExactModelReaderAccessError, "regular file"):
                prepare_artifact_root(root, parent)

            self.assertEqual("external\n", external.read_text(encoding="utf-8"))

    def test_preflight_rejects_tampered_readiness_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory) / "generated"
            root = parent / "model-shadow-performance"
            prepare_artifact_root(root, parent)
            marker = root / READINESS_MARKER
            marker.chmod(0o600)
            marker.write_text("com.frauddetection.artifact-root-ready=false\n", encoding="utf-8")

            with self.assertRaisesRegex(ExactModelReaderAccessError, "marker"):
                verify_prepared_artifact_root(root, parent)


if __name__ == "__main__":
    unittest.main()
