import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const root = process.env.FDP50_API_BOUNDARY_ROOT ?? repoRoot;
const requireFromFrontend = createRequire(join(repoRoot, "analyst-console-ui/package.json"));
const { parse } = requireFromFrontend("@babel/parser");
const failureMessage = "FDP-50 requires auth-sensitive UI code to use explicit workspace-scoped apiClient instances.";
const scannedRoots = [
  "analyst-console-ui/src/workspace",
  "analyst-console-ui/src/fraudCases",
  "analyst-console-ui/src/components",
  "analyst-console-ui/src/pages"
];
const scannedFiles = ["analyst-console-ui/src/App.jsx"];
const allowedAlertsApiImports = new Set([
  "createAlertsApiClient",
  "isAbortError",
  "toUtcInstantParam"
]);
const defaultWrapperExports = new Set([
  "listAlerts",
  "listFraudCaseWorkQueue",
  "getFraudCaseWorkQueueSummary",
  "listScoredTransactions",
  "listGovernanceAdvisories",
  "getGovernanceAdvisoryAnalytics",
  "getGovernanceAdvisoryAudit",
  "recordGovernanceAdvisoryAudit",
  "getAlert",
  "getAssistantSummary",
  "getFraudCase",
  "updateFraudCase",
  "submitAnalystDecision"
]);

const files = [
  ...scannedRoots.flatMap((directory) => walk(directory)),
  ...scannedFiles.filter((file) => existsSync(join(root, file)))
]
  .filter((file) => /\.(js|jsx|ts|tsx)$/.test(file))
  .filter((file) => !isExcluded(file));

let failed = false;

for (const file of files) {
  const source = readFileSync(join(root, file), "utf8");
  const ast = parse(source, {
    sourceType: "module",
    plugins: ["jsx", "importAttributes"],
    errorRecovery: false,
    ranges: true,
    tokens: false
  });
  const fetchAliases = new Set();

  traverse(ast, null, (node, parent) => {
    if (node.type === "ImportDeclaration" && isAlertsApiSource(node.source.value)) {
      inspectAlertsApiImport(file, node);
    }
    if (node.type === "ExportAllDeclaration" && isAlertsApiSource(node.source.value)) {
      fail(file, node, "re-exports alertsApi.js from an auth-sensitive boundary");
    }
    if (node.type === "ExportNamedDeclaration" && node.source && isAlertsApiSource(node.source.value)) {
      inspectAlertsApiReExport(file, node);
    }
    if (node.type === "CallExpression" && node.callee.type === "Import" && node.arguments.some((argument) => isAlertsApiLiteral(argument))) {
      fail(file, node, "dynamically imports alertsApi.js from an auth-sensitive boundary");
    }
    if (node.type === "VariableDeclarator" && node.id.type === "Identifier" && isFetchReference(node.init)) {
      fetchAliases.add(node.id.name);
      fail(file, node, `aliases raw fetch as ${node.id.name}`);
    }
    if (node.type === "CallExpression" && isForbiddenFetchCall(node.callee, fetchAliases)) {
      fail(file, node, "uses raw fetch outside API/auth bootstrap code");
    }
    if (node.type === "CallExpression" && node.callee.type === "Identifier" && node.callee.name === "setApiSession") {
      fail(file, node, "calls legacy setApiSession from auth-sensitive UI code");
    }
    if (
      node.type === "MemberExpression"
      && parent?.type === "CallExpression"
      && parent.callee === node
      && node.object.type === "Identifier"
      && node.object.name === "alertsApi"
    ) {
      fail(file, node, "uses alertsApi namespace instead of an explicit apiClient");
    }
  });
}

if (failed) {
  console.error(failureMessage);
  process.exit(1);
}

function inspectAlertsApiImport(file, node) {
  for (const specifier of node.specifiers) {
    if (specifier.type === "ImportNamespaceSpecifier") {
      fail(file, specifier, "imports alertsApi.js as a namespace");
      continue;
    }
    if (specifier.type === "ImportDefaultSpecifier") {
      fail(file, specifier, "imports the default alertsApi compatibility client");
      continue;
    }
    const imported = specifier.imported?.name || specifier.imported?.value;
    if (defaultWrapperExports.has(imported)) {
      fail(file, specifier, `imports compatibility wrapper ${imported}`);
      continue;
    }
    if (!allowedAlertsApiImports.has(imported)) {
      fail(file, specifier, `imports non-allowlisted alertsApi export ${imported}`);
    }
  }
}

function inspectAlertsApiReExport(file, node) {
  for (const specifier of node.specifiers) {
    const exported = specifier.local?.name || specifier.local?.value || specifier.exported?.name || specifier.exported?.value;
    if (exported === "default" || defaultWrapperExports.has(exported) || !allowedAlertsApiImports.has(exported)) {
      fail(file, specifier, `re-exports alertsApi export ${exported}`);
    }
  }
}

function isForbiddenFetchCall(callee, aliases) {
  if (callee.type === "Identifier") {
    return callee.name === "fetch" || aliases.has(callee.name);
  }
  return isFetchReference(callee);
}

function isFetchReference(node) {
  if (!node) {
    return false;
  }
  if (node.type === "Identifier") {
    return node.name === "fetch";
  }
  if (node.type !== "MemberExpression") {
    return false;
  }
  const objectName = node.object.type === "Identifier" ? node.object.name : "";
  if (objectName !== "window" && objectName !== "globalThis") {
    return false;
  }
  if (!node.computed && node.property.type === "Identifier") {
    return node.property.name === "fetch";
  }
  return node.computed && isFetchLiteral(node.property);
}

function isFetchLiteral(node) {
  return (node.type === "StringLiteral" || node.type === "Literal") && node.value === "fetch";
}

function isAlertsApiLiteral(node) {
  return (node.type === "StringLiteral" || node.type === "Literal") && isAlertsApiSource(node.value);
}

function isAlertsApiSource(value) {
  return typeof value === "string" && /(^|\/)api\/alertsApi\.js$/.test(value.replaceAll("\\", "/"));
}

function traverse(node, parent, visitor) {
  if (!node || typeof node.type !== "string") {
    return;
  }
  visitor(node, parent);
  for (const [key, value] of Object.entries(node)) {
    if (key === "loc" || key === "start" || key === "end" || key === "range") {
      continue;
    }
    if (Array.isArray(value)) {
      for (const child of value) {
        traverse(child, node, visitor);
      }
    } else if (value && typeof value.type === "string") {
      traverse(value, node, visitor);
    }
  }
}

function walk(relativeDirectory) {
  const directory = join(root, relativeDirectory);
  if (!existsSync(directory)) {
    return [];
  }
  return readdirSync(directory).flatMap((entry) => {
    const absolute = join(directory, entry);
    const relative = `${relativeDirectory}/${entry}`;
    return statSync(absolute).isDirectory() ? walk(relative) : [relative];
  });
}

function isExcluded(file) {
  const normalized = file.replaceAll("\\", "/");
  return /(\.test\.[jt]sx?|__mocks__|\/mocks\/|generated)/.test(normalized);
}

function fail(file, node, message) {
  failed = true;
  const line = node.loc?.start?.line ?? 1;
  console.error(`${file}:${line}: ${message}`);
}
