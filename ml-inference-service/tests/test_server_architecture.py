import ast
import unittest
from pathlib import Path
from unittest.mock import patch

from app import server
from app.http.request_support import HttpRequestSupport
from app.security import internal_auth


SERVICE_ROOT = Path(__file__).resolve().parents[1]


class ServerArchitectureTest(unittest.TestCase):
    SECURITY_FORBIDDEN_IMPORTS = frozenset({"app.server", "app.model"})
    REQUEST_SUPPORT_FORBIDDEN_IMPORTS = frozenset({"app.model"})

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

    def test_security_boundary_does_not_import_server_or_model_bootstrap(self):
        imports = self._imports(self._tree("app/security/internal_auth.py"))
        self.assertFalse(self._contains_forbidden_import(imports, self.SECURITY_FORBIDDEN_IMPORTS))

    def test_request_support_does_not_import_inference_runtime(self):
        imports = self._imports(self._tree("app/http/request_support.py"))
        self.assertFalse(self._contains_forbidden_import(imports, self.REQUEST_SUPPORT_FORBIDDEN_IMPORTS))

    def test_python_dependency_rules_reject_deliberate_violations(self):
        security_violation = ast.parse("import app.server\nfrom app.model import FraudModel\n")
        request_support_violation = ast.parse("from app.model.runtime import resolve_model\n")

        self.assertTrue(self._contains_forbidden_import(
            self._imports(security_violation),
            self.SECURITY_FORBIDDEN_IMPORTS,
        ))
        self.assertTrue(self._contains_forbidden_import(
            self._imports(request_support_violation),
            self.REQUEST_SUPPORT_FORBIDDEN_IMPORTS,
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
        self.assertIs(server.SOFT_REPLAY_CACHE, internal_auth.SOFT_REPLAY_CACHE)
        self.assertIs(result, principal)
        validator.assert_called_once_with("token", "fraud:score")
        self.assertTrue(issubclass(server.FraudInferenceHandler, HttpRequestSupport))

    def test_server_authorization_delegates_to_canonical_security_boundary(self):
        server_tree = self._tree("app/server.py")
        handler = next(
            node
            for node in server_tree.body
            if isinstance(node, ast.ClassDef) and node.name == "FraudInferenceHandler"
        )
        principal_method = next(
            node
            for node in handler.body
            if isinstance(node, ast.FunctionDef) and node.name == "_internal_service_principal"
        )

        self.assertEqual(len(principal_method.body), 1)
        self.assertIsInstance(principal_method.body[0], ast.Return)
        delegated_calls = [
            node
            for node in ast.walk(principal_method)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and isinstance(node.func.value, ast.Name)
            and node.func.value.id == "internal_auth"
            and node.func.attr == "authenticate_internal_service"
        ]
        self.assertEqual(len(delegated_calls), 1)

    def test_startup_and_mtls_compatibility_wrappers_delegate(self):
        expected = (None, 401, "missing_client_certificate")
        with patch.object(
                internal_auth,
                "mtls_service_principal_from_certificate",
                return_value=expected,
        ) as mtls_validator:
            result = server._mtls_service_principal_from_certificate(None, "ml-score")

        self.assertIs(result, expected)
        mtls_validator.assert_called_once_with(None, "ml-score")

        with patch.object(
                internal_auth,
                "validate_internal_auth_startup",
                return_value="TOKEN_VALIDATOR",
        ) as startup_validator:
            server._validate_internal_auth_startup(
                mode="TOKEN_VALIDATOR",
                profile="localdev",
                credentials={},
            )

        startup_validator.assert_called_once_with("TOKEN_VALIDATOR", "localdev", {})

    def _tree(self, relative_path: str) -> ast.AST:
        return ast.parse((SERVICE_ROOT / relative_path).read_text(encoding="utf-8"))

    def _imports(self, tree: ast.AST) -> frozenset[str]:
        imported_modules = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported_modules.update(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imported_modules.add(node.module)
                imported_modules.update(f"{node.module}.{alias.name}" for alias in node.names)
        return frozenset(imported_modules)

    def _contains_forbidden_import(
            self,
            imports: frozenset[str],
            forbidden_roots: frozenset[str],
    ) -> bool:
        return any(
            imported == root or imported.startswith(f"{root}.")
            for imported in imports
            for root in forbidden_roots
        )


if __name__ == "__main__":
    unittest.main()
