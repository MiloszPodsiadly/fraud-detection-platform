import { describe, expect, it, vi } from "vitest";
import { createOidcClient, createOidcClientSettings, isOidcCallbackPath } from "./oidcClient.js";

describe("oidcClient", () => {
  it("builds code-flow settings from Vite env values", () => {
    expect(createOidcClientSettings({
      VITE_OIDC_AUTHORITY: " http://localhost:8086/realms/fraud-detection ",
      VITE_OIDC_CLIENT_ID: " analyst-console-ui ",
      VITE_OIDC_REDIRECT_URI: " http://localhost:5173/auth/callback ",
      VITE_OIDC_POST_LOGOUT_REDIRECT_URI: " http://localhost:5173/ "
    })).toEqual({
      authority: "http://localhost:8086/realms/fraud-detection",
      client_id: "analyst-console-ui",
      redirect_uri: "http://localhost:5173/auth/callback",
      post_logout_redirect_uri: "http://localhost:5173/",
      scope: "openid profile email",
      response_type: "code"
    });
  });

  it("uses oidc-client-ts through a lazy adapter boundary", async () => {
    const signinRedirect = vi.fn().mockResolvedValue(undefined);
    const getUser = vi.fn().mockResolvedValue({ id_token: "id-token-1", profile: { sub: "user-1" } });
    const signinRedirectCallback = vi.fn().mockResolvedValue({ profile: { sub: "user-1" } });
    const signoutRedirect = vi.fn().mockResolvedValue(undefined);
    const removeUser = vi.fn().mockResolvedValue(undefined);
    const userManagerFactory = vi.fn(function UserManager(settings) {
      this.settings = settings;
      this.getUser = getUser;
      this.signinRedirect = signinRedirect;
      this.signinRedirectCallback = signinRedirectCallback;
      this.signoutRedirect = signoutRedirect;
      this.removeUser = removeUser;
    });
    const stateStoreFactory = vi.fn(function WebStorageStateStore({ store }) {
      this.store = store;
    });

    const oidcClient = createOidcClient({
      settings: {
        authority: "http://localhost:8086/realms/fraud-detection",
        client_id: "analyst-console-ui",
        redirect_uri: "http://localhost:5173/auth/callback",
        post_logout_redirect_uri: "http://localhost:5173/",
        scope: "openid profile email",
        response_type: "code"
      },
      loadOidcSdk: async () => ({
        UserManager: userManagerFactory,
        WebStorageStateStore: stateStoreFactory
      })
    });

    expect(oidcClient.hasConfiguration()).toBe(true);
    await oidcClient.getUser();
    await oidcClient.beginLogin();
    await oidcClient.completeLoginCallback();
    await oidcClient.beginLogout();

    expect(userManagerFactory).toHaveBeenCalledTimes(1);
    expect(userManagerFactory).toHaveBeenCalledWith(expect.objectContaining({
      authority: "http://localhost:8086/realms/fraud-detection",
      client_id: "analyst-console-ui",
      redirect_uri: "http://localhost:5173/auth/callback",
      response_type: "code"
    }));
    expect(stateStoreFactory).toHaveBeenCalledWith({ store: window.sessionStorage });
    expect(getUser).toHaveBeenCalledTimes(2);
    expect(removeUser).toHaveBeenCalledTimes(1);
    expect(signinRedirect).toHaveBeenCalledTimes(1);
    expect(signinRedirectCallback).toHaveBeenCalledTimes(1);
    expect(signoutRedirect).toHaveBeenCalledTimes(1);
    expect(signoutRedirect).toHaveBeenCalledWith({
      client_id: "analyst-console-ui",
      id_token_hint: "id-token-1",
      post_logout_redirect_uri: "http://localhost:5173/"
    });
  });

  it("recognizes the dedicated callback path", () => {
    expect(isOidcCallbackPath("/auth/callback")).toBe(true);
    expect(isOidcCallbackPath("/")).toBe(false);
  });
});
