const rootUsername = process.env.MONGO_INITDB_ROOT_USERNAME;
const rootPassword = process.env.MONGO_INITDB_ROOT_PASSWORD;
const migrationMode = process.env.MONGODB_AUTH_MIGRATION === "true";

const credentials = [
  ["root", rootPassword],
  ["governance_app", process.env.MONGODB_GOVERNANCE_PASSWORD],
  ["trust_authority_app", process.env.MONGODB_TRUST_AUTHORITY_PASSWORD],
  ["alert_app", process.env.MONGODB_ALERTS_PASSWORD],
  ["policy_publisher_app", process.env.MONGODB_POLICY_PUBLISHER_PASSWORD],
];

for (const [name, password] of credentials) {
  if (typeof password !== "string" || !/^[A-Za-z0-9._~-]{32,}$/.test(password)) {
    throw new Error(`${name} password must be at least 32 URL-safe characters`);
  }
}
if (!/^[A-Za-z0-9_-]+$/.test(rootUsername || "")) {
  throw new Error("MONGO_INITDB_ROOT_USERNAME must use letters, digits, underscore, or hyphen");
}
if (new Set(credentials.map(([, password]) => password)).size !== credentials.length) {
  throw new Error("MongoDB root and application passwords must be distinct");
}

const admin = db.getSiblingDB("admin");
const existingRoot = admin.getUser(rootUsername);
if (!existingRoot && !migrationMode) {
  throw new Error("MongoDB root user was not created by the official image entrypoint");
}
if (migrationMode) {
  const root = { user: rootUsername, pwd: rootPassword, roles: [{ role: "root", db: "admin" }] };
  if (existingRoot) {
    admin.updateUser(rootUsername, { pwd: rootPassword, roles: root.roles });
  } else {
    admin.createUser(root);
  }
}

function ensureDatabaseUser(databaseName, username, password) {
  const target = db.getSiblingDB(databaseName);
  const user = { user: username, pwd: password, roles: [{ role: "readWrite", db: databaseName }] };
  if (target.getUser(username)) {
    target.updateUser(username, { pwd: password, roles: user.roles });
  } else {
    target.createUser(user);
  }
}

ensureDatabaseUser("fraud_governance", "governance_app", process.env.MONGODB_GOVERNANCE_PASSWORD);
ensureDatabaseUser("fraud_trust_authority", "trust_authority_app", process.env.MONGODB_TRUST_AUTHORITY_PASSWORD);
ensureDatabaseUser("fraud_alerts", "alert_app", process.env.MONGODB_ALERTS_PASSWORD);
ensureDatabaseUser("fraud_alerts", "policy_publisher_app", process.env.MONGODB_POLICY_PUBLISHER_PASSWORD);
