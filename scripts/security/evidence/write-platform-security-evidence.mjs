#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { appendFileSync, lstatSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SECURITY_POLICY } from '../config/policy.mjs';
import { IMAGE_SECURITY_INVENTORY } from '../config/image-inventory.mjs';
import { validateTrivyVulnerabilityReport } from '../scanners/trivy-vulnerability-report.mjs';

const POLICY_PATH = fileURLToPath(new URL('../config/security-policy.json', import.meta.url));
const INVENTORY_PATH = fileURLToPath(new URL('../config/image-security-inventory.json', import.meta.url));
const EVIDENCE_FILES = SECURITY_POLICY.artifacts.imageEvidenceFiles;
const SHA_PATTERN = /^[a-f0-9]{40}$/;

export function buildPlatformSecurityEvidence({
  artifactDirectory,
  needs,
  testedGitSha,
  prHeadSha,
  prMergeSha = '',
  runId,
  runAttempt,
  repository,
  ref,
  workflow,
  eventName,
  createdAt = new Date().toISOString(),
}) {
  const failures = [];
  const requiredJobs = SECURITY_POLICY.platformGate.requiredProducerJobs;
  if (!needs || typeof needs !== 'object' || Array.isArray(needs)) {
    failures.push('Producer outcomes are missing or malformed.');
    needs = {};
  }
  const actualJobs = Object.keys(needs);
  for (const job of requiredJobs) {
    if (!Object.hasOwn(needs, job)) failures.push(`Required producer result is missing: ${job}.`);
    else if (needs[job]?.result !== 'success') {
      failures.push(`Required producer ${job} completed with ${String(needs[job]?.result ?? 'malformed result')}.`);
    }
  }
  for (const job of actualJobs) {
    if (!requiredJobs.includes(job)) failures.push(`Unexpected producer result was supplied: ${job}.`);
  }

  if (!SHA_PATTERN.test(testedGitSha ?? '')) failures.push('Tested revision is not a full Git SHA.');
  if (!SHA_PATTERN.test(prHeadSha ?? '')) failures.push('PR head revision is not a full Git SHA.');
  if (prMergeSha && !SHA_PATTERN.test(prMergeSha)) failures.push('PR merge revision is malformed.');
  if (prMergeSha && prMergeSha !== testedGitSha) failures.push('Tested revision does not match the recorded PR merge revision.');
  if (!/^\d+$/.test(String(runId ?? ''))) failures.push('Workflow run ID is missing or malformed.');
  if (!/^\d+$/.test(String(runAttempt ?? ''))) failures.push('Workflow run attempt is missing or malformed.');
  if (typeof repository !== 'string' || !repository.includes('/')) failures.push('Repository identity is missing or malformed.');

  const root = path.resolve(artifactDirectory);
  const artifactDirectories = new Map();
  try {
    for (const entry of readdirSync(root, { withFileTypes: true })) {
      const entryPath = path.join(root, entry.name);
      if (entry.isSymbolicLink()) {
        failures.push(`Downloaded evidence contains a symbolic link: ${entry.name}.`);
      } else if (entry.isDirectory()) {
        artifactDirectories.set(entry.name, entryPath);
      } else {
        failures.push(`Downloaded evidence contains an unexpected root file: ${entry.name}.`);
      }
    }
  } catch (error) {
    failures.push(`Downloaded evidence directory is unavailable: ${error.message}.`);
  }

  const requiredArtifactNames = SECURITY_POLICY.platformGate.requiredArtifactBases
    .map((base) => `${base}-${testedGitSha}`);
  const imageArtifactNames = [
    ...Object.values(IMAGE_SECURITY_INVENTORY.groups).flat().map((entry) => `${entry.artifactName}-${testedGitSha}`),
    ...SECURITY_POLICY.platformGate.historicalImageArtifacts.map((name) => `${name}-${testedGitSha}`),
  ];
  for (const artifactName of [...requiredArtifactNames, ...imageArtifactNames]) {
    if (!artifactDirectories.has(artifactName)) failures.push(`Required same-run evidence artifact is missing: ${artifactName}.`);
  }

  const contractFiles = SECURITY_POLICY.platformGate.requiredFiles;
  const findFile = (artifactName, fileName) => {
    const directory = artifactDirectories.get(artifactName);
    return directory ? findNamedFile(directory, fileName, failures) : null;
  };
  const readArtifactJson = (artifactName, fileName, description) => {
    const file = findFile(artifactName, fileName);
    if (!file) {
      failures.push(`${description} is missing from ${artifactName}.`);
      return null;
    }
    try {
      return JSON.parse(readFileSync(file, 'utf8'));
    } catch (error) {
      failures.push(`${description} is malformed JSON: ${error.message}.`);
      return null;
    }
  };

  const npmAudit = readArtifactJson(`npm-audit-${testedGitSha}`, contractFiles.npmAudit, 'npm audit report');
  if (npmAudit) {
    const counts = npmAudit.metadata?.vulnerabilities;
    const levels = ['info', 'low', 'moderate', 'high', 'critical'];
    if (!counts || typeof counts !== 'object' || Array.isArray(counts)
      || !Number.isInteger(counts.total) || levels.some((level) => !Number.isInteger(counts[level]) || counts[level] < 0)
      || counts.total !== levels.reduce((total, level) => total + counts[level], 0)) {
      failures.push('npm audit report has a malformed vulnerability summary.');
    } else if (SECURITY_POLICY.vulnerabilityPolicy.npmBlockingSeverities.some((severity) => counts[severity] > 0)) {
      const blockingFindings = SECURITY_POLICY.vulnerabilityPolicy.npmBlockingSeverities
        .map((severity) => `${counts[severity]} ${severity}`).join(' and ');
      failures.push(`npm audit report contains blocking vulnerabilities (${blockingFindings}).`);
    }
  }
  const pythonAudit = readArtifactJson(`pip-audit-${testedGitSha}`, contractFiles.pythonAudit, 'pip-audit report');
  if (pythonAudit) {
    if (!Array.isArray(pythonAudit.dependencies) || pythonAudit.dependencies.length === 0 || !Array.isArray(pythonAudit.fixes)
      || pythonAudit.dependencies.some((dependency) => !dependency || typeof dependency.name !== 'string'
        || typeof dependency.version !== 'string' || !Array.isArray(dependency.vulns)
        || Object.hasOwn(dependency, 'skip_reason'))) {
      failures.push('pip-audit report has an unsupported or incomplete schema.');
    } else {
      const vulnerabilityCount = pythonAudit.dependencies.reduce((count, dependency) => count + dependency.vulns.length, 0);
      if (vulnerabilityCount > 0) failures.push(`pip-audit report contains ${vulnerabilityCount} vulnerabilities.`);
    }
  }
  const repositoryConfig = readArtifactJson(
    `repository-config-scan-${testedGitSha}`, contractFiles.repositoryConfig, 'repository configuration report',
  );
  if (repositoryConfig && (!Array.isArray(repositoryConfig.Results) || !Number.isInteger(repositoryConfig.SchemaVersion))) {
    failures.push('Repository configuration report has an unsupported Trivy JSON schema.');
  }
  const secretProof = readArtifactJson(`repository-secret-proof-${testedGitSha}`, contractFiles.secretProof, 'safe secret proof summary');
  if (secretProof && (secretProof.testedGitSha !== testedGitSha
    || secretProof.trivyVersion !== SECURITY_POLICY.tools.trivy
    || secretProof.offlineSecretOnlyScan !== 'success'
    || secretProof.expectedRuleId !== SECURITY_POLICY.secretProof.expectedRuleId
    || secretProof.expectedSeverity !== SECURITY_POLICY.secretProof.expectedSeverity
    || secretProof.findingCount !== SECURITY_POLICY.secretProof.expectedFindingCount
    || secretProof.cleanControl !== 'passed'
    || secretProof.independentOfflineMavenFailure !== 'passed')) {
    failures.push('Safe secret proof summary does not match the required revision and controls.');
  }

  const vulnerabilityArtifact = `repository-vulnerability-evidence-${testedGitSha}`;
  const vulnerabilityCombined = readArtifactJson(vulnerabilityArtifact, contractFiles.vulnerabilityCombined, 'combined Trivy vulnerability report');
  const vulnerabilitySummary = readArtifactJson(vulnerabilityArtifact, contractFiles.vulnerabilitySummary, 'Trivy vulnerability decision summary');
  const repositoryReport = readArtifactJson(vulnerabilityArtifact, contractFiles.vulnerabilityRepositoryReport, 'repository Trivy source report');
  const mavenReport = readArtifactJson(vulnerabilityArtifact, contractFiles.vulnerabilityMavenReport, 'resolved Maven Trivy source report');
  let trivySourcesValid = false;
  if (repositoryReport && mavenReport && vulnerabilityCombined) {
    try {
      validateTrivyVulnerabilityReport(repositoryReport, 'repository');
      validateTrivyVulnerabilityReport(mavenReport, 'resolved Maven artifact');
      validateTrivyVulnerabilityReport(vulnerabilityCombined, 'combined');
      trivySourcesValid = true;
      const expectedResults = [...repositoryReport.Results, ...mavenReport.Results];
      if (JSON.stringify(vulnerabilityCombined.Results) !== JSON.stringify(expectedResults)) {
        failures.push('Combined Trivy vulnerability report does not contain the complete repository and Maven source results.');
      }
    } catch (error) {
      failures.push(`Trivy vulnerability source report failed validation: ${error.message}.`);
    }
  }
  if (vulnerabilitySummary && (vulnerabilitySummary.decision !== 'PASS'
    || vulnerabilitySummary.trivyVersion !== SECURITY_POLICY.tools.trivy
    || vulnerabilitySummary.blockingVulnerabilities !== 0)) {
    failures.push('Repository vulnerability evidence does not record a clean blocking policy decision.');
  }
  if (vulnerabilityCombined && (vulnerabilityCombined.Trivy?.Version !== SECURITY_POLICY.tools.trivy
    || !Array.isArray(vulnerabilityCombined.Results) || vulnerabilityCombined.Results.length === 0)) {
    failures.push('Combined Trivy vulnerability report is incomplete or uses another scanner version.');
  }
  if (vulnerabilityCombined && vulnerabilitySummary) {
    const combinedPath = findFile(vulnerabilityArtifact, contractFiles.vulnerabilityCombined);
    if (combinedPath && vulnerabilitySummary.combinedReportSha256 !== hashFile(combinedPath)) {
      failures.push('Trivy vulnerability summary hash does not match the combined report bytes.');
    }
    if (trivySourcesValid) {
      const expectedSources = [
        ['repository', repositoryReport],
        ['resolved-maven-artifacts', mavenReport],
      ];
      const sources = vulnerabilitySummary.sources;
      if (!Array.isArray(sources) || sources.length !== expectedSources.length) {
        failures.push('Trivy vulnerability summary does not describe both source reports.');
      } else {
        for (const [index, [name, report]] of expectedSources.entries()) {
          const source = sources[index];
          const expectedHash = createHash('sha256').update(JSON.stringify(report)).digest('hex');
          const packageCount = report.Results.reduce((count, result) => count + result.Packages.length, 0);
          const vulnerabilityCount = report.Results.reduce((count, result) => count + (result.Vulnerabilities?.length ?? 0), 0);
          if (source?.name !== name || source.artifactName !== report.ArtifactName
            || source.targetCount !== report.Results.length || source.packageCount !== packageCount
            || source.vulnerabilityCount !== vulnerabilityCount || source.reportSha256 !== expectedHash) {
            failures.push(`Trivy vulnerability summary does not match its ${name} source report.`);
          }
        }
      }
      if (Array.isArray(vulnerabilityCombined.Results)) {
        const counts = Object.fromEntries(SECURITY_POLICY.vulnerabilityPolicy.visibleSeverities.map((severity) => [severity, 0]));
        for (const result of [...repositoryReport.Results, ...mavenReport.Results]) {
          for (const finding of result.Vulnerabilities ?? []) counts[finding.Severity] += 1;
        }
        const summarizedCounts = vulnerabilitySummary.countsBySeverity;
        if (!summarizedCounts || JSON.stringify(summarizedCounts) !== JSON.stringify(counts)
          || vulnerabilitySummary.totalVulnerabilities !== Object.values(counts).reduce((total, count) => total + count, 0)
          || vulnerabilitySummary.blockingVulnerabilities !== SECURITY_POLICY.vulnerabilityPolicy.blockingSeverities
            .reduce((total, severity) => total + counts[severity], 0)) {
          failures.push('Trivy vulnerability summary counts do not match the raw source reports.');
        }
      }
    }
  }

  const sbomArtifact = `resolved-cyclonedx-sbom-${testedGitSha}`;
  for (const fileName of contractFiles.sbomDocuments) {
    if (!findFile(sbomArtifact, fileName)) failures.push(`Required resolved SBOM evidence is missing: ${fileName}.`);
  }

  const expectedImageCounts = {
    [`image-scan-validation-${testedGitSha}`]: {
      count: Object.values(IMAGE_SECURITY_INVENTORY.groups).flat().length,
      requiredJobs: SECURITY_POLICY.platformGate.imageValidationJobs.inventory,
    },
    [`fdp-image-scan-validation-${testedGitSha}`]: {
      count: SECURITY_POLICY.platformGate.historicalImageArtifacts.length,
      requiredJobs: SECURITY_POLICY.platformGate.imageValidationJobs.historical,
    },
  };
  const imageSummaries = [];
  for (const [artifactName, expected] of Object.entries(expectedImageCounts)) {
    const summary = readArtifactJson(artifactName, contractFiles.imageValidationSummary, `${artifactName} validation summary`);
    if (!summary) continue;
    const actualJobs = summary.requiredScanJobs && typeof summary.requiredScanJobs === 'object'
      && !Array.isArray(summary.requiredScanJobs) ? Object.keys(summary.requiredScanJobs).sort() : [];
    const expectedJobs = [...expected.requiredJobs].sort();
    if (summary.schemaVersion !== 2 || summary.outcome !== 'success' || summary.testedGitSha !== testedGitSha
      || summary.expectedArtifactCount !== expected.count || summary.artifactCount !== expected.count
      || JSON.stringify(actualJobs) !== JSON.stringify(expectedJobs)
      || expectedJobs.some((job) => summary.requiredScanJobs?.[job] !== 'success')) {
      failures.push(`${artifactName} does not prove complete successful image evidence for the tested revision.`);
    }
    imageSummaries.push({ artifactName, expectedArtifactCount: expected.count, artifactCount: summary.artifactCount });
  }

  for (const artifactName of imageArtifactNames) {
    const directory = artifactDirectories.get(artifactName);
    if (!directory) continue;
    for (const fileName of [
      EVIDENCE_FILES.evidence,
      EVIDENCE_FILES.dockerInspect,
      EVIDENCE_FILES.dockerInspectAfterScan,
      EVIDENCE_FILES.scanResult,
      EVIDENCE_FILES.trivy,
      EVIDENCE_FILES.rawSbom,
      EVIDENCE_FILES.boundSbom,
    ]) {
      if (!findNamedFile(directory, fileName, failures)) failures.push(`${artifactName} is missing validated image evidence file ${fileName}.`);
    }
  }

  const artifactReferences = [];
  for (const [name, directory] of [...artifactDirectories.entries()].sort(([left], [right]) => left.localeCompare(right))) {
    const files = [];
    walkEvidenceFiles(directory, root, files, failures);
    artifactReferences.push({
      artifactName: name,
      fileCount: files.length,
      totalBytes: files.reduce((total, file) => total + file.sizeBytes, 0),
      files,
    });
  }

  const evidence = {
    schemaVersion: 1,
    finalDecision: failures.length === 0 ? 'PASS' : 'FAIL',
    createdAt,
    repository,
    workflow,
    eventName,
    ref,
    testedGitSha,
    pullRequest: { headSha: prHeadSha, mergeSha: prMergeSha || null },
    run: { id: String(runId ?? ''), attempt: Number(runAttempt) || null },
    policy: {
      sha256: hashFile(POLICY_PATH),
      imageInventorySha256: hashFile(INVENTORY_PATH),
      tools: SECURITY_POLICY.tools,
      platforms: SECURITY_POLICY.platforms,
      vulnerabilityPolicy: SECURITY_POLICY.vulnerabilityPolicy,
      formats: SECURITY_POLICY.formats,
      imageArtifactCount: imageArtifactNames.length,
    },
    producerOutcomes: Object.fromEntries(requiredJobs.map((job) => [job, needs[job]?.result ?? 'missing'])),
    imageEvidence: imageSummaries,
    artifactReferences,
    failures,
  };
  return evidence;
}

function findNamedFile(directory, fileName, failures) {
  const pending = [directory];
  const matches = [];
  while (pending.length > 0) {
    const current = pending.pop();
    let entries;
    try {
      entries = readdirSync(current, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const target = path.join(current, entry.name);
      if (entry.isFile() && entry.name === fileName) matches.push(target);
      if (entry.isDirectory()) pending.push(target);
    }
  }
  if (matches.length > 1) {
    failures.push(`Evidence file ${fileName} appears more than once under ${path.basename(directory)}.`);
    return null;
  }
  return matches[0] ?? null;
}

function walkEvidenceFiles(directory, root, output, failures) {
  let entries;
  try {
    entries = readdirSync(directory, { withFileTypes: true });
  } catch (error) {
    failures.push(`Unable to read downloaded evidence directory: ${error.message}.`);
    return;
  }
  for (const entry of entries) {
    const target = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) {
      failures.push(`Downloaded evidence contains a symbolic link: ${path.relative(root, target)}.`);
    } else if (entry.isDirectory()) {
      walkEvidenceFiles(target, root, output, failures);
    } else if (entry.isFile()) {
      try {
        const stat = lstatSync(target);
        output.push({
          path: path.relative(root, target).replaceAll('\\', '/'),
          sizeBytes: stat.size,
          sha256: hashFile(target),
        });
      } catch (error) {
        failures.push(`Unable to hash downloaded evidence ${path.relative(root, target)}: ${error.message}.`);
      }
    }
  }
}

function hashFile(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

function renderSummary(evidence) {
  const lines = [
    '# Platform Security Gate',
    '',
    `**Decision: ${evidence.finalDecision}**`,
    '',
    `- Tested SHA: \`${evidence.testedGitSha}\``,
    `- Pull request head SHA: \`${evidence.pullRequest.headSha}\``,
    `- Pull request merge SHA: \`${evidence.pullRequest.mergeSha ?? 'not applicable'}\``,
    `- Workflow run: \`${evidence.run.id}\`, attempt \`${evidence.run.attempt ?? 'unknown'}\``,
    `- Verified artifact files: ${evidence.artifactReferences.reduce((total, artifact) => total + artifact.fileCount, 0)}`,
    '',
    '| Producer | Result |',
    '| --- | --- |',
    ...Object.entries(evidence.producerOutcomes).map(([job, result]) => `| ${job} | ${result} |`),
    '',
  ];
  if (evidence.failures.length > 0) {
    lines.push('## Failures', '', ...evidence.failures.map((failure) => `- ${failure}`), '');
  }
  return `${lines.join('\n')}\n`;
}

function main(args) {
  const [artifactDirectory, outputPath] = args;
  if (!artifactDirectory || !outputPath || args.length !== 2) {
    throw new Error('Usage: node write-platform-security-evidence.mjs <artifact-directory> <output.json>');
  }
  const needs = JSON.parse(process.env.PLATFORM_SECURITY_NEEDS_JSON ?? 'null');
  const evidence = buildPlatformSecurityEvidence({
    artifactDirectory,
    needs,
    testedGitSha: process.env.TESTED_GIT_SHA,
    prHeadSha: process.env.PR_HEAD_SHA,
    prMergeSha: process.env.PR_MERGE_SHA ?? '',
    runId: process.env.GITHUB_RUN_ID,
    runAttempt: process.env.GITHUB_RUN_ATTEMPT,
    repository: process.env.GITHUB_REPOSITORY,
    ref: process.env.GITHUB_REF,
    workflow: process.env.GITHUB_WORKFLOW,
    eventName: process.env.GITHUB_EVENT_NAME,
  });
  const resolvedOutputPath = path.resolve(outputPath);
  const markdownPath = resolvedOutputPath.replace(/\.json$/i, '.md');
  mkdirSync(path.dirname(resolvedOutputPath), { recursive: true });
  writeFileSync(resolvedOutputPath, `${JSON.stringify(evidence, null, 2)}\n`, 'utf8');
  const summary = renderSummary(evidence);
  writeFileSync(markdownPath, summary, 'utf8');
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary, 'utf8');
  if (evidence.failures.length > 0) {
    console.error('Platform Security Gate failed:');
    for (const failure of evidence.failures) console.error(`- ${failure}`);
    process.exitCode = 1;
    return;
  }
  console.log(`Platform Security Gate passed for ${evidence.testedGitSha}; ${evidence.artifactReferences.length} evidence artifacts and ${evidence.artifactReferences.reduce((total, artifact) => total + artifact.fileCount, 0)} hashed files.`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(`Platform Security Gate evidence generation failed: ${error.message}`);
    process.exitCode = 1;
  }
}
