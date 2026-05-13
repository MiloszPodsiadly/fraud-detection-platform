export function WorkQueueErrorPanel({ refTarget, error, invalidCursor, onRetry }) {
  const message = messageForWorkQueueError(error, invalidCursor);
  const isAlert = error?.status === 403 || error?.status === 503 || invalidCursor;
  const buttonLabel = invalidCursor ? "Refresh from first slice" : "Try again";
  return (
    <div
      className={error?.status === 401 ? "statePanel workQueueSessionPanel" : "statePanel errorPanel"}
      role={isAlert ? "alert" : undefined}
      tabIndex={-1}
      ref={refTarget}
    >
      <h3>{message.title}</h3>
      <p>{message.body}</p>
      <button
        className="secondaryButton"
        type="button"
        onClick={onRetry}
        aria-label={invalidCursor ? "Refresh fraud case work queue from first slice" : "Retry fraud case work queue"}
      >
        {buttonLabel}
      </button>
    </div>
  );
}

function messageForWorkQueueError(error, invalidCursor) {
  if (error?.status === 401) {
    return {
      title: "Session required",
      body: "No analyst session is currently active. Sign in with the configured provider, then retry this workspace."
    };
  }
  if (error?.status === 403) {
    return { title: "Access denied", body: "Your session does not include FRAUD_CASE_READ." };
  }
  if (error?.status === 503) {
    return { title: "Work queue temporarily unavailable", body: "Sensitive-read audit is fail-closed. Try again after the service recovers." };
  }
  if (invalidCursor) {
    return { title: "Queue position expired", body: "Refresh from the first slice to continue with the latest results." };
  }
  if (error?.status === 400) {
    return { title: "Invalid work queue request", body: error.message || "Adjust the filter or sort selection and retry." };
  }
  return { title: "Unable to load work queue", body: error?.message || "Network error while loading the fraud case work queue." };
}
