import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { AUTHORITIES } from "./session.js";

function backendAuthorities() {
  const currentFile = fileURLToPath(import.meta.url);
  const authorityFile = resolve(
    dirname(currentFile),
    "../../../alert-service/src/main/java/com/frauddetection/alert/security/authorization/AnalystAuthority.java"
  );
  const source = readFileSync(authorityFile, "utf8");
  return [...source.matchAll(/=\s*\"([a-z:-]+)\";/g)].map((match) => match[1]).sort();
}

describe("authority contract", () => {
  it("matches backend analyst authority names", () => {
    expect(Object.values(AUTHORITIES).sort()).toEqual(backendAuthorities());
  });
});
