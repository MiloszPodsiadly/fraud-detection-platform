import path from "node:path";
import { fileURLToPath } from "node:url";
import { SECURITY_POLICY } from "../config/policy.mjs";
import { runBoundedProcess } from "../lib/run-bounded-process.mjs";

export const MAVEN_GOAL = `org.apache.maven.plugins:maven-dependency-plugin:${SECURITY_POLICY.tools.mavenDependencyPlugin}:go-offline`;
export const MAX_ATTEMPTS = SECURITY_POLICY.processLimits.mavenResolutionAttempts;
export const MAX_TOTAL_RETRY_WAIT_MS = SECURITY_POLICY.processLimits.mavenRetryWaitMs;
export const MAX_TOTAL_DURATION_MS = SECURITY_POLICY.processLimits.mavenResolutionTotalMs;
export const MAVEN_PROCESS_TIMEOUT_MS = SECURITY_POLICY.processLimits.mavenProcessMs;
const MAX_BACKOFF_MS = SECURITY_POLICY.processLimits.mavenBackoffMaxMs;
const REPOSITORY_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");

export function retryAfterMilliseconds(output, now = Date.now()) {
  const match = output.match(/(?:^|\r?\n)Retry-After\s*:\s*([^\r\n]+)\s*(?:\r?\n|$)/i);
  if (!match) return undefined;
  const value = match[1].trim();
  if (/^\d+$/.test(value)) {
    const seconds = Number(value);
    return Number.isFinite(seconds) ? Math.min(Number.MAX_VALUE, seconds * 1000) : Number.MAX_VALUE;
  }
  if (!/^(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun), \d{2} (?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4} \d{2}:\d{2}:\d{2} GMT$/.test(value)) {
    return undefined;
  }
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return undefined;
  if (new Date(timestamp).toUTCString() !== value) return undefined;
  return Math.max(0, timestamp - now);
}

export function isRetryableMavenFailure(output) {
  return /(?:HTTP(?:\/\d(?:\.\d)?)?\s+429\b|returned\s+429\b|status(?:\s+code)?\s*[:=]?\s*429\b|\b(?:500|502|503|504)\b|(?:Connection reset|Connection refused|timed out|Temporary failure|Unknown host|Name or service not known|No route to host|Network is unreachable|remote host terminated handshake))/i
    .test(output);
}

export function classifyMavenFailure(result, output) {
  if (result.interrupted) return "interrupted";
  if (result.timedOut) return "timeout";
  if (result.error) return "execution";
  return isRetryableMavenFailure(output) ? "network" : "resolution-or-policy";
}

export function redactMavenDiagnostics(output) {
  return output
    .replace(/(https?:\/\/)[^\s/@]+:[^\s/@]+@/gi, "$1[REDACTED]@")
    .replace(/((?:password|passwd|token|secret|authorization|api[_-]?key)\s*[=:]\s*)[^\s,;]+/gi, "$1[REDACTED]");
}

export async function runMavenResolution({
  execute = executeMaven,
  sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
  now = Date.now,
  monotonicNow = () => performance.now(),
  maxAttempts = MAX_ATTEMPTS,
  maxTotalRetryWaitMs = MAX_TOTAL_RETRY_WAIT_MS,
  maxTotalDurationMs = MAX_TOTAL_DURATION_MS,
  processTimeoutMs = MAVEN_PROCESS_TIMEOUT_MS,
  signal,
  onOutput = (output) => { if (output) process.stdout.write(redactMavenDiagnostics(output)); }
} = {}) {
  const startedAt = monotonicNow();
  let totalWaitMs = 0;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const remainingMs = maxTotalDurationMs - (monotonicNow() - startedAt);
    if (remainingMs <= 0) {
      return { status: 124, attempts: attempt - 1, totalWaitMs, failureKind: "timeout",
        message: "Maven dependency prefetch exceeded its total execution deadline." };
    }

    let result;
    try {
      result = await execute({ timeoutMs: Math.min(processTimeoutMs, remainingMs), signal });
    } catch (error) {
      result = { status: 1, error };
    }
    const output = `${result.stdout ?? ""}${result.stderr ?? ""}`;
    onOutput(output);
    if (result.error || result.timedOut || result.interrupted) {
      const failureKind = classifyMavenFailure(result, output);
      const cause = result.error ? result.error.code ?? "spawn error" : failureKind;
      return { status: result.status ?? (failureKind === "timeout" ? 124 : 1), attempts: attempt,
        totalWaitMs, failureKind,
        message: `Maven dependency prefetch stopped (${failureKind}; ${cause}).` };
    }
    if (result.status === 0) return { status: 0, attempts: attempt, totalWaitMs };

    const failureKind = classifyMavenFailure(result, output);
    if (attempt === maxAttempts || failureKind !== "network") {
      return { status: result.status ?? 1, attempts: attempt, totalWaitMs, failureKind,
        message: `Maven dependency prefetch failed after ${attempt} attempt(s) (${failureKind}).` };
    }

    const retryAfterMs = retryAfterMilliseconds(output, now());
    const backoffMs = Math.min(1000 * (2 ** (attempt - 1)), MAX_BACKOFF_MS);
    const delayMs = retryAfterMs ?? backoffMs;
    const remainingBeforeRetryMs = maxTotalDurationMs - (monotonicNow() - startedAt);
    if (totalWaitMs + delayMs > maxTotalRetryWaitMs || delayMs >= remainingBeforeRetryMs) {
      const delaySeconds = Math.ceil(delayMs / 1000);
      const budgetSeconds = Math.floor(Math.min(maxTotalRetryWaitMs, Math.max(0, remainingBeforeRetryMs)) / 1000);
      return {
        status: result.status ?? 1,
        attempts: attempt,
        totalWaitMs,
        failureKind: remainingBeforeRetryMs <= 0 || delayMs >= remainingBeforeRetryMs ? "timeout" : "network",
        message: `Maven requested a ${delaySeconds}s retry delay beyond the remaining ${budgetSeconds}s bounded budget; stopping without an early retry.`
      };
    }

    console.log(`Maven dependency prefetch attempt ${attempt} failed transiently; waiting ${Math.ceil(delayMs / 1000)}s before retry.`);
    await sleep(delayMs);
    totalWaitMs += delayMs;
  }
  return { status: 1, attempts: maxAttempts, totalWaitMs, failureKind: "network",
    message: "Maven dependency prefetch exhausted its retry budget." };
}

function executeMaven({ timeoutMs, signal }) {
  const mavenRepository = path.join(REPOSITORY_ROOT, "target", "security", "home", ".m2", "repository");
  const executable = process.env.MAVEN_BIN ?? (process.platform === "win32" ? "mvn.cmd" : "mvn");
  return runBoundedProcess(executable, ["-B", "-ntp", `-Dmaven.repo.local=${mavenRepository}`, MAVEN_GOAL], {
    cwd: REPOSITORY_ROOT,
    shell: process.platform === "win32",
    timeoutMs,
    maxOutputBytes: 2 * 1024 * 1024,
    signal,
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const abortController = new AbortController();
  const abort = () => abortController.abort();
  process.once("SIGINT", abort);
  process.once("SIGTERM", abort);
  runMavenResolution({ signal: abortController.signal }).then((result) => {
    if (result.status !== 0) {
      console.error(result.message);
      process.exitCode = result.status;
      return;
    }
    console.log(`Maven dependencies and plugins are locally available after ${result.attempts} attempt(s).`);
  }).catch((error) => {
    console.error(`Maven dependency prefetch failed unexpectedly (${error.message}).`);
    process.exitCode = 1;
  }).finally(() => {
    process.removeListener("SIGINT", abort);
    process.removeListener("SIGTERM", abort);
  });
}
