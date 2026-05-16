export const ciSuites = {
  fdp42: {
    label: "FDP-42",
    reports: "alert-service/target/surefire-reports",
    junitRequired: [
      "FraudCaseTransitionPolicyTest",
      "Fdp42FraudCaseManagementServiceTest",
      "FraudCaseControllerTest",
      "FraudCaseManagementServiceTest",
      "FraudCaseMutationInvariantTest",
      "FraudCaseAuditServiceTest",
      "Fdp42FraudCaseAuditAppendOnlyArchitectureTest",
      "Fdp42FraudCaseDocumentationNoOverclaimTest",
      "FraudCaseSecurityIntegrationTest",
      "FraudCaseTransactionIntegrationTest",
      "AlertSecurityConfigTest",
      "AlertSecurityConfigJwtEnabledTest",
      "DemoAuthSecurityConfigTest",
      "AnalystRoleTest"
    ]
  },
  fdp43: {
    label: "FDP-43",
    reports: "alert-service/target/surefire-reports",
    junitRequired: [
      "IdempotencyCanonicalHasherTest",
      "SharedIdempotencyKeyPolicyTest",
      "FraudCaseLifecycleIdempotencyConflictPolicyTest",
      "Fdp43FraudCaseLifecycleIdempotencyArchitectureTest",
      "Fdp43FraudCaseLifecyclePublicPathIdempotencyArchitectureTest",
      "FraudCaseControllerTest",
      "FraudCaseSecurityIntegrationTest",
      "FraudCaseTransactionIntegrationTest",
      "FraudCaseLifecycleIdempotencyGlobalKeyRegressionIntegrationTest",
      "FraudCaseLifecycleIdempotencyConcurrencyIntegrationTest",
      "FraudCaseLifecycleIdempotencyFailureIntegrationTest",
      "FraudCaseLifecycleIdempotencyServiceRaceTest",
      "RegulatedMutationIdempotencyPrimitiveCompatibilityTest"
    ]
  },
  fdp44: {
    label: "FDP-44",
    reports: "alert-service/target/surefire-reports",
    junitRequired: [
      "Fdp44FraudCaseLifecycleApiSurfaceStructuralTest",
      "Fdp44FraudCaseLifecycleReplaySnapshotTest",
      "Fdp44FraudCaseLifecycleReplaySnapshotFailClosedTest",
      "Fdp44FraudCaseLifecycleReplaySnapshotCoverageTest",
      "Fdp44FraudCaseLifecycleReplaySnapshotMapperStructuralTest",
      "Fdp44FraudCaseLifecycleReplayEquivalenceIntegrationTest",
      "Fdp44FraudCaseIdempotencyRetentionOperationalTest",
      "Fdp44FraudCaseIdempotencyOperationalDocsNoOverclaimTest",
      "FraudCaseLifecycleIdempotencyServiceRaceTest",
      "FraudCaseLifecycleIdempotencyGlobalKeyRegressionIntegrationTest",
      "FraudCaseLifecycleIdempotencyConcurrencyIntegrationTest",
      "FraudCaseLifecycleIdempotencyFailureIntegrationTest",
      "AlertServiceMetricsTest"
    ]
  },
  fdp45: {
    label: "FDP-45",
    reports: "alert-service/target/surefire-reports",
    junitRequired: [
      "Fdp45FraudCaseReadModelSingleSourceOfTruthTest",
      "Fdp45FraudCaseWorkQueuePaginationTest",
      "Fdp45FraudCaseWorkQueueSortingTest",
      "Fdp45FraudCaseWorkQueueFilterTest",
      "Fdp45FraudCaseWorkQueueDuplicateParamTest",
      "Fdp45FraudCaseWorkQueueSlaConfigTest",
      "Fdp45FraudCaseProdSlaConfigTest",
      "Fdp45FraudCaseWorkQueueSlaAgeTest",
      "Fdp45FraudCaseWorkQueueReadOnlySafetyTest",
      "Fdp45FraudCaseWorkQueueSecurityTest",
      "Fdp45FraudCaseWorkQueueObservabilityTest",
      "Fdp45FraudCaseWorkQueueFailureMetricsTest",
      "Fdp45FraudCaseWorkQueueReadAccessAuditTest",
      "Fdp45FraudCaseWorkQueueMongoIntegrationTest",
      "Fdp45FraudCaseWorkQueueContractCompatibilityTest",
      "Fdp45FraudCaseWorkQueueDocsNoOverclaimTest",
      "Fdp45FraudCaseLegacyListValidationTest",
      "Fdp45FraudCaseLegacyDeepPaginationValidationTest",
      "Fdp45FraudCaseWorkQueueDeepPaginationTest",
      "Fdp45FraudCaseWorkQueueIndexReadinessTest",
      "Fdp45FraudCaseCompoundIndexReadinessDocsTest",
      "Fdp45FraudCaseWorkQueueOpenApiContractTest",
      "Fdp45FraudCaseWorkQueueFailedReadAuditTest",
      "Fdp45FraudCaseWorkQueueFilterLengthTest",
      "Fdp45FraudCaseWorkQueueNoDuplicateAuditAdviceTest",
      "Fdp45ReadAccessAuditResponseAdviceMarkerTest",
      "Fdp45FraudCaseWorkQueueAuditPrecedenceTest",
      "Fdp45SensitiveReadAuditUnavailableRunbookTest",
      "Fdp45FraudCaseReadPolicyNamingBoundaryTest",
      "Fdp45FraudCaseLegacyExactCountCompatibilityDocsTest",
      "Fdp45FraudCaseLegacyPaginationReleaseNotesTest",
      "Fdp45FraudCaseReadQueryPolicyContractTest",
      "Fdp45FraudCaseWorkQueueCursorArchitectureTest",
      "Fdp45FraudCaseWorkQueueCursorCodecTest",
      "Fdp45FraudCaseWorkQueueCursorPaginationTest",
      "Fdp45FraudCaseWorkQueueCursorQueryFingerprintTest",
      "Fdp45FraudCaseWorkQueueCursorFilterBindingTest",
      "Fdp45FraudCaseWorkQueueCursorPageCombinationTest",
      "Fdp45FraudCaseWorkQueueCursorSecretProfileTest",
      "Fdp45FraudCaseWorkQueueCursorObservabilityTest",
      "Fdp45FraudCaseWorkQueueCursorDocsContractTest",
      "Fdp45FraudCaseWorkQueueSortFieldCoverageTest",
      "Fdp45FraudCaseWorkQueueCursorSizeChangeTest",
      "SensitiveReadAuditServiceTest"
    ]
  },
  fdp46: {
    label: "FDP-46",
    reports: "alert-service/target/surefire-reports",
    junitRequired: [
      "ScoredTransactionControllerValidationTest",
      "ScoredTransactionSearchPolicyTest",
      "TransactionMonitoringServiceSearchTest",
      "ScoredTransactionDocumentMapperTest",
      "ScoredTransactionSearchIndexReadinessTest",
      "ReadAccessAuditClassifierTest",
      "ReadAccessAuditEndpointTest",
      "ReadAccessAuditServiceTest",
      "ReadAccessAuditResponseAdviceTest",
      "Fdp45ReadAccessAuditResponseAdviceMarkerTest",
      "AlertServiceMetricsTest",
      "AlertSecurityConfigTest"
    ]
  },
  fdp47: {
    label: "FDP-47",
    reports: "alert-service/target/surefire-reports",
    junitRequired: [
      "FraudCaseWorkQueueSummaryControllerTest",
      "ReadAccessAuditClassifierTest",
      "ReadAccessResultCountExtractorTest",
      "Fdp45FraudCaseReadModelSingleSourceOfTruthTest",
      "Fdp45FraudCaseWorkQueueReadOnlySafetyTest",
      "Fdp45FraudCaseWorkQueueOpenApiContractTest",
      "Fdp47FraudCaseWorkQueueSummaryContractTest",
      "Fdp45FraudCaseWorkQueueObservabilityTest",
      "FraudCaseSecurityIntegrationTest"
    ],
    activationGroups: [
      {
        label: "FDP-46 backend tests",
        pattern: "java-no-disabled",
        files: [
          "alert-service/src/test/java/com/frauddetection/alert/controller/ScoredTransactionControllerValidationTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/service/ScoredTransactionSearchPolicyTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/service/TransactionMonitoringServiceSearchTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/mapper/ScoredTransactionDocumentMapperTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/service/ScoredTransactionSearchIndexReadinessTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/audit/read/ReadAccessAuditEndpointTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/observability/AlertServiceMetricsTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/AlertSecurityConfigTest.java"
        ]
      },
      {
        label: "FDP-47 backend tests",
        pattern: "java-no-disabled",
        files: [
          "alert-service/src/test/java/com/frauddetection/alert/controller/FraudCaseWorkQueueSummaryControllerTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/audit/read/ReadAccessResultCountExtractorTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/fraudcase/Fdp47FraudCaseWorkQueueSummaryContractTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/observability/Fdp45FraudCaseWorkQueueObservabilityTest.java"
        ]
      },
      {
        label: "FDP-46/FDP-47 frontend tests",
        pattern: "vitest-no-skip",
        files: [
          "analyst-console-ui/src/App.test.jsx",
          "analyst-console-ui/src/pages/AlertsListPage.test.jsx",
          "analyst-console-ui/src/components/FraudCaseWorkQueuePanel.test.jsx",
          "analyst-console-ui/src/fraudCases/workQueueState.test.js",
          "analyst-console-ui/src/api/alertsApi.test.js"
        ]
      }
    ]
  },
  fdp48: {
    label: "FDP-48",
    reports: "alert-service/target/surefire-reports",
    junitRequired: [
      "com.frauddetection.alert.security.config.BffSessionSecurityIntegrationTest",
      "com.frauddetection.alert.security.auth.BffSecurityPropertiesTest",
      "com.frauddetection.alert.security.auth.BffLogoutSuccessHandlerTest",
      "com.frauddetection.alert.security.auth.OidcAnalystAuthoritiesMapperTest",
      "com.frauddetection.alert.security.config.AlertSecurityConfigJwtEnabledTest",
      "com.frauddetection.alert.observability.AlertServiceMetricsTest"
    ],
    activationGroups: [
      {
        label: "FDP-48 backend tests",
        pattern: "java-no-disabled",
        files: [
          "alert-service/src/test/java/com/frauddetection/alert/security/config/BffSessionSecurityIntegrationTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/auth/BffSecurityPropertiesTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/auth/BffLogoutSuccessHandlerTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/auth/OidcAnalystAuthoritiesMapperTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/AlertSecurityConfigJwtEnabledTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/observability/AlertServiceMetricsTest.java"
        ]
      },
      {
        label: "FDP-48 frontend tests",
        pattern: "vitest-no-skip-or-only",
        files: [
          "analyst-console-ui/src/auth/authProvider.test.js",
          "analyst-console-ui/src/api/alertsApi.test.js",
          "analyst-console-ui/src/fraudCases/useFraudCaseWorkQueue.test.js",
          "analyst-console-ui/src/fraudCases/useFraudCaseWorkQueueSummary.test.js",
          "analyst-console-ui/src/workspace/workspaceDataHooks.test.js",
          "analyst-console-ui/src/workspace/useWorkspaceRoute.test.js"
        ]
      }
    ],
    vitestReports: [
      {
        path: "analyst-console-ui/test-results/fdp48-vitest.xml",
        required: [
          "authProvider.test.js",
          "alertsApi.test.js",
          "workspaceDataHooks.test.js",
          "useWorkspaceRoute.test.js"
        ],
        forbidSkipped: true
      }
    ]
  },
  fdp49: {
    label: "FDP-49",
    reports: "alert-service/target/surefire-reports",
    junitRequired: [
      "com.frauddetection.alert.security.config.AuthorizationRulesCoverageTest",
      "com.frauddetection.alert.security.config.AuthorizationRuleGroupRegistrationTest",
      "com.frauddetection.alert.security.config.RouteCoverageAgainstMvcMappingsTest",
      "com.frauddetection.alert.security.config.SecurityMatcherOrderRegressionTest",
      "com.frauddetection.alert.security.config.DenyByDefaultSecurityTest",
      "com.frauddetection.alert.security.config.SpaFallbackSecurityTest",
      "com.frauddetection.alert.security.config.BffCsrfBoundaryRegressionTest",
      "com.frauddetection.alert.security.config.BearerVsSessionCsrfRegressionTest",
      "com.frauddetection.alert.security.config.BffSessionSecurityIntegrationTest",
      "com.frauddetection.alert.security.config.SecurityRouteRegistryDocumentationTest",
      "com.frauddetection.alert.security.config.SecurityRouteOwnershipRegistryTest",
      "com.frauddetection.alert.security.config.SingleSecurityFilterChainGuardTest",
      "com.frauddetection.alert.security.config.Fdp49NoNewBusinessSemanticsTest",
      "com.frauddetection.alert.observability.AlertServiceMetricsTest"
    ],
    activationGroups: [
      {
        label: "FDP-49 backend tests",
        pattern: "java-no-disabled",
        files: [
          "alert-service/src/test/java/com/frauddetection/alert/security/config/AuthorizationRulesCoverageTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/AuthorizationRuleGroupRegistrationTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/RouteCoverageAgainstMvcMappingsTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/SecurityMatcherOrderRegressionTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/DenyByDefaultSecurityTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/SpaFallbackSecurityTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/BffCsrfBoundaryRegressionTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/BearerVsSessionCsrfRegressionTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/BffSessionSecurityIntegrationTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/SecurityRouteRegistryDocumentationTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/SecurityRouteOwnershipRegistryTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/SingleSecurityFilterChainGuardTest.java",
          "alert-service/src/test/java/com/frauddetection/alert/security/config/Fdp49NoNewBusinessSemanticsTest.java"
        ]
      },
      {
        label: "FDP-49 frontend tests",
        pattern: "vitest-no-skip-or-only",
        files: [
          "analyst-console-ui/src/api/apiClientBoundary.test.js",
          "analyst-console-ui/src/auth/authProvider.test.js",
          "analyst-console-ui/src/api/alertsApi.test.js",
          "analyst-console-ui/src/workspace/workspaceDataHooks.test.js",
          "analyst-console-ui/src/fraudCases/useFraudCaseWorkQueue.test.js",
          "analyst-console-ui/src/fraudCases/useFraudCaseWorkQueueSummary.test.js"
        ]
      }
    ],
    vitestReports: [
      {
        path: "analyst-console-ui/test-results/fdp49-vitest.xml",
        required: [
          "apiClientBoundary.test.js",
          "authProvider.test.js",
          "alertsApi.test.js",
          "workspaceDataHooks.test.js",
          "useFraudCaseWorkQueue.test.js",
          "useFraudCaseWorkQueueSummary.test.js"
        ],
        forbidSkipped: true
      }
    ],
    markerFiles: [
      {
        path: "analyst-console-ui/test-results/fdp49-api-client-boundary-guard.txt",
        contains: "fdp49_api_client_boundary_guard_ran=true"
      }
    ]
  },
  "frontend-architecture": {
    label: "Frontend architecture",
    activationGroups: [
      {
        label: "Frontend architecture tests",
        pattern: "vitest-no-skip-or-only",
        files: [
          "analyst-console-ui/src/api/apiClientBoundary.test.js",
          "analyst-console-ui/src/auth/authProvider.test.js",
          "analyst-console-ui/src/api/alertsApi.test.js",
          "analyst-console-ui/src/workspace/workspaceDataHooks.test.js",
          "analyst-console-ui/src/workspace/useWorkspaceRoute.test.js",
          "analyst-console-ui/src/fraudCases/useFraudCaseWorkQueue.test.js",
          "analyst-console-ui/src/fraudCases/useFraudCaseWorkQueueSummary.test.js",
          "analyst-console-ui/src/App.test.jsx",
          "analyst-console-ui/src/workspace/WorkspaceRuntimeProvider.test.jsx",
          "analyst-console-ui/src/workspace/useWorkspaceCounters.test.js",
          "analyst-console-ui/src/workspace/useGovernanceAuditWorkflow.test.js",
          "analyst-console-ui/src/workspace/workspaceRefreshContract.test.js",
          "analyst-console-ui/src/components/GovernanceReviewQueue.test.jsx",
          "analyst-console-ui/src/pages/AlertDetailsPage.test.jsx",
          "analyst-console-ui/src/pages/FraudCaseDetailsPage.test.jsx",
          "analyst-console-ui/src/pages/AlertsListPage.test.jsx",
          "analyst-console-ui/src/workspace/WorkspaceDashboardShell.test.jsx",
          "analyst-console-ui/src/workspace/WorkspaceDetailRouter.test.jsx",
          "analyst-console-ui/src/workspace/AnalystWorkspaceContainer.test.jsx",
          "analyst-console-ui/src/workspace/FraudTransactionWorkspaceContainer.test.jsx",
          "analyst-console-ui/src/workspace/TransactionScoringWorkspaceContainer.test.jsx",
          "analyst-console-ui/src/workspace/GovernanceWorkspaceContainer.test.jsx",
          "analyst-console-ui/src/workspace/ReportsWorkspaceContainer.test.jsx",
          "analyst-console-ui/src/workspace/WorkspaceContainerBoundary.test.jsx",
          "analyst-console-ui/src/components/DetailHeader.test.jsx",
          "analyst-console-ui/src/components/DetailStateBanner.test.jsx",
          "analyst-console-ui/src/utils/idempotencyKey.test.js",
          "analyst-console-ui/src/workspace/WorkspaceRouteRegistry.test.jsx",
          "analyst-console-ui/src/workspace/WorkspaceRuntimes.test.jsx",
          "analyst-console-ui/src/workspace/useWorkspaceRefreshNotice.test.js",
          "analyst-console-ui/src/workspace/workspaceRuntimeResult.test.js",
          "analyst-console-ui/src/workspace/ReportsWorkspaceContainer.test.jsx"
        ]
      }
    ],
    vitestReports: [
      {
        path: "analyst-console-ui/test-results/fdp50-vitest.xml",
        required: [
          "apiClientBoundary.test.js",
          "authProvider.test.js",
          "alertsApi.test.js",
          "workspaceDataHooks.test.js",
          "useWorkspaceRoute.test.js",
          "useFraudCaseWorkQueue.test.js",
          "useFraudCaseWorkQueueSummary.test.js",
          "App.test.jsx"
        ],
        forbidSkipped: true
      },
      {
        path: "analyst-console-ui/test-results/fdp51-vitest.xml",
        required: [
          "WorkspaceRuntimeProvider.test.jsx",
          "useWorkspaceCounters.test.js",
          "useGovernanceAuditWorkflow.test.js",
          "workspaceRefreshContract.test.js",
          "workspaceDataHooks.test.js",
          "GovernanceReviewQueue.test.jsx",
          "AlertDetailsPage.test.jsx",
          "FraudCaseDetailsPage.test.jsx",
          "AlertsListPage.test.jsx",
          "App.test.jsx",
          "alertsApi.test.js",
          "apiClientBoundary.test.js"
        ],
        forbidSkipped: true
      },
      {
        path: "analyst-console-ui/test-results/fdp52-vitest.xml",
        required: [
          "WorkspaceDashboardShell.test.jsx",
          "WorkspaceDetailRouter.test.jsx",
          "AnalystWorkspaceContainer.test.jsx",
          "FraudTransactionWorkspaceContainer.test.jsx",
          "TransactionScoringWorkspaceContainer.test.jsx",
          "GovernanceWorkspaceContainer.test.jsx",
          "ReportsWorkspaceContainer.test.jsx",
          "WorkspaceContainerBoundary.test.jsx",
          "DetailHeader.test.jsx",
          "DetailStateBanner.test.jsx",
          "idempotencyKey.test.js",
          "AlertDetailsPage.test.jsx",
          "FraudCaseDetailsPage.test.jsx"
        ],
        forbidSkipped: true
      },
      {
        path: "analyst-console-ui/test-results/fdp53-vitest.xml",
        required: [
          "WorkspaceDashboardShell.test.jsx",
          "WorkspaceRouteRegistry.test.jsx",
          "WorkspaceRuntimes.test.jsx",
          "workspaceRefreshContract.test.js",
          "useWorkspaceRefreshNotice.test.js",
          "workspaceRuntimeResult.test.js",
          "useWorkspaceRoute.test.js",
          "AlertsListPage.test.jsx",
          "ReportsWorkspaceContainer.test.jsx",
          "WorkspaceDetailRouter.test.jsx",
          "WorkspaceContainerBoundary.test.jsx",
          "AlertDetailsPage.test.jsx",
          "FraudCaseDetailsPage.test.jsx",
          "App.test.jsx"
        ],
        forbidSkipped: true
      }
    ],
    markerFiles: [
      {
        path: "analyst-console-ui/test-results/frontend-architecture-guards.txt",
        contains: "fdp50_api_client_boundary_guard_ran=true"
      },
      {
        path: "analyst-console-ui/test-results/frontend-architecture-guards.txt",
        contains: "fdp50_scope_guard_ran=true"
      },
      {
        path: "analyst-console-ui/test-results/frontend-architecture-guards.txt",
        contains: "fdp51_scope_guard_ran=true"
      },
      {
        path: "analyst-console-ui/test-results/frontend-architecture-guards.txt",
        contains: "fdp52_scope_guard_ran=true"
      },
      {
        path: "analyst-console-ui/test-results/frontend-architecture-guards.txt",
        contains: "fdp53_scope_guard_ran=true"
      }
    ]
  }
};

export function getCiSuite(name) {
  const suite = ciSuites[name];
  if (!suite) {
    throw new Error(`Unknown CI suite '${name}'. Known suites: ${Object.keys(ciSuites).sort().join(", ")}`);
  }
  return suite;
}
