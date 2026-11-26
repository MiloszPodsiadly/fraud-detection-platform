#!/usr/bin/env node
import { discoverMavenReactorPoms } from './maven-reactor.mjs';

process.stdout.write(discoverMavenReactorPoms().map((pom) => `${pom}\0`).join(''));
