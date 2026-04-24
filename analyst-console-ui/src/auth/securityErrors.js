export function securityErrorKind(error) {
  const status = typeof error === "object" ? error?.status : undefined;
  const code = typeof error === "object" ? error?.error : undefined;
  const reason = typeof error === "object" ? securityReason(error?.details) : undefined;

  if (status === 401 && code === "session_expired") {
    return "expired";
  }
  if (code === "auth_provider_error") {
    return "auth_error";
  }
  if (reason === "insufficient_authority" || status === 403) {
    return "forbidden";
  }
  if (
    reason === "missing_credentials"
    || reason === "invalid_demo_auth"
    || reason === "invalid_jwt"
    || status === 401
  ) {
    return "unauthorized";
  }
  return "generic";
}

export function securityErrorMessage(error) {
  if (!error) {
    return "";
  }
  if (typeof error === "string") {
    return error;
  }
  return error.message || `Request failed with status ${error.status}`;
}

function securityReason(details) {
  if (!Array.isArray(details)) {
    return "";
  }
  const reasonEntry = details.find((detail) => typeof detail === "string" && detail.startsWith("reason:"));
  return reasonEntry ? reasonEntry.slice("reason:".length) : "";
}
