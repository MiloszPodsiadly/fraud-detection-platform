export function authHeadersForSession(authProvider, session) {
  if (!authProvider || typeof authProvider.getRequestHeaders !== "function") {
    return {};
  }

  return authProvider.getRequestHeaders(session) || {};
}
