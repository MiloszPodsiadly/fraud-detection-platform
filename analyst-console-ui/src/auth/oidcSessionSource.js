import { normalizeSession } from "./session.js";
import { SESSION_STATES } from "./sessionState.js";

const OIDC_GROUP_ROLE_MAPPING = Object.freeze({
  "fraud-readonly-analyst": "READ_ONLY_ANALYST",
  "fraud-analyst": "ANALYST",
  "fraud-reviewer": "REVIEWER",
  "fraud-ops-admin": "FRAUD_OPS_ADMIN"
});

export function createInMemoryOidcSessionSource(initialState = {}) {
  let snapshot = normalizeOidcSessionSnapshot(initialState);

  return {
    getSession() {
      return snapshot.session;
    },
    setSession(nextSession) {
      snapshot = normalizeOidcSessionSnapshot({
        ...snapshot,
        session: nextSession
      });
    },
    getAccessToken() {
      return snapshot.accessToken;
    },
    setAccessToken(nextAccessToken) {
      snapshot = normalizeOidcSessionSnapshot({
        ...snapshot,
        accessToken: nextAccessToken
      });
    },
    getState() {
      return snapshot.state;
    },
    setState(nextState) {
      snapshot = normalizeOidcSessionSnapshot({
        ...snapshot,
        state: nextState
      });
    },
    replace(nextSnapshot) {
      snapshot = normalizeOidcSessionSnapshot(nextSnapshot);
    }
  };
}

export function createOidcSessionSource(oidcClient, initialState = {}) {
  const source = createInMemoryOidcSessionSource(initialState);

  return {
    ...source,
    async refresh() {
      source.replace(snapshotFromOidcUser(await oidcClient.getUser()));
      return source.getSession();
    },
    async completeLoginCallback() {
      source.replace(snapshotFromOidcUser(await oidcClient.completeLoginCallback()));
      return source.getSession();
    },
    clear() {
      source.replace({});
    }
  };
}

export function oidcAuthHeaders(accessToken) {
  const normalizedAccessToken = normalizeAccessToken(accessToken);
  if (!normalizedAccessToken) {
    return {};
  }
  return {
    Authorization: `Bearer ${normalizedAccessToken}`
  };
}

export function mapOidcProfileToSession(profile = {}) {
  return normalizeSession({
    userId: profile.userId || profile.sub || "",
    roles: resolveRoles(profile),
    extraAuthorities: profile.authorities || []
  });
}

export function normalizeOidcSessionSnapshot(input = {}) {
  const session = normalizeSession(resolveSessionInput(input));
  const accessToken = normalizeAccessToken(input.accessToken);
  return {
    session,
    accessToken,
    state: normalizeSourceState(input.state, session, accessToken)
  };
}

export function snapshotFromOidcUser(user) {
  if (!user) {
    return normalizeOidcSessionSnapshot({});
  }

  const expiresAt = normalizeExpiresAt(user.expires_at);
  return normalizeOidcSessionSnapshot({
    profile: user.profile || {},
    accessToken: user.access_token,
    state: {
      status: resolveUserStatus(user, expiresAt),
      expiresAt
    }
  });
}

function normalizeAccessToken(accessToken) {
  return typeof accessToken === "string" ? accessToken.trim() : "";
}

function normalizeSourceState(state, session, accessToken) {
  const normalizedStatus = normalizeStatus(state?.status, session, accessToken);
  return {
    status: normalizedStatus,
    expiresAt: state?.expiresAt || null
  };
}

function normalizeStatus(status, session, accessToken) {
  if (status && Object.values(SESSION_STATES).includes(status)) {
    return status;
  }
  if (session.userId && accessToken) {
    return SESSION_STATES.AUTHENTICATED;
  }
  return SESSION_STATES.UNAUTHENTICATED;
}

function resolveSessionInput(input) {
  if (input.profile) {
    return mapOidcProfileToSession(input.profile);
  }
  return input.session || { userId: "", roles: [], extraAuthorities: [] };
}

function resolveRoles(profile) {
  const groupRoles = resolveRolesFromGroups(profile.groups);
  if (groupRoles.length > 0) {
    return groupRoles;
  }
  return profile.roles || [];
}

function resolveRolesFromGroups(groups) {
  const values = Array.isArray(groups) ? groups : [];
  return values
    .map((group) => OIDC_GROUP_ROLE_MAPPING[String(group).trim()])
    .filter(Boolean);
}

function normalizeExpiresAt(expiresAt) {
  if (typeof expiresAt !== "number" || !Number.isFinite(expiresAt)) {
    return null;
  }

  return new Date(expiresAt * 1000).toISOString();
}

function resolveUserStatus(user, expiresAt) {
  if (user?.expired) {
    return SESSION_STATES.EXPIRED;
  }

  if (expiresAt && Date.parse(expiresAt) <= Date.now()) {
    return SESSION_STATES.EXPIRED;
  }

  return undefined;
}
