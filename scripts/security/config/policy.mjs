import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const CONFIG_PATH = fileURLToPath(new URL("./security-policy.json", import.meta.url));
export const SECURITY_POLICY = loadPolicy();

function loadPolicy() {
  const config = JSON.parse(fs.readFileSync(CONFIG_PATH, "utf8"));
  return validateSecurityPolicy(config);
}

export function validateSecurityPolicy(config) {
  const record = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
  const uniqueStrings = (value, pattern = /^.+$/) => Array.isArray(value)
    && value.length > 0
    && value.every((item) => typeof item === "string" && pattern.test(item))
    && new Set(value).size === value.length;
  const requiredFiles = config?.platformGate?.requiredFiles;
  const imageValidationJobs = config?.platformGate?.imageValidationJobs;
  const imageIdentity = config?.imageIdentity;
  const evidenceFiles = config?.artifacts?.imageEvidenceFiles;
  const vulnerabilityPolicy = config?.vulnerabilityPolicy;
  const secretProof = config?.secretProof;
  const allowedSeverities = new Set(["UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"]);
  const safeJsonFileName = /^(?!.*\.\.)[a-z0-9][a-z0-9.-]*\.json$/;
  const requiredTools = ["node", "npm", "python", "pip", "pipAudit", "trivy", "syft", "cyclonedxBom",
    "mavenDependencyPlugin", "cyclonedxMavenPlugin"];
  if (!record(config)
    || config.schemaVersion !== 1
    || !record(config.tools)
    || requiredTools.some((tool) => typeof config.tools[tool] !== "string")
    || Object.values(config.tools).some((version) => typeof version !== "string" || !/^\d+\.\d+(?:\.\d+)?$/.test(version))
    || !/^\d+\.\d+\.\d+$/.test(config.tools?.trivy ?? "")
    || !/^\d+\.\d+\.\d+$/.test(config.tools?.syft ?? "")
    || !/^\d+\.\d+\.\d+$/.test(config.tools?.pipAudit ?? "")
    || !/^[a-f0-9]{128}$/.test(config.downloadIntegrity?.npmArchiveSha512 ?? "")
    || !/^[a-f0-9]{64}$/.test(config.downloadIntegrity?.cyclonedxWheelSha256 ?? "")
    || !/^[a-f0-9]{64}$/.test(config.downloadIntegrity?.pipAuditWheelSha256 ?? "")
    || config.downloadIntegrity?.pipIndexUrl !== "https://pypi.org/simple"
    || !uniqueStrings(vulnerabilityPolicy?.visibleSeverities, /^[A-Z]+$/)
    || vulnerabilityPolicy.visibleSeverities.some((severity) => !allowedSeverities.has(severity))
    || !uniqueStrings(vulnerabilityPolicy?.blockingSeverities, /^[A-Z]+$/)
    || vulnerabilityPolicy.blockingSeverities.some((severity) => !allowedSeverities.has(severity)
      || !vulnerabilityPolicy.visibleSeverities.includes(severity))
    || !uniqueStrings(vulnerabilityPolicy?.npmBlockingSeverities, /^[a-z]+$/)
    || vulnerabilityPolicy.npmBlockingSeverities.some((severity) => !["info", "low", "moderate", "high", "critical"].includes(severity))
    || !/^\d+m$/.test(vulnerabilityPolicy?.secretTimeout ?? "")
    || !/^\d+m$/.test(vulnerabilityPolicy?.repositoryTimeout ?? "")
    || !/^\d+m$/.test(vulnerabilityPolicy?.imageTimeout ?? "")
    || !Number.isInteger(config.processLimits?.secretProofTotalMs) || config.processLimits.secretProofTotalMs <= 0
    || !Number.isInteger(config.processLimits?.secretVersionProcessMs) || config.processLimits.secretVersionProcessMs <= 0
    || !Number.isInteger(config.processLimits?.secretMavenProcessMs) || config.processLimits.secretMavenProcessMs <= 0
    || !Number.isInteger(config.processLimits?.secretScanProcessMs) || config.processLimits.secretScanProcessMs <= 0
    || !/^\d+m$/.test(config.processLimits?.secretScanCliTimeout ?? "")
    || !record(secretProof)
    || typeof secretProof.expectedRuleId !== "string" || !/^[a-z0-9-]+$/.test(secretProof.expectedRuleId)
    || !allowedSeverities.has(secretProof.expectedSeverity)
    || !vulnerabilityPolicy.visibleSeverities.includes(secretProof.expectedSeverity)
    || !Number.isInteger(secretProof.expectedFindingCount) || secretProof.expectedFindingCount !== 1
    || !Number.isInteger(config.artifacts?.retentionDays) || config.artifacts.retentionDays <= 0
    || typeof config.artifacts.imageSecuritySuffix !== "string" || !/^[a-z0-9-]+$/.test(config.artifacts.imageSecuritySuffix)
    || !Number.isInteger(imageIdentity?.evidenceSchemaVersion) || imageIdentity.evidenceSchemaVersion <= 0
    || !/^linux\/[a-z0-9_]+$/.test(config.platforms?.image ?? "")
    || config.formats?.imageSbom !== "CycloneDX JSON"
    || config.formats?.imageSbomBomFormat !== "CycloneDX"
    || !Number.isInteger(config.formats?.trivyImageSchemaVersion)
    || config.formats?.packageUrlPrefix !== "pkg:"
    || !uniqueStrings(imageIdentity?.allowedSources)
    || !uniqueStrings(imageIdentity?.dockerImageIdKinds)
    || !uniqueStrings(imageIdentity?.manifestMediaTypes)
    || !uniqueStrings(imageIdentity?.indexMediaTypes)
    || typeof imageIdentity?.sbomPropertyPrefix !== "string" || imageIdentity.sbomPropertyPrefix.length === 0
    || !record(config.platformGate)
    || typeof config.platformGate.artifactName !== "string" || !/^[a-z0-9-]+$/.test(config.platformGate.artifactName)
    || !uniqueStrings(config.platformGate.requiredProducerJobs, /^[a-z0-9_-]+$/)
    || !uniqueStrings(config.platformGate.requiredArtifactBases, /^[a-z0-9-]+$/)
    || !uniqueStrings(config.platformGate.historicalImageArtifacts, /^[a-z0-9-]+$/)
    || !record(imageValidationJobs)
    || !uniqueStrings(imageValidationJobs.inventory, /^[a-z0-9-]+$/)
    || !uniqueStrings(imageValidationJobs.historical, /^[a-z0-9-]+$/)
    || !record(requiredFiles)
    || ["npmAudit", "pythonAudit", "repositoryConfig", "secretProof", "vulnerabilityCombined", "vulnerabilitySummary",
      "vulnerabilityRepositoryReport", "vulnerabilityMavenReport", "imageValidationSummary"]
      .some((key) => typeof requiredFiles[key] !== "string" || !safeJsonFileName.test(requiredFiles[key]))
    || !uniqueStrings(requiredFiles.sbomDocuments, safeJsonFileName)
    || !record(evidenceFiles)
    || Object.values(evidenceFiles).some((name) => typeof name !== "string" || !safeJsonFileName.test(name))
    || new Set(Object.values(evidenceFiles)).size !== Object.values(evidenceFiles).length) {
    throw new Error("Central security policy is malformed.");
  }
  return deepFreeze(config);
}

function deepFreeze(value) {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value)) deepFreeze(child);
  }
  return value;
}

export function securityPolicyOutputs(policy = SECURITY_POLICY) {
  const files = policy.artifacts.imageEvidenceFiles;
  return {
    node_version: policy.tools.node,
    npm_version: policy.tools.npm,
    python_version: policy.tools.python,
    pip_version: policy.tools.pip,
    pip_audit_version: policy.tools.pipAudit,
    trivy_version: `v${policy.tools.trivy}`,
    trivy_version_number: policy.tools.trivy,
    syft_version: `v${policy.tools.syft}`,
    cyclonedx_bom_version: policy.tools.cyclonedxBom,
    maven_dependency_plugin_version: policy.tools.mavenDependencyPlugin,
    cyclonedx_maven_plugin_version: policy.tools.cyclonedxMavenPlugin,
    image_platform: policy.platforms.image,
    all_vulnerability_severities: policy.vulnerabilityPolicy.visibleSeverities.join(","),
    blocking_vulnerability_severities: policy.vulnerabilityPolicy.blockingSeverities.join(","),
    image_scan_timeout: policy.vulnerabilityPolicy.imageTimeout,
    repository_scan_timeout: policy.vulnerabilityPolicy.repositoryTimeout,
    artifact_retention_days: String(policy.artifacts.retentionDays),
    image_security_suffix: policy.artifacts.imageSecuritySuffix,
    secret_scan_timeout: policy.vulnerabilityPolicy.secretTimeout,
    platform_gate_artifact_name: policy.platformGate.artifactName,
    image_evidence_file: files.evidence,
    docker_image_inspect_file: files.dockerInspect,
    docker_image_inspect_after_scan_file: files.dockerInspectAfterScan,
    image_scan_result_file: files.scanResult,
    trivy_image_file: files.trivy,
    raw_image_sbom_file: files.rawSbom,
    bound_image_sbom_file: files.boundSbom,
    buildx_metadata_file: files.buildxMetadata,
    registry_identity_file: files.registryIdentity,
  };
}

export function securityPolicyValue(dotPath, policy = SECURITY_POLICY) {
  const value = dotPath.split(".").reduce((current, segment) => current?.[segment], policy);
  if (typeof value !== "string" && typeof value !== "number" && typeof value !== "boolean") {
    throw new Error(`Security policy value "${dotPath}" is missing or not scalar.`);
  }
  return String(value);
}

export function writeSecurityPolicyOutputs(outputPath, policy = SECURITY_POLICY) {
  if (!outputPath) throw new Error("Security policy output path is required.");
  const output = Object.entries(securityPolicyOutputs(policy))
    .map(([name, value]) => `${name}=${String(value).replace(/[\r\n]/g, "")}`)
    .join("\n");
  fs.mkdirSync(path.dirname(path.resolve(outputPath)), { recursive: true });
  fs.appendFileSync(outputPath, `${output}\n`, "utf8");
}
