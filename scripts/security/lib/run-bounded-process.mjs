import { spawn } from 'node:child_process';

export function runBoundedProcess(command, args, {
  cwd = process.cwd(),
  env = process.env,
  shell = false,
  timeoutMs = 60_000,
  maxOutputBytes = 8 * 1024 * 1024,
  killGraceMs = 1_000,
  signal,
  inheritStdio = false,
} = {}) {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new Error('timeoutMs must be a positive finite number.');
  if (!Number.isInteger(maxOutputBytes) || maxOutputBytes < 0) throw new Error('maxOutputBytes must be a non-negative integer.');
  if (signal?.aborted) {
    return Promise.resolve({ status: 130, signal: null, stdout: '', stderr: '', interrupted: true, timedOut: false });
  }

  return new Promise((resolve) => {
    let child;
    let spawnError;
    let timedOut = false;
    let interrupted = false;
    let stdout = Buffer.alloc(0);
    let stderr = Buffer.alloc(0);
    let outputTruncated = false;
    let settled = false;
    let timeoutTimer;
    let forceTimer;

    const finish = (code, processSignal) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeoutTimer);
      clearTimeout(forceTimer);
      signal?.removeEventListener('abort', onAbort);
      resolve({
        status: timedOut ? 124 : interrupted ? 130 : spawnError ? 1 : (Number.isInteger(code) && code >= 0 ? code : 1),
        signal: processSignal ?? null,
        stdout: stdout.toString('utf8'),
        stderr: stderr.toString('utf8'),
        error: spawnError,
        timedOut,
        interrupted,
        outputTruncated,
      });
    };

    const terminate = (reason) => {
      if (!child || settled) return;
      timedOut ||= reason === 'timeout';
      interrupted ||= reason === 'interrupted';
      terminateProcessTree(child, false);
      forceTimer = setTimeout(() => terminateProcessTree(child, true), killGraceMs);
      forceTimer.unref?.();
    };

    const onAbort = () => terminate('interrupted');
    try {
      child = spawn(command, args, {
        cwd,
        env,
        shell,
        detached: process.platform !== 'win32',
        windowsHide: true,
        stdio: inheritStdio ? 'inherit' : ['ignore', 'pipe', 'pipe'],
      });
    } catch (error) {
      resolve({ status: 1, signal: null, stdout: '', stderr: '', error, timedOut: false, interrupted: false, outputTruncated: false });
      return;
    }

    child.stdout?.on('data', (chunk) => {
      const result = appendTail(stdout, chunk, maxOutputBytes);
      stdout = result.buffer;
      outputTruncated ||= result.truncated;
    });
    child.stderr?.on('data', (chunk) => {
      const result = appendTail(stderr, chunk, maxOutputBytes);
      stderr = result.buffer;
      outputTruncated ||= result.truncated;
    });
    child.once('error', (error) => { spawnError = error; });
    child.once('close', finish);
    timeoutTimer = setTimeout(() => terminate('timeout'), timeoutMs);
    timeoutTimer.unref?.();
    signal?.addEventListener('abort', onAbort, { once: true });
    if (signal?.aborted) onAbort();
  });
}

function appendTail(current, chunk, limit) {
  if (limit === 0) return { buffer: Buffer.alloc(0), truncated: current.length > 0 || chunk.length > 0 };
  if (chunk.length >= limit) return { buffer: chunk.subarray(chunk.length - limit), truncated: current.length > 0 || chunk.length > limit };
  const combined = Buffer.concat([current, chunk]);
  if (combined.length <= limit) return { buffer: combined, truncated: false };
  return { buffer: combined.subarray(combined.length - limit), truncated: true };
}

function terminateProcessTree(child, force) {
  if (!child.pid) return;
  if (process.platform === 'win32') {
    const args = ['/pid', String(child.pid), '/t'];
    if (force) args.push('/f');
    try {
      const killer = spawn('taskkill.exe', args, { windowsHide: true, stdio: 'ignore' });
      killer.once('error', () => {});
      killer.unref();
    } catch {
      // The direct child is still signalled below if process-tree termination is unavailable.
    }
    try { child.kill(force ? 'SIGKILL' : 'SIGTERM'); } catch {}
    return;
  }

  try {
    process.kill(-child.pid, force ? 'SIGKILL' : 'SIGTERM');
  } catch {
    try { child.kill(force ? 'SIGKILL' : 'SIGTERM'); } catch {}
  }
}
