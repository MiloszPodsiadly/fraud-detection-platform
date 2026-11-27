#!/usr/bin/env node
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { SECURITY_POLICY } from "../config/policy.mjs";

const outputDirectory = process.env.OUTPUT_DIRECTORY;
if (!outputDirectory) throw new Error("OUTPUT_DIRECTORY is required.");

function readEvidence(name) {
  return JSON.parse(fs.readFileSync(path.join(outputDirectory, name), "utf8"));
}

function sha256(name) {
  return createHash("sha256").update(fs.readFileSync(path.join(outputDirectory, name))).digest("hex");
}

function optionalSha256(name) {
  return fs.existsSync(path.join(outputDirectory, name)) ? sha256(name) : null;
}

function main() {
  const files = SECURITY_POLICY.artifacts.imageEvidenceFiles;
  const evidence = readEvidence(files.evidence);
  const result = {
    testedGitSha: evidence.testedGitSha,
    imageRef: evidence.imageRef,
    imagePlatform: evidence.imagePlatform,
    imageIdDigest: evidence.imageIdDigest,
    dockerImageIdKind: evidence.dockerImageIdKind,
    dockerEngineId: evidence.dockerEngineId,
    dockerEngineConfigDigest: evidence.dockerEngineConfigDigest,
    dockerEngineDescriptorDigest: evidence.dockerEngineDescriptorDigest,
    dockerEngineDescriptorMediaType: evidence.dockerEngineDescriptorMediaType,
    dockerEngineDescriptorConfigDigest: evidence.dockerEngineDescriptorConfigDigest,
    scannedImageDigest: evidence.scannedImageDigest,
    scannedImageIdentitySource: evidence.scannedImageIdentitySource,
    repoDigests: evidence.repoDigests,
    sourceImageDigest: evidence.sourceImageDigest,
    sourceManifestMediaType: evidence.sourceManifestMediaType,
    sourceManifestContentDigest: evidence.sourceManifestContentDigest,
    platformManifestDigest: evidence.platformManifestDigest,
    platformManifestMediaType: evidence.platformManifestMediaType,
    platformManifestContentDigest: evidence.platformManifestContentDigest,
    platformConfigDigest: evidence.platformConfigDigest,
    buildxActionDigest: evidence.buildxActionDigest,
    buildxDigest: evidence.buildxDigest,
    buildxConfigDigest: evidence.buildxConfigDigest,
    buildxOciArchiveSha256: evidence.buildxOciArchiveSha256,
    scannerInputImageId: evidence.imageIdDigest,
    artifactSha256: {
      evidence: sha256(files.evidence),
      dockerImageInspect: sha256(files.dockerInspect),
      dockerImageInspectAfterScan: optionalSha256(files.dockerInspectAfterScan),
      trivy: optionalSha256(files.trivy),
      sbomRaw: optionalSha256(files.rawSbom),
      sbomBound: optionalSha256(files.boundSbom),
      buildxMetadata: optionalSha256(files.buildxMetadata),
      registryIdentity: optionalSha256(files.registryIdentity),
    },
    scanner: {
      name: "trivy",
      version: `v${SECURITY_POLICY.tools.trivy}`,
      outcome: process.env.TRIVY_OUTCOME,
      policy: SECURITY_POLICY.vulnerabilityPolicy.blockingSeverities.join(","),
    },
    sbom: {
      format: SECURITY_POLICY.formats.imageSbom,
      generator: "syft",
      version: `v${SECURITY_POLICY.tools.syft}`,
      outcome: process.env.SBOM_OUTCOME,
      bindingOutcome: process.env.SBOM_BINDING_OUTCOME,
    },
  };
  fs.writeFileSync(path.join(outputDirectory, files.scanResult), `${JSON.stringify(result, null, 2)}\n`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
