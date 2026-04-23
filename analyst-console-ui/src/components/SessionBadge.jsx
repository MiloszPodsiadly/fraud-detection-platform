import { ROLE_AUTHORITIES, isAuthenticated } from "../auth/session.js";

const ROLE_OPTIONS = ["READ_ONLY_ANALYST", "ANALYST", "REVIEWER", "FRAUD_OPS_ADMIN"];

export function SessionBadge({ session, onSessionChange }) {
  const authenticated = isAuthenticated(session);
  const activeRole = session.roles[0] || "";
  const roleSummary = session.roles.join(", ") || "No role";

  function changeRole(event) {
    const nextRole = event.target.value;
    if (!nextRole) {
      onSessionChange({ userId: "", roles: [], extraAuthorities: [] });
      return;
    }
    onSessionChange({
      userId: session.userId || "analyst.local",
      roles: [nextRole],
      extraAuthorities: []
    });
  }

  function changeUserId(event) {
    onSessionChange({
      userId: event.target.value,
      roles: session.roles.length > 0 ? session.roles : ["READ_ONLY_ANALYST"],
      extraAuthorities: session.extraAuthorities
    });
  }

  return (
    <section className="sessionBadge" aria-label="Current session">
      <div className="sessionIdentity">
        <span className={authenticated ? "sessionDot sessionDotActive" : "sessionDot"} />
        <div>
          <p className="eyebrow">Local demo session</p>
          <strong>{authenticated ? session.userId : "Not authenticated"}</strong>
          <small>{authenticated ? `Authenticated as ${roleSummary}` : "Demo auth headers are disabled"}</small>
        </div>
        <span className={authenticated ? "sessionModePill" : "sessionModePill sessionModePillMuted"}>
          {authenticated ? "local/dev only" : "headers off"}
        </span>
      </div>

      <label>
        User
        <input value={session.userId} onChange={changeUserId} placeholder="analyst.local" />
      </label>

      <label>
        Role
        <select value={activeRole} onChange={changeRole}>
          <option value="">Unauthenticated</option>
          {ROLE_OPTIONS.map((role) => (
            <option key={role} value={role}>{role}</option>
          ))}
        </select>
      </label>

      {authenticated && (
        <div className="authorityList">
          {(ROLE_AUTHORITIES[activeRole] || session.authorities).map((authority) => (
            <span className="tag" key={authority}>{authority}</span>
          ))}
        </div>
      )}
    </section>
  );
}
