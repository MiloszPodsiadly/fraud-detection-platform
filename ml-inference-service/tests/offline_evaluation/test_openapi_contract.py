from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[3]
OPENAPI = ROOT / "docs" / "openapi" / "alert_service.openapi.yaml"


class OpenApiContractTest(unittest.TestCase):
    def test_allLocalRefsResolve(self):
        document = yaml.safe_load(OPENAPI.read_text(encoding="utf-8"))
        refs = []

        def walk(value, path):
            if isinstance(value, dict):
                for key, nested in value.items():
                    next_path = path + [str(key)]
                    if key == "$ref" and isinstance(nested, str) and nested.startswith("#/"):
                        refs.append((next_path, nested))
                    walk(nested, next_path)
            elif isinstance(value, list):
                for index, nested in enumerate(value):
                    walk(nested, path + [str(index)])

        def exists(ref):
            current = document
            for raw_part in ref[2:].split("/"):
                part = raw_part.replace("~1", "/").replace("~0", "~")
                if isinstance(current, dict) and part in current:
                    current = current[part]
                elif isinstance(current, list) and part.isdigit() and int(part) < len(current):
                    current = current[int(part)]
                else:
                    return False
            return True

        walk(document, [])
        unresolved = [f"{'/'.join(path)} -> {ref}" for path, ref in refs if not exists(ref)]

        self.assertEqual([], unresolved)


if __name__ == "__main__":
    unittest.main()
