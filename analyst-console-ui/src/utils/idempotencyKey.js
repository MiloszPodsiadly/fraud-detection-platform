export function createIdempotencyKey(operationPrefix, entityId) {
  void entityId;
  return `${operationPrefix}-${secureRandomId()}`;
}

export function secureRandomId() {
  const crypto = globalThis.crypto;
  if (typeof crypto?.randomUUID === "function") {
    return crypto.randomUUID();
  }
  if (typeof crypto?.getRandomValues === "function") {
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    return [...bytes].map((byte, index) => {
      const hex = byte.toString(16).padStart(2, "0");
      return [4, 6, 8, 10].includes(index) ? `-${hex}` : hex;
    }).join("");
  }
  throw new Error("Web Crypto is required to create an idempotency key.");
}
