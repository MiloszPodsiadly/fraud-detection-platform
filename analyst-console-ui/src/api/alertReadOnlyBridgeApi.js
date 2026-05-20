export function createAlertReadOnlyBridgeApiClient(apiClient) {
  if (!apiClient || typeof apiClient.getAlert !== "function") {
    return null;
  }

  return Object.freeze({
    getAlert: (alertId, requestOptions) => apiClient.getAlert(alertId, requestOptions)
  });
}
