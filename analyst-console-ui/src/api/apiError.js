export class ApiError extends Error {
  constructor({ status, error, message, details }) {
    super(message || `Request failed with status ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.error = error;
    this.details = details || [];
    this.reasonCode = stableReasonCode(this.details);
  }

  get isUnauthorized() {
    return this.status === 401;
  }

  get isForbidden() {
    return this.status === 403;
  }
}

function stableReasonCode(details) {
  if (!Array.isArray(details)) {
    return null;
  }
  const codes = details
    .filter((detail) => typeof detail === "string" && /^reason:[A-Z][A-Z0-9_]{0,95}$/.test(detail))
    .map((detail) => detail.slice("reason:".length));
  return codes.length === 1 ? codes[0] : null;
}
