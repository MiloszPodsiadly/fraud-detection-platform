import ast
import unittest
from pathlib import Path
from unittest.mock import patch

from app import server
from app.http.request_support import HttpRequestSupport
from app.security import internal_auth


SERVICE_ROOT = Path(__file__).resolve().parents[1]


class ServerArchitectureTest(unittest.TestCase):
    def test_extracted_boundaries_do_not_create_runtime_or_metrics_singletons(self):
        server_tree = self._tree("app/server.py")
        extracted_trees = [
            self._tree("app/security/internal_auth.py"),
            self._tree("app/http/request_support.py"),
        ]

        runtime_assignments = [
            node
            for node in ast.walk(server_tree)
            if isinstance(node, ast.Assign)
            and any(isinstance(target, ast.Name) and target.id == "RESOLVED_MODEL_RUNTIME" for target in node.targets)
        ]
        self.assertEqual(len(runtime_assignments), 1)
        for tree in extracted_trees:
            referenced_names = {node.id for node in ast.walk(tree) if isinstance(node, ast.Name)}
            self.assertNotIn("resolve_configured_model_runtime", referenced_names)
            self.assertNotIn("CollectorRegistry", referenced_names)
            self.assertFalse(any(
                isinstance(node, ast.ImportFrom) and node.module == "app" and any(alias.name == "server" for alias in node.names)
                for node in ast.walk(tree)
            ))

    def test_server_keeps_compatibility_symbols_as_delegating_boundary(self):
        principal = internal_auth.InternalServicePrincipal(
            "fraud-scoring-service",
            frozenset({"fraud:score"}),
            internal_auth.datetime.now(internal_auth.timezone.utc),
            "TOKEN_VALIDATOR",
        )
        with patch.object(internal_auth, "validate_jwt_service_token", return_value=principal) as validator:
            result = server._validate_jwt_service_token("token", "fraud:score")

        self.assertIs(server.InternalServicePrincipal, internal_auth.InternalServicePrincipal)
        self.assertIs(server.SoftReplayCache, internal_auth.SoftReplayCache)
        self.assertIs(result, principal)
        validator.assert_called_once_with("token", "fraud:score")
        self.assertTrue(issubclass(server.FraudInferenceHandler, HttpRequestSupport))

    def _tree(self, relative_path: str) -> ast.AST:
        return ast.parse((SERVICE_ROOT / relative_path).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
