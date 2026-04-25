const DEFAULT_SCOPE = "openid profile email";
const CALLBACK_PATH = "/auth/callback";

export function createOidcClientSettings(env = import.meta.env) {
  return {
    authority: normalizeEnv(env?.VITE_OIDC_AUTHORITY),
    client_id: normalizeEnv(env?.VITE_OIDC_CLIENT_ID),
    redirect_uri: normalizeEnv(env?.VITE_OIDC_REDIRECT_URI),
    post_logout_redirect_uri: normalizeEnv(env?.VITE_OIDC_POST_LOGOUT_REDIRECT_URI),
    scope: normalizeEnv(env?.VITE_OIDC_SCOPE) || DEFAULT_SCOPE,
    response_type: "code"
  };
}

export function isOidcCallbackPath(pathname = window.location.pathname) {
  return pathname === CALLBACK_PATH;
}

export function createOidcClient({
  settings = createOidcClientSettings(),
  loadOidcSdk = defaultLoadOidcSdk
} = {}) {
  let userManagerPromise = null;

  return {
    hasConfiguration() {
      return Boolean(settings.authority && settings.client_id && settings.redirect_uri);
    },
    async getUser() {
      const userManager = await getUserManager();
      return userManager.getUser();
    },
    async beginLogin() {
      const userManager = await getUserManager();
      if (typeof userManager.removeUser === "function") {
        await userManager.removeUser();
      }
      return userManager.signinRedirect();
    },
    async completeLoginCallback() {
      const userManager = await getUserManager();
      return userManager.signinRedirectCallback();
    },
    async beginLogout() {
      const userManager = await getUserManager();
      if (typeof userManager.removeUser === "function") {
        await userManager.removeUser();
      }
      return userManager.signoutRedirect({
        client_id: settings.client_id,
        post_logout_redirect_uri: settings.post_logout_redirect_uri
      });
    }
  };

  async function getUserManager() {
    if (!userManagerPromise) {
      userManagerPromise = loadOidcSdk().then(({ UserManager, WebStorageStateStore }) => {
        if (!settings.authority || !settings.client_id || !settings.redirect_uri) {
          throw new Error("OIDC client is missing authority, client_id, or redirect_uri configuration.");
        }

        return new UserManager({
          ...settings,
          userStore: createUserStore(WebStorageStateStore)
        });
      });
    }

    return userManagerPromise;
  }
}

async function defaultLoadOidcSdk() {
  return import("oidc-client-ts");
}

function createUserStore(WebStorageStateStore) {
  if (typeof window === "undefined" || !window.sessionStorage) {
    return undefined;
  }

  return new WebStorageStateStore({ store: window.sessionStorage });
}

function normalizeEnv(value) {
  return typeof value === "string" ? value.trim() : "";
}
