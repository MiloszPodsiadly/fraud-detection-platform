export function DetailStateBanner({ state, message, onRetry, retryLabel }) {
  if (!state || state === "loaded") {
    return null;
  }

  const alertStates = new Set(["stale", "unavailable", "access-denied"]);
  const titleByState = {
    loading: "Loading detail.",
    stale: "Detail refresh failed. Showing last loaded data.",
    degraded: "Detail is degraded.",
    unavailable: "Detail unavailable.",
    "access-denied": "Detail access denied.",
    "runtime-not-ready": "Detail runtime is not ready."
  };

  return (
    <div
      className={state === "stale" || state === "degraded" ? "statePanel warningPanel" : "statePanel errorPanel"}
      role={alertStates.has(state) ? "alert" : "status"}
      aria-live={alertStates.has(state) ? "assertive" : "polite"}
    >
      <h3>{titleByState[state] || "Detail state changed."}</h3>
      {message && <p>{message}</p>}
      {typeof onRetry === "function" && (
        <button className="secondaryButton" type="button" onClick={onRetry} aria-label={retryLabel || "Retry detail load"}>
          Retry
        </button>
      )}
    </div>
  );
}
