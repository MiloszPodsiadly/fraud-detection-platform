import { isAuthenticated, normalizeSession } from "./session.js";

const STORAGE_KEY = "analyst-console.demo-session";
const DEFAULT_USER_ID = import.meta.env.VITE_DEMO_USER_ID || "analyst.local";
const DEFAULT_ROLES = splitCsv(import.meta.env.VITE_DEMO_ROLES || "REVIEWER");

export function getInitialDemoSession() {
  const stored = readStoredSession();
  if (stored) {
    return normalizeSession(stored);
  }
  return normalizeSession({ userId: DEFAULT_USER_ID, roles: DEFAULT_ROLES });
}

export function saveDemoSession(session) {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify({
    userId: session.userId,
    roles: session.roles,
    extraAuthorities: session.extraAuthorities
  }));
}

export function demoAuthHeaders(session) {
  if (!isAuthenticated(session)) {
    return {};
  }
  const headers = {
    "X-Demo-User-Id": session.userId,
    "X-Demo-Roles": session.roles.join(",")
  };
  if (session.extraAuthorities.length > 0) {
    headers["X-Demo-Authorities"] = session.extraAuthorities.join(",");
  }
  return headers;
}

function readStoredSession() {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function splitCsv(value) {
  return String(value).split(",").map((item) => item.trim()).filter(Boolean);
}
