import unittest

from app import server
from app.observability import metrics


class ObservabilityMetricsOwnershipTest(unittest.TestCase):
    def test_server_compatibility_exports_share_the_owned_metric_instances(self):
        metric_names = (
            "REQUEST_COUNTER",
            "REQUEST_LATENCY",
            "ERROR_COUNTER",
            "INTERNAL_AUTH_SUCCESSES",
            "INTERNAL_AUTH_FAILURES",
            "INTERNAL_MTLS_CERTIFICATE_EXPIRY",
            "INTERNAL_MTLS_CERT_EXPIRY",
            "INTERNAL_MTLS_CERT_AGE",
            "INTERNAL_MTLS_CERT_EXPIRY_STATE",
            "INTERNAL_MTLS_HANDSHAKE_FAILURES",
            "INTERNAL_AUTH_REPLAY_REJECTIONS",
            "INTERNAL_AUTH_TOKEN_AGE",
            "MODEL_LOAD_STATUS",
            "MODEL_INFO",
            "GOVERNANCE_DRIFT_STATUS",
            "GOVERNANCE_FEATURE_DRIFT_DETECTED",
            "GOVERNANCE_SCORE_DRIFT_DETECTED",
            "GOVERNANCE_PROFILE_OBSERVATIONS",
            "GOVERNANCE_REFERENCE_PROFILE_LOADED",
            "GOVERNANCE_DRIFT_CONFIDENCE",
            "GOVERNANCE_SNAPSHOTS_PERSISTED",
            "GOVERNANCE_SNAPSHOT_PERSISTENCE_FAILURES",
            "GOVERNANCE_SNAPSHOT_HISTORY_AVAILABLE",
            "GOVERNANCE_DRIFT_ACTION_RECOMMENDATION",
            "MODEL_LIFECYCLE_INFO",
            "MODEL_LIFECYCLE_EVENTS",
            "MODEL_LIFECYCLE_HISTORY_AVAILABLE",
            "GOVERNANCE_ADVISORY_EVENTS_EMITTED",
            "GOVERNANCE_ADVISORY_EVENTS_PERSISTED",
            "GOVERNANCE_ADVISORY_PERSISTENCE_FAILURES",
        )

        for name in metric_names:
            with self.subTest(metric=name):
                self.assertIs(getattr(server, name), getattr(metrics, name))


if __name__ == "__main__":
    unittest.main()
