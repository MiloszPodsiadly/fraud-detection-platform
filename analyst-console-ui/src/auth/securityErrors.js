export function securityErrorKind(error) {
  const status = typeof error === "object" ? error?.status : undefined;
  const message = typeof error === "string" ? error : error?.message;
  if (status === 401 || message === "Authentication is required.") {
    return "unauthorized";
  }
  if (status === 403 || message === "Insufficient permissions.") {
    return "forbidden";
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
