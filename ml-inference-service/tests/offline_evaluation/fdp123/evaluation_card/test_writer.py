import hashlib
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch

from offline_evaluation.fdp123.evaluation_card.run_evaluation_card_generation import main
from offline_evaluation.fdp123.evaluation_card.schema import Fdp123EvaluationCardValidationError
from offline_evaluation.fdp123.evaluation_card.artifact_reader import read_validated_evaluation_card_artifact_set
from offline_evaluation.fdp123.evaluation_card.writer import (
    build_evaluation_card_manifest,
    evaluation_card_json,
    evaluation_card_markdown,
    write_evaluation_card_artifacts,
)
try:
    from fdp123.evaluation_card.test_generator import PLATFORM_RECOMMENDATION_EVALUATION_CARD_GENERATED_AT, fdp124_artifacts, model_metadata
    from fdp123.evaluation_card.test_schema import valid_evaluation_card
except ModuleNotFoundError:
    try:
        from .test_generator import PLATFORM_RECOMMENDATION_EVALUATION_CARD_GENERATED_AT, fdp124_artifacts, model_metadata
        from .test_schema import valid_evaluation_card
    except ImportError:
        from test_generator import PLATFORM_RECOMMENDATION_EVALUATION_CARD_GENERATED_AT, fdp124_artifacts, model_metadata
        from test_schema import valid_evaluation_card


class Fdp123EvaluationCardWriterTest(unittest.TestCase):
    def test_writesEvaluationCardJsonMarkdownAndManifest(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = write_evaluation_card_artifacts(valid_evaluation_card(), Path(directory))

            self.assertTrue(paths["evaluationCardJson"].exists())
            self.assertTrue(paths["evaluationCardMarkdown"].exists())
            self.assertTrue(paths["manifest"].exists())

    def test_manifestIsWrittenLast(self):
        original_replace = os.replace
        replace_calls = []

        def recording_replace(source, destination):
            replace_calls.append((Path(source).name, Path(destination).name))
            return original_replace(source, destination)

        with tempfile.TemporaryDirectory() as directory:
            with patch("offline_evaluation.fdp123.evaluation_card.writer.os.replace", recording_replace):
                write_evaluation_card_artifacts(valid_evaluation_card(), Path(directory))

        self.assertEqual(("manifest.json.tmp", "manifest.json"), replace_calls[-1])

    def test_manifestHashesAndSizesMatchWrittenFiles(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            write_evaluation_card_artifacts(valid_evaluation_card(), output)
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))

            self.assertEqual("PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1", manifest["reportType"])
            self.assertEqual("platform-recommendation-evaluation-card-artifact-set-v1", manifest["artifactSetVersion"])
            self.assertEqual(
                ["platform_recommendation_evaluation_card.json", "platform_recommendation_evaluation_card.md"],
                sorted(item["name"] for item in manifest["files"]),
            )
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

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                write_evaluation_card_artifacts(valid_evaluation_card(), link)

    def test_rejectsSymlinkFinalArtifactPath(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "out"
            output.mkdir()
            target = Path(directory) / "target.json"
            target.write_text("{}", encoding="utf-8")
            link = output / "platform_recommendation_evaluation_card.json"
            try:
                link.symlink_to(target)
            except OSError as exception:
                self.skipTest(f"symlink creation unavailable: {exception}")

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                write_evaluation_card_artifacts(valid_evaluation_card(), output)

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

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                write_evaluation_card_artifacts(valid_evaluation_card(), output)

    def test_rejectsOutputOutsideAllowOutputRoot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "allowed"
            output = Path(directory) / "outside"

            with self.assertRaises(Fdp123EvaluationCardValidationError):
                write_evaluation_card_artifacts(valid_evaluation_card(), output, allow_output_root=root)

    def test_cleansTempFilesOnFailure(self):
        def failing_replace(source, destination):
            raise OSError("simulated replace failure")

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with patch("offline_evaluation.fdp123.evaluation_card.writer.os.replace", failing_replace):
                with self.assertRaises(OSError):
                    write_evaluation_card_artifacts(valid_evaluation_card(), output)

            self.assertFalse((output / "manifest.json").exists())
            self.assertEqual([], list(output.glob("*.tmp")))

    def test_failureBeforeManifestDoesNotCreateValidArtifactSet(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                write_evaluation_card_artifacts(valid_evaluation_card(rawPayload="unsafe"), output)

            self.assertFalse((output / "manifest.json").exists())

    def test_markdownSafetyFilterRejectsForbiddenTerms(self):
        with self.assertRaises(Fdp123EvaluationCardValidationError):
            evaluation_card_markdown(valid_evaluation_card(warnings=["TOKEN"]))

    def test_jsonSafetyFilterRejectsForbiddenTerms(self):
        with self.assertRaises(Fdp123EvaluationCardValidationError):
            evaluation_card_json(valid_evaluation_card(rawPayload="unsafe"))

    def test_buildManifestRejectsUnsafePayload(self):
        with self.assertRaises(Fdp123EvaluationCardValidationError):
            build_evaluation_card_manifest({Path("rawPayload.json"): "{}\n"}, PLATFORM_RECOMMENDATION_EVALUATION_CARD_GENERATED_AT)

    def test_interruptedOverwriteRemovesFinalManifestAndRejectsMixedDirectory(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            card_a = valid_evaluation_card()
            card_b = valid_evaluation_card(generatedAt="2026-06-12T00:00:01Z")
            write_evaluation_card_artifacts(card_a, output)
            validated_a, _manifest_sha = read_validated_evaluation_card_artifact_set(
                output / "platform_recommendation_evaluation_card.json",
                output / "manifest.json",
            )
            self.assertEqual("2026-06-12T00:00:00Z", validated_a["generatedAt"])
            real_replace = os.replace

            def fail_after_json_replace(source, destination):
                real_replace(source, destination)
                if Path(destination).name == "platform_recommendation_evaluation_card.json":
                    raise OSError("injected after card json replace")

            with patch("offline_evaluation.fdp123.evaluation_card.writer.os.replace", fail_after_json_replace):
                with self.assertRaises(OSError):
                    write_evaluation_card_artifacts(card_b, output)

            self.assertFalse((output / "manifest.json").exists())
            self.assertFalse((output / "platform_recommendation_evaluation_card.json.tmp").exists())
            self.assertFalse((output / "platform_recommendation_evaluation_card.md.tmp").exists())
            self.assertFalse((output / "manifest.json.tmp").exists())
            with self.assertRaises(Fdp123EvaluationCardValidationError):
                read_validated_evaluation_card_artifact_set(
                    output / "platform_recommendation_evaluation_card.json",
                    output / "manifest.json",
                )

            write_evaluation_card_artifacts(card_b, output)
            validated_b, _manifest_sha = read_validated_evaluation_card_artifact_set(
                output / "platform_recommendation_evaluation_card.json",
                output / "manifest.json",
            )
            self.assertEqual("2026-06-12T00:00:01Z", validated_b["generatedAt"])


class Fdp123EvaluationCardCliTest(unittest.TestCase):
    def test_cliGeneratesArtifactsFromValidInputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "platform-recommendation-evaluation-card"
            with fdp124_artifacts() as paths:
                result = main(cli_args(paths, output, root))

            self.assertEqual(0, result)
            self.assertTrue((output / "platform_recommendation_evaluation_card.json").exists())
            self.assertTrue((output / "platform_recommendation_evaluation_card.md").exists())
            self.assertTrue((output / "manifest.json").exists())

    def test_cliRejectsOutputOutsideAllowOutputRoot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "allowed"
            output = Path(directory) / "outside"
            with fdp124_artifacts() as paths:
                with self.assertRaises(Fdp123EvaluationCardValidationError):
                    main(cli_args(paths, output, root))

    def test_cliPassesGeneratedAtIntoOutput(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "platform-recommendation-evaluation-card"
            with fdp124_artifacts() as paths:
                main(cli_args(paths, output, root))

            card = json.loads((output / "platform_recommendation_evaluation_card.json").read_text(encoding="utf-8"))
            self.assertEqual(PLATFORM_RECOMMENDATION_EVALUATION_CARD_GENERATED_AT, card["generatedAt"])

    def test_cliRequiresAllowOutputRoot(self):
        with fdp124_artifacts() as paths:
            args = cli_args(paths, Path("out"), Path("."))
            root_index = args.index("--allow-output-root")
            del args[root_index:root_index + 2]
            with self.assertRaises(SystemExit):
                with redirect_stderr(io.StringIO()):
                    main(args)

    def test_cliRejectsOldCallerControlledIdentityArgs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "platform-recommendation-evaluation-card"
            with fdp124_artifacts() as paths:
                args = cli_args(paths, output, root)
                args.extend(["--model-version", "2026.06.12-offline"])
                with self.assertRaises(SystemExit):
                    with redirect_stderr(io.StringIO()):
                        main(args)

    def test_cliRejectsGovernanceMetadataWithCallerControlledIdentity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "platform-recommendation-evaluation-card"
            with fdp124_artifacts() as paths:
                args = cli_args(paths, output, root)
                args.extend(["--limitation", "modelName"])
                with self.assertRaises(Fdp123EvaluationCardValidationError):
                    main(args)


def cli_args(paths, output, root):
    metadata = model_metadata()
    args = [
        "--evaluation-summary", str(paths["evaluationSummary"]),
        "--evaluation-manifest", str(paths["manifest"]),
        "--output-dir", str(output),
        "--allow-output-root", str(root),
        "--generated-at", PLATFORM_RECOMMENDATION_EVALUATION_CARD_GENERATED_AT,
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
