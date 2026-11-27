#!/usr/bin/env node
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { sanitizeDockerImageInspect } from './image-identity.mjs';

const [outputPathArgument, expectedPlatform] = process.argv.slice(2);
if (!outputPathArgument || !expectedPlatform || process.argv.length !== 4) {
  throw new Error('Usage: node write-docker-image-identity.mjs <output-path> <expected-platform>');
}

const outputPath = resolve(outputPathArgument);
const input = readFileSync(0, 'utf8');
const sanitized = sanitizeDockerImageInspect(input, expectedPlatform);
mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, sanitized);
