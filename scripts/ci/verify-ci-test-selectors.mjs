import { existsSync, readdirSync, readFileSync } from "node:fs";
import { basename, dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { ciSuites } from "./ci-suites.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const failures = validateRepositorySelectors();
  if (failures.length > 0) {
    console.error("CI test selector validation failed:");
    for (const failure of failures) console.error(`- ${failure}`);
    process.exitCode = 1;
  } else {
    console.log("CI test selector validation passed");
  }
}

export function validateCiTestSelectors({ suites, workflows, javaTestClasses, existingFiles }) {
  const failures = [];
  const classes = javaTestClasses instanceof Set ? javaTestClasses : new Set(javaTestClasses);
  const files = new Set([...existingFiles].map(normalizePath));

  for (const [suiteName, suite] of Object.entries(suites)) {
    for (const required of suite.junitRequired ?? []) {
      validateJavaSelector(required, `ciSuites.${suiteName}.junitRequired`, classes, failures);
    }
    for (const group of suite.activationGroups ?? []) {
      for (const file of group.files ?? []) {
        const normalized = normalizePath(file);
        if (!files.has(normalized)) {
          failures.push(`ciSuites.${suiteName}.${group.label}: missing activation file ${file}`);
        } else if (normalized.endsWith(".java")) {
          validateJavaSelector(basename(normalized, ".java"), `ciSuites.${suiteName}.${group.label}`, classes, failures);
        }
      }
    }
  }

  for (const workflow of workflows) {
    for (const command of collectWorkflowRunCommands(workflow.source)) {
      for (const selector of extractMavenTestSelectors(command)) {
        for (const classSelector of selector.split(",").map((entry) => entry.trim()).filter(Boolean)) {
          validateJavaSelector(classSelector, `${workflow.path} Maven -Dtest`, classes, failures);
        }
      }
    }
  }

  return failures;
}

export function collectWorkflowRunCommands(source) {
  const lines = source.split(/\r?\n/);
  const commands = [];
  for (let index = 0; index < lines.length; index += 1) {
    const match = lines[index].match(/^(\s*)run:\s*(.*)$/);
    if (!match) continue;

    const indentation = match[1].length;
    const value = match[2].trim();
    if (/^(?:\||>)[+-]?(?:\s+#.*)?$/.test(value) || value === "") {
      const block = [];
      for (index += 1; index < lines.length; index += 1) {
        const line = lines[index];
        if (line.trim() === "") {
          block.push("");
          continue;
        }
        const lineIndentation = line.match(/^\s*/)[0].length;
        if (lineIndentation <= indentation) {
          index -= 1;
          break;
        }
        block.push(line.slice(Math.min(lineIndentation, indentation + 2)));
      }
      commands.push(block.join("\n"));
    } else {
      commands.push(value);
    }
  }
  return commands;
}

export function extractMavenTestSelectors(command) {
  const selectors = [];
  const pattern = /(?:^|\s)-Dtest\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"']+))/g;
  for (const match of command.matchAll(pattern)) {
    selectors.push(match[1] ?? match[2] ?? match[3] ?? "");
  }
  return selectors;
}

function validateJavaSelector(rawSelector, source, classes, failures) {
  let selector = rawSelector.trim();
  if (!selector || selector.startsWith("!") || /[*?{}\[\]]|\$\{/.test(selector)) return;
  selector = selector.split(/[#+]/, 1)[0];
  if (!selector || /[^\w.$]/.test(selector)) return;

  const exists = selector.includes(".")
    ? classes.has(selector)
    : classes.has(selector);
  if (!exists) failures.push(`${source}: missing Java test class ${selector}`);
}

function validateRepositorySelectors() {
  const javaTestClasses = new Set();
  const existingFiles = new Set();
  collectFiles(repoRoot, (path, source) => {
    const relativePath = normalizePath(relative(repoRoot, path));
    existingFiles.add(relativePath);
    if (!relativePath.includes("/src/test/java/") || !path.endsWith(".java")) return;
    const simpleName = basename(path, ".java");
    javaTestClasses.add(simpleName);
    const packageName = source.match(/^\s*package\s+([\w.]+)\s*;/m)?.[1];
    if (packageName) javaTestClasses.add(`${packageName}.${simpleName}`);
  });

  const workflowDirectory = join(repoRoot, ".github", "workflows");
  const workflows = readdirSync(workflowDirectory)
    .filter((entry) => entry.endsWith(".yml") || entry.endsWith(".yaml"))
    .map((entry) => {
      const path = join(workflowDirectory, entry);
      return { path: relative(repoRoot, path), source: readFileSync(path, "utf8") };
    });

  return validateCiTestSelectors({ suites: ciSuites, workflows, javaTestClasses, existingFiles });
}

function collectFiles(directory, visit) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.isDirectory() && shouldSkipDirectory(entry.name)) continue;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      collectFiles(path, visit);
    } else if (entry.isFile()) {
      visit(path, path.endsWith(".java") ? readFileSync(path, "utf8") : "");
    }
  }
}

function shouldSkipDirectory(name) {
  return new Set([".git", ".m2repo", ".venv", "node_modules", "target", "build", "dist", ".gradle"]).has(name);
}

function normalizePath(path) {
  return path.replaceAll("\\", "/").replace(/^\.\//, "");
}
