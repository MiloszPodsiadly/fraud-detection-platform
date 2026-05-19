import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

describe("nginx API proxy contract", () => {
  it("proxies only the suspicious transaction internal read API before SPA fallback", () => {
    const config = readFileSync(join(process.cwd(), "nginx.conf"), "utf8");
    const exactSuspiciousLocationIndex = config.indexOf("location = /internal/suspicious-transactions");
    const nestedSuspiciousLocationIndex = config.indexOf("location /internal/suspicious-transactions/");
    const fallbackIndex = config.indexOf("location / {");

    expect(exactSuspiciousLocationIndex).toBeGreaterThan(-1);
    expect(nestedSuspiciousLocationIndex).toBeGreaterThan(-1);
    expect(exactSuspiciousLocationIndex).toBeLessThan(fallbackIndex);
    expect(nestedSuspiciousLocationIndex).toBeLessThan(fallbackIndex);
    expect(config).toContain("proxy_pass http://alert-service:8080/internal/suspicious-transactions;");
    expect(config).toContain("proxy_pass http://alert-service:8080/internal/suspicious-transactions/;");
    expect(config).not.toMatch(/location\s+\/internal\/\s*\{/);
    expect(config).not.toContain("proxy_pass http://alert-service:8080/internal/;");
  });
});
