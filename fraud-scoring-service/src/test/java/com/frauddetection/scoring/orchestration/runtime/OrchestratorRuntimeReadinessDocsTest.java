package com.frauddetection.scoring.orchestration.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorRuntimeReadinessDocsTest {

    @Test
    void documentsInternalRuntimeReadinessBoundary() throws Exception {
        String docs = Files.readString(docsRoot().resolve("architecture/orchestrator_runtime_readiness.md"))
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");

        assertThat(docs)
                .contains(
                        "fdp-90",
                        "runtime-hardening foundation only",
                        "per-engine deadline policy",
                        "bounded executor policy",
                        "cancellation is cooperative",
                        "timeout result means orchestrator stopped waiting",
                        "does not guarantee the underlying work was forcibly terminated",
                        "timeout is not low risk",
                        "timeout is not available",
                        "required timeout produces `required_engine_failed`",
                        "optional timeout produces `partial`",
                        "latency measurement",
                        "metrics abstraction",
                        "noopfraudscoringorchestratormetrics",
                        "low-cardinality labels",
                        "rules.primary",
                        "ml.python.primary",
                        "`transactionid` forbidden",
                        "`customerid` forbidden",
                        "`accountid` forbidden",
                        "raw exception forbidden",
                        "payload forbidden",
                        "endpoint forbidden",
                        "token forbidden",
                        "no `transactionscoredevent.engineresults[]`",
                        "no kafka schema changes",
                        "no alert-service projection",
                        "no api/ui",
                        "no final decisioning",
                        "no `compositefraudscoringengine` wiring"
                );
    }

    @Test
    void doesNotClaimExternalExposureOrLiveMigration() throws Exception {
        String docs = Files.readString(docsRoot().resolve("architecture/orchestrator_runtime_readiness.md"))
                .toLowerCase(Locale.ROOT);

        assertThat(docs)
                .doesNotContain(
                        "production migration is complete",
                        "public engine intelligence exposure is enabled",
                        "final decision source changed",
                        "orchestrator is the live scoring path"
                );
    }

    @Test
    void runtimeReadinessDocsDoNotClaimProductionLiveReadiness() throws Exception {
        String docs = Files.readString(docsRoot().resolve("architecture/orchestrator_runtime_readiness.md"))
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");

        assertThat(docs)
                .doesNotContain(
                        "production-ready orchestrator",
                        "ready for live scoring",
                        "live scoring path enabled",
                        "production migration complete",
                        "safe for production traffic",
                        "runtime wiring complete",
                        "engine intelligence exposed",
                        "public engine results",
                        "final decision source"
                )
                .contains(
                        "internal runtime-hardening foundation only",
                        "not a production enablement claim",
                        "no `compositefraudscoringengine` wiring",
                        "fdp-94 later adds disabled-by-default public diagnostic producer enrichment",
                        "runtime wiring injects lifecycle-managed executor",
                        "cancellation is cooperative",
                        "metrics are best-effort",
                        "no vendor-specific metrics integration"
                );
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
