#!/usr/bin/env node
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

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
  const evidence = readEvidence("image-security-evidence.json");
  const result = {
    testedGitSha: evidence.testedGitSha,
    imageRef: evidence.imageRef,
    imagePlatform: evidence.imagePlatform,
    imageIdDigest: evidence.imageIdDigest,
    dockerImageIdKind: evidence.dockerImageIdKind,
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
    scannerInputImageId: evidence.imageIdDigest,
    artifactSha256: {
      evidence: sha256("image-security-evidence.json"),
      trivy: optionalSha256("trivy-image.json"),
      sbomRaw: optionalSha256("image.cdx.raw.json"),
      sbomBound: optionalSha256("image.cdx.json"),
      buildxMetadata: optionalSha256("buildx-metadata.json"),
      registryIdentity: optionalSha256("registry-image-identity.json"),
    },
    scanner: {
      name: "trivy",
      version: process.env.SCANNER_VERSION,
      outcome: process.env.TRIVY_OUTCOME,
      policy: "HIGH,CRITICAL",
    },
    sbom: {
      format: "CycloneDX JSON",
      generator: "syft",
      version: process.env.SBOM_GENERATOR_VERSION,
      outcome: process.env.SBOM_OUTCOME,
      bindingOutcome: process.env.SBOM_BINDING_OUTCOME,
    },
  };
  fs.writeFileSync(path.join(outputDirectory, "image-scan-result.json"), `${JSON.stringify(result, null, 2)}\n`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
