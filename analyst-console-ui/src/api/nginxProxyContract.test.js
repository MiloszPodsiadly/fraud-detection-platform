import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

describe("nginx API proxy contract", () => {
  it("proxies internal protected read API routes before SPA fallback", () => {
    const config = readFileSync(join(process.cwd(), "nginx.conf"), "utf8");
    const internalLocationIndex = config.indexOf("location /internal/");
    const fallbackIndex = config.indexOf("location / {");

    expect(internalLocationIndex).toBeGreaterThan(-1);
    expect(internalLocationIndex).toBeLessThan(fallbackIndex);
    expect(config).toContain("proxy_pass http://alert-service:8080/internal/;");
  });
});
