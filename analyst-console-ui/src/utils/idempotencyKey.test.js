import { afterEach, describe, expect, it, vi } from "vitest";
import { createIdempotencyKey, secureRandomId } from "./idempotencyKey.js";

describe("idempotencyKey", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uses crypto.randomUUID when available", () => {
    vi.stubGlobal("crypto", { randomUUID: vi.fn(() => "uuid-1") });

    expect(createIdempotencyKey("alert-decision", "alert-1")).toBe("alert-decision-alert-1-uuid-1");
  });

  it("falls back to crypto.getRandomValues when randomUUID is unavailable", () => {
    vi.stubGlobal("crypto", {
      getRandomValues: vi.fn((bytes) => {
        bytes.set([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]);
        return bytes;
      })
    });

    expect(createIdempotencyKey("fraud-case-update", "case-1")).toBe("fraud-case-update-case-1-00010203-0405-4607-8809-0a0b0c0d0e0f");
  });

  it("fails closed when Web Crypto is unavailable", () => {
    vi.stubGlobal("crypto", undefined);

    expect(() => secureRandomId()).toThrow("Web Crypto is required to create an idempotency key.");
  });
});
