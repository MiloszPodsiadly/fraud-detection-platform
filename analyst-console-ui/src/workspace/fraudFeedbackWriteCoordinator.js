const MAX_TRACKED_WRITES = 256;

// Lives for one WorkspaceRuntimeProvider instance. It stores only bounded write state,
// never request bodies or feedback responses; unresolved entries survive panel remounts.
export function createFraudFeedbackWriteCoordinator() {
  const writes = new Map();
  const listeners = new Set();

  return {
    getStatus(ownerKey, transactionId) {
      const key = operationKey(ownerKey, transactionId);
      return key ? writes.get(key)?.status || "idle" : "unavailable";
    },

    reserve(ownerKey, transactionId) {
      const key = operationKey(ownerKey, transactionId);
      if (!key || writes.has(key) || !makeRoom(writes)) {
        return null;
      }
      const token = {};
      writes.set(key, { status: "in-flight", token });
      notify(listeners);
      return token;
    },

    markUncertain(ownerKey, transactionId, token) {
      const key = operationKey(ownerKey, transactionId);
      const current = key ? writes.get(key) : null;
      if (!current || current.token !== token || current.status !== "in-flight") {
        return;
      }
      writes.set(key, { status: "uncertain", token: null });
      notify(listeners);
    },

    markRecorded(ownerKey, transactionId, token) {
      const key = operationKey(ownerKey, transactionId);
      if (!key) {
        return;
      }
      const current = writes.get(key);
      if (token && current?.token !== token) {
        return;
      }
      if (!current && !makeRoom(writes)) {
        return;
      }
      writes.set(key, { status: "recorded", token: null });
      notify(listeners);
    },

    release(ownerKey, transactionId, token) {
      const key = operationKey(ownerKey, transactionId);
      const current = key ? writes.get(key) : null;
      if (!current || current.token !== token || current.status !== "in-flight") {
        return;
      }
      writes.delete(key);
      notify(listeners);
    },

    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    }
  };
}

function operationKey(ownerKey, transactionId) {
  if (typeof ownerKey !== "string" || ownerKey.length === 0
      || typeof transactionId !== "string" || transactionId.length === 0) {
    return null;
  }
  return JSON.stringify([ownerKey, transactionId]);
}

function makeRoom(writes) {
  if (writes.size < MAX_TRACKED_WRITES) {
    return true;
  }
  for (const [key, state] of writes) {
    if (state.status === "recorded") {
      writes.delete(key);
      return true;
    }
  }
  return false;
}

function notify(listeners) {
  listeners.forEach((listener) => listener());
}
