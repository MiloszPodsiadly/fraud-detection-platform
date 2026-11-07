import {
  containsEndpointString,
  containsAnalystWorkflowClaim,
  fileMatchesAnyPrefix,
  getChangedFiles,
  printViolationsAndExit,
  isOwnedApiClientContractTest,
  readFileIfExists,
  runGitDiff
} from "./scope-guards/scopeGuardHelpers.mjs";

const usingExplicitChangedFiles = Boolean(process.env.WORKSPACE_DETAIL_SCOPE_CHANGED_FILES);
const changedFiles = getChangedFiles({ baseEnvVar: "WORKSPACE_DETAIL_SCOPE_BASE" });
const violations = [];
const enforceBackendScope = usingExplicitChangedFiles;

const backendProductionPrefixes = [
  "alert-service/src/main/java/",
  "alert-service/src/main/resources/"
];
// Reviewed corrections for transaction feedback, scored search, and response contracts.
// Keep this exact-file exception narrow; other backend production paths remain blocked.
const allowedBackendFiles = new Set([
  "alert-service/src/main/java/com/frauddetection/alert/security/auth/BffLogoutSuccessHandler.java",
  "alert-service/src/main/java/com/frauddetection/alert/api/ScoredMerchantInfoResponse.java",
  "alert-service/src/main/java/com/frauddetection/alert/api/ScoredTransactionDetailResponse.java",
  "alert-service/src/main/java/com/frauddetection/alert/api/ScoredTransactionPageResponse.java",
  "alert-service/src/main/java/com/frauddetection/alert/api/ScoredTransactionResponse.java",
  "alert-service/src/main/java/com/frauddetection/alert/controller/AlertController.java",
  "alert-service/src/main/java/com/frauddetection/alert/controller/ScoredTransactionController.java",
  "alert-service/src/main/java/com/frauddetection/alert/domain/ScoringOccurrenceToken.java",
  "alert-service/src/main/java/com/frauddetection/alert/feedback/CreateFraudFeedbackRequest.java",
  "alert-service/src/main/java/com/frauddetection/alert/feedback/FraudFeedbackMapper.java",
  "alert-service/src/main/java/com/frauddetection/alert/feedback/FraudFeedbackOccurrenceRelation.java",
  "alert-service/src/main/java/com/frauddetection/alert/feedback/FraudFeedbackResponse.java",
  "alert-service/src/main/java/com/frauddetection/alert/feedback/FraudFeedbackService.java",
  "alert-service/src/main/java/com/frauddetection/alert/exception/AlertServiceExceptionHandler.java",
  "alert-service/src/main/java/com/frauddetection/alert/feedback/validation/FraudFeedbackRequestValidator.java",
  "alert-service/src/main/java/com/frauddetection/alert/feedback/validation/ValidatedFraudFeedback.java",
  "alert-service/src/main/java/com/frauddetection/alert/mapper/ScoredTransactionResponseMapper.java",
  "alert-service/src/main/java/com/frauddetection/alert/persistence/AlertDocument.java",
  "alert-service/src/main/java/com/frauddetection/alert/persistence/ScoredTransactionDocument.java",
  "alert-service/src/main/java/com/frauddetection/alert/persistence/ScoredTransactionProjectionWriter.java",
  "alert-service/src/main/java/com/frauddetection/alert/persistence/FeedbackOccurrenceFenceException.java",
  "alert-service/src/main/java/com/frauddetection/alert/regulated/RegulatedMutationTransactionRunner.java",
  "alert-service/src/main/java/com/frauddetection/alert/service/ScoredTransactionSearchResult.java",
  "alert-service/src/main/java/com/frauddetection/alert/service/TransactionMonitoringService.java",
  "alert-service/src/main/java/com/frauddetection/alert/service/TransactionMonitoringUseCase.java"
]);
const allowedEndpointFiles = new Set([
  "analyst-console-ui/src/api/alertsApi.js"
]);
const outOfScopePattern = /\b(assignment|bulk|mass|optimistic|Kafka|outbox|finality)\b/i;
const exportWorkflowPattern = /\bexport\s+(workflow|button|action|csv|download|file|report|data|results)\b/i;
const governanceFiles = new Set([
  "scripts/check-doc-overclaims.mjs",
  "scripts/check-scope-guard-helpers-smoke.mjs",
  "scripts/compare-ci-jobs.mjs",
  "scripts/scope-guards/scopeGuardHelpers.mjs"
]);

for (const file of changedFiles) {
  const normalized = file.replaceAll("\\", "/");
  if (enforceBackendScope
      && fileMatchesAnyPrefix(normalized, backendProductionPrefixes)
      && !allowedBackendFiles.has(normalized)) {
    violations.push(`${normalized}: workspace detail UX is frontend-only; backend production code/resources must not change.`);
  }
  if (!isCheckedTextFile(normalized)) {
    continue;
  }
  const diff = usingExplicitChangedFiles ? fileContentAsAddedLines(normalized) : runGitDiff(diffBase(), "HEAD", ["--unified=0", "--", normalized]);
  for (const line of diff.split(/\r?\n/).filter((entry) => entry.startsWith("+") && !entry.startsWith("+++"))) {
    const sourceLine = line.replace(/^\+\s*/, "");
    if (!allowedEndpointFiles.has(normalized)
        && !isOwnedApiClientContractTest(normalized)
        && !isWorkspaceDetailScopeGuardTest(normalized)
        && !isGovernanceFile(normalized)
        && containsEndpointString(sourceLine)) {
      violations.push(`${normalized}: new endpoint strings must stay behind the API client boundary.`);
    }
    if (!isScopeGuardScript(normalized)
        && !isWorkspaceDetailScopeGuardTest(normalized)
        && !isAllowedOutOfScopeFeatureTest(normalized)
        && !normalized.startsWith("docs/")
        && !isGovernanceFile(normalized)
        && introducesForbiddenScope(sourceLine)) {
      violations.push(`${normalized}: workspace detail UX must not introduce assignment, claim, export, bulk, optimistic, Kafka/outbox/finality, or idempotency semantics.`);
    }
  }
}

printViolationsAndExit(violations, "Workspace detail scope guard failed:");

function introducesForbiddenScope(sourceLine) {
  if (/^export\s+(async\s+)?(function|const|let|var|class)\b/.test(sourceLine)) {
    return false;
  }
  if (/^export\s+(default|\{|\*)\b/.test(sourceLine)) {
    return false;
  }
  return outOfScopePattern.test(sourceLine)
    || containsAnalystWorkflowClaim(sourceLine)
    || exportWorkflowPattern.test(sourceLine)
    || /\b(export|download)\s+(csv|file|report|data|results)\b/i.test(sourceLine)
    || /\b(onExport|handleExport|export[A-Z][A-Za-z]*)\b/.test(sourceLine);
}

function isScopeGuardScript(file) {
  return file.startsWith("scripts/check-") && file.endsWith("-scope.mjs");
}

function isAllowedOutOfScopeFeatureTest(file) {
  // These tests cover separate feature areas with their own claim/ownership semantics.
  return file === "analyst-console-ui/src/workspace/ShadowPerformanceScopeGuard.test.jsx"
    || file === "analyst-console-ui/src/pages/PolicySecurityPage.test.jsx";
}

function isWorkspaceDetailScopeGuardTest(file) {
  return file === "analyst-console-ui/src/components/EngineIntelligencePanelScopeGuard.test.js";
}

function isGovernanceFile(file) {
  return file.startsWith(".github/workflows/")
    || file.startsWith("scripts/ci/")
    || governanceFiles.has(file);
}

function isCheckedTextFile(file) {
  return file.startsWith("analyst-console-ui/src/")
    || file.startsWith("analyst-console-ui/package.json")
    || file.startsWith("scripts/")
    || file.startsWith(".github/workflows/")
    || file.startsWith("docs/");
}

function diffBase() {
  return process.env.WORKSPACE_DETAIL_SCOPE_BASE || `origin/${process.env.GITHUB_BASE_REF || "master"}`;
}

function fileContentAsAddedLines(file) {
  return readFileIfExists(file).split(/\r?\n/).map((line) => `+${line}`).join("\n");
}
