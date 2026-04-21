const API_BASE_URL = import.meta.env.VITE_ALERT_API_BASE_URL ?? "";

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      "Content-Type": "application/json",
      ...options.headers
    },
    ...options
  });

  if (!response.ok) {
    const fallback = `Request failed with status ${response.status}`;
    try {
      const errorBody = await response.json();
      const details = errorBody.details || errorBody.validationErrors || [];
      const detailText = Array.isArray(details) && details.length > 0 ? ` ${details.join(" ")}` : "";
      throw new Error(`${errorBody.message || fallback}${detailText}`);
    } catch (error) {
      if (error instanceof SyntaxError) {
        throw new Error(fallback);
      }
      throw error;
    }
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

export function listAlerts() {
  return request("/api/v1/alerts");
}

export function listScoredTransactions({ page = 0, size = 25 } = {}) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size)
  });
  return request(`/api/v1/transactions/scored?${params.toString()}`);
}

export function getAlert(alertId) {
  return request(`/api/v1/alerts/${encodeURIComponent(alertId)}`);
}

export function submitAnalystDecision(alertId, decision) {
  return request(`/api/v1/alerts/${encodeURIComponent(alertId)}/decision`, {
    method: "POST",
    body: JSON.stringify(decision)
  });
}
