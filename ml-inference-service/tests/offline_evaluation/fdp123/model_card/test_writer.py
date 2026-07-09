import hashlib
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch

from offline_evaluation.fdp123.model_card.run_model_card_generation import main
from offline_evaluation.fdp123.model_card.schema import Fdp123ModelCardValidationError
from offline_evaluation.fdp123.model_card.writer import (
    build_model_card_manifest,
    model_card_json,
    model_card_markdown,
    write_model_card_artifacts,
)
try:
    from fdp123.model_card.test_generator import MODEL_CARD_GENERATED_AT, fdp124_artifacts, model_metadata
    from fdp123.model_card.test_schema import valid_model_card
except ModuleNotFoundError:
    from test_generator import MODEL_CARD_GENERATED_AT, fdp124_artifacts, model_metadata
    from test_schema import valid_model_card


class Fdp123ModelCardWriterTest(unittest.TestCase):
    def test_writesModelCardJsonMarkdownAndManifest(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = write_model_card_artifacts(valid_model_card(), Path(directory))

            self.assertTrue(paths["modelCardJson"].exists())
            self.assertTrue(paths["modelCardMarkdown"].exists())
            self.assertTrue(paths["manifest"].exists())

    def test_manifestIsWrittenLast(self):
        original_replace = os.replace
        replace_calls = []

        def recording_replace(source, destination):
            replace_calls.append((Path(source).name, Path(destination).name))
            return original_replace(source, destination)

        with tempfile.TemporaryDirectory() as directory:
            with patch("offline_evaluation.fdp123.model_card.writer.os.replace", recording_replace):
                write_model_card_artifacts(valid_model_card(), Path(directory))

        self.assertEqual(("manifest.json.tmp", "manifest.json"), replace_calls[-1])

    def test_manifestHashesAndSizesMatchWrittenFiles(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            write_model_card_artifacts(valid_model_card(), output)
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))

            self.assertEqual("MODEL_CARD_V1", manifest["reportType"])
            self.assertEqual("model-card-artifact-set-v1", manifest["artifactSetVersion"])
            for item in manifest["files"]:
                payload = (output / item["name"]).read_bytes()
                self.assertEqual(len(payload), item["sizeBytes"])
                self.assertEqual(hashlib.sha256(payload).hexdigest(), item["sha256"])

    def test_rejectsSymlinkOutputDirectory(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "target"
            target.mkdir()
            link = Path(directory) / "link"
            try:
                link.symlink_to(target, target_is_directory=True)
            except OSError as exception:
                self.skipTest(f"symlink creation unavailable: {exception}")

            with self.assertRaises(Fdp123ModelCardValidationError):
                write_model_card_artifacts(valid_model_card(), link)

    def test_rejectsSymlinkFinalArtifactPath(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "out"
            output.mkdir()
            target = Path(directory) / "target.json"
            target.write_text("{}", encoding="utf-8")
            link = output / "model_card.json"
            try:
                link.symlink_to(target)
            except OSError as exception:
                self.skipTest(f"symlink creation unavailable: {exception}")

            with self.assertRaises(Fdp123ModelCardValidationError):
                write_model_card_artifacts(valid_model_card(), output)

    def test_rejectsSymlinkManifestPath(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "out"
            output.mkdir()
            target = Path(directory) / "target.json"
            target.write_text("{}", encoding="utf-8")
            link = output / "manifest.json"
            try:
                link.symlink_to(target)
            except OSError as exception:
                self.skipTest(f"symlink creation unavailable: {exception}")

            with self.assertRaises(Fdp123ModelCardValidationError):
                write_model_card_artifacts(valid_model_card(), output)

    def test_rejectsOutputOutsideAllowOutputRoot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "allowed"
            output = Path(directory) / "outside"

            with self.assertRaises(Fdp123ModelCardValidationError):
                write_model_card_artifacts(valid_model_card(), output, allow_output_root=root)

    def test_cleansTempFilesOnFailure(self):
        def failing_replace(source, destination):
            raise OSError("simulated replace failure")

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with patch("offline_evaluation.fdp123.model_card.writer.os.replace", failing_replace):
                with self.assertRaises(OSError):
                    write_model_card_artifacts(valid_model_card(), output)

            self.assertFalse((output / "manifest.json").exists())
            self.assertEqual([], list(output.glob("*.tmp")))

    def test_failureBeforeManifestDoesNotCreateValidArtifactSet(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with self.assertRaises(Fdp123ModelCardValidationError):
                write_model_card_artifacts(valid_model_card(rawPayload="unsafe"), output)

            self.assertFalse((output / "manifest.json").exists())

    def test_markdownSafetyFilterRejectsForbiddenTerms(self):
        with self.assertRaises(Fdp123ModelCardValidationError):
            model_card_markdown(valid_model_card(warnings=["TOKEN"]))

    def test_jsonSafetyFilterRejectsForbiddenTerms(self):
        with self.assertRaises(Fdp123ModelCardValidationError):
            model_card_json(valid_model_card(rawPayload="unsafe"))

    def test_buildManifestRejectsUnsafePayload(self):
        with self.assertRaises(Fdp123ModelCardValidationError):
            build_model_card_manifest({Path("rawPayload.json"): "{}\n"}, MODEL_CARD_GENERATED_AT)


class Fdp123ModelCardCliTest(unittest.TestCase):
    def test_cliGeneratesArtifactsFromValidInputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "model-card"
            with fdp124_artifacts() as paths:
                result = main(cli_args(paths, output, root))

            self.assertEqual(0, result)
            self.assertTrue((output / "model_card.json").exists())
            self.assertTrue((output / "model_card.md").exists())
            self.assertTrue((output / "manifest.json").exists())

    def test_cliRejectsOutputOutsideAllowOutputRoot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "allowed"
            output = Path(directory) / "outside"
            with fdp124_artifacts() as paths:
                with self.assertRaises(Fdp123ModelCardValidationError):
                    main(cli_args(paths, output, root))

    def test_cliPassesGeneratedAtIntoOutput(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "model-card"
            with fdp124_artifacts() as paths:
                main(cli_args(paths, output, root))

            card = json.loads((output / "model_card.json").read_text(encoding="utf-8"))
            self.assertEqual(MODEL_CARD_GENERATED_AT, card["generatedAt"])

    def test_cliFailsIfRequiredModelMetadataMissing(self):
        with fdp124_artifacts() as paths:
            args = cli_args(paths, Path("out"), Path("."))
            args.remove("--model-version")
            args.remove("2026.06.12-offline")
            with self.assertRaises(SystemExit):
                with redirect_stderr(io.StringIO()):
                    main(args)

    def test_cliDoesNotAcceptDefaultModelVersion(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "model-card"
            with fdp124_artifacts() as paths:
                args = cli_args(paths, output, root)
                args[args.index("2026.06.12-offline")] = "v1"
                with self.assertRaises(Fdp123ModelCardValidationError):
                    main(args)


def cli_args(paths, output, root):
    metadata = model_metadata()
    args = [
        "--evaluation-summary", str(paths["evaluationSummary"]),
        "--evaluation-manifest", str(paths["manifest"]),
        "--model-name", metadata["modelName"],
        "--model-version", metadata["modelVersion"],
        "--model-family", metadata["modelFamily"],
        "--training-mode", metadata["trainingMode"],
        "--feature-contract-version", metadata["featureContractVersion"],
        "--reference-quality", metadata["referenceQuality"],
        "--output-dir", str(output),
        "--allow-output-root", str(root),
        "--generated-at", MODEL_CARD_GENERATED_AT,
    ]
    for value in metadata["allowedUsageModes"]:
        args.extend(["--allowed-usage-mode", value])
    for value in metadata["intendedUse"]:
        args.extend(["--intended-use", value])
    for value in metadata["notIntendedUse"]:
        args.extend(["--not-intended-use", value])
    for value in metadata["limitations"]:
        args.extend(["--limitation", value])
    for value in metadata["governanceBoundary"]:
        args.extend(["--governance-boundary", value])
    return args


if __name__ == "__main__":
    unittest.main()
