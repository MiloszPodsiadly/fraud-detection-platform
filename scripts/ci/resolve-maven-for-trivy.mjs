import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const MAVEN_GOAL = "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:go-offline";
export const MAX_ATTEMPTS = 3;
export const MAX_TOTAL_RETRY_WAIT_MS = 5 * 60 * 1000;
const MAX_BACKOFF_MS = 15 * 1000;

export function retryAfterMilliseconds(output, now = Date.now()) {
  const match = output.match(/Retry-After\s*:\s*([^\r\n]+)/i);
  if (!match) return undefined;
  const value = match[1].trim();
  const seconds = value.match(/^(\d+)/);
  if (seconds) return Number(seconds[1]) * 1000;
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return undefined;
  return Math.max(0, timestamp - now);
}

export function isRetryableMavenFailure(output) {
  return /(?:HTTP(?:\/\d(?:\.\d)?)?\s+429\b|returned\s+429\b|status(?:\s+code)?\s*[:=]?\s*429\b|\b5\d\d\s+(?:Server\s+Error|Bad Gateway|Service Unavailable|Gateway Timeout)\b|(?:Connection reset|Connection refused|timed out|Temporary failure|Unknown host|Name or service not known))/i
    .test(output);
}

export async function runMavenResolution({
  execute = executeMaven,
  sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
  now = Date.now,
  maxAttempts = MAX_ATTEMPTS,
  maxTotalRetryWaitMs = MAX_TOTAL_RETRY_WAIT_MS,
  onOutput = (output) => { if (output) process.stdout.write(output); }
} = {}) {
  let totalWaitMs = 0;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const result = await execute();
    const output = `${result.stdout ?? ""}${result.stderr ?? ""}`;
    onOutput(output);
    if (result.error) {
      return { status: Number.isInteger(result.status) ? result.status : 1, attempts: attempt,
        message: `Maven dependency prefetch could not start (${result.error.code ?? "spawn error"}).` };
    }
    if (result.status === 0) return { status: 0, attempts: attempt, totalWaitMs };

    if (attempt === maxAttempts || !isRetryableMavenFailure(output)) {
      return { status: result.status ?? 1, attempts: attempt,
        message: `Maven dependency prefetch failed after ${attempt} attempt(s).` };
    }

    const retryAfterMs = retryAfterMilliseconds(output, now());
    const backoffMs = Math.min(1000 * (2 ** (attempt - 1)), MAX_BACKOFF_MS);
    const delayMs = retryAfterMs ?? backoffMs;
    if (totalWaitMs + delayMs > maxTotalRetryWaitMs) {
      const delaySeconds = Math.ceil(delayMs / 1000);
      const budgetSeconds = Math.floor(maxTotalRetryWaitMs / 1000);
      return {
        status: result.status ?? 1,
        attempts: attempt,
        totalWaitMs,
        message: `Maven requested a ${delaySeconds}s Retry-After, beyond the ${budgetSeconds}s bounded retry budget; stopping without an early retry.`
      };
    }

    console.log(`Maven dependency prefetch attempt ${attempt} failed transiently; waiting ${Math.ceil(delayMs / 1000)}s before retry.`);
    await sleep(delayMs);
    totalWaitMs += delayMs;
  }
  return { status: 1, attempts: maxAttempts, message: "Maven dependency prefetch exhausted its retry budget." };
}

function executeMaven() {
  return spawnSync("mvn", ["-B", "-ntp", MAVEN_GOAL], {
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  runMavenResolution().then((result) => {
    if (result.status !== 0) {
      console.error(result.message);
      process.exitCode = result.status;
      return;
    }
    console.log(`Maven dependencies and plugins are locally available after ${result.attempts} attempt(s).`);
  }).catch((error) => {
    console.error(`Maven dependency prefetch failed unexpectedly (${error.message}).`);
    process.exitCode = 1;
  });
}
