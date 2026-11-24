const rootUsername = process.env.MONGO_INITDB_ROOT_USERNAME;
const rootPassword = process.env.MONGO_INITDB_ROOT_PASSWORD;
const migrationMode = process.env.MONGODB_AUTH_MIGRATION === "true";
const policyPublisherRole = "policy_publisher_runtime";

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
let authenticatedForMigration = false;
let existingRoot;
try {
  existingRoot = admin.getUser(rootUsername);
} catch (error) {
  if (!migrationMode || (error?.code !== 13 && error?.codeName !== "Unauthorized")) {
    throw error;
  }
  if (admin.auth(rootUsername, rootPassword) !== 1) {
    throw new Error("MongoDB authentication is enabled, but the supplied root credentials are invalid; root password rotation is not supported by this migration");
  }
  authenticatedForMigration = true;
  existingRoot = admin.getUser(rootUsername);
}
if (!existingRoot && !migrationMode) {
  throw new Error("MongoDB root user was not created by the official image entrypoint");
}
if (migrationMode) {
  const root = { user: rootUsername, pwd: rootPassword, roles: [{ role: "root", db: "admin" }] };
  if (existingRoot) {
    if (!authenticatedForMigration) {
      admin.updateUser(rootUsername, { pwd: rootPassword, roles: root.roles });
    }
  } else {
    admin.createUser(root);
  }
}

function ensurePolicyPublisherRole() {
  const target = db.getSiblingDB("fraud_alerts");
  const privileges = [
    { resource: { db: "fraud_alerts", collection: "model_policy_publication_jobs" }, actions: ["find", "update"] },
    { resource: { db: "fraud_alerts", collection: "model_promotion_policies" }, actions: ["find", "insert", "createIndex", "listIndexes"] },
    { resource: { db: "fraud_alerts", collection: "model_promotion_policy_approval_evidence" }, actions: ["find", "insert"] },
    { resource: { db: "fraud_alerts", collection: "model_promotion_policy_publication_fences" }, actions: ["find", "insert", "update"] },
  ];
  const role = { roles: [], privileges };
  if (target.getRole(policyPublisherRole)) {
    target.updateRole(policyPublisherRole, role);
  } else {
    target.createRole({ role: policyPublisherRole, ...role });
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
ensurePolicyPublisherRole();
const publisherDb = db.getSiblingDB("fraud_alerts");
const publisher = {
  user: "policy_publisher_app",
  pwd: process.env.MONGODB_POLICY_PUBLISHER_PASSWORD,
  roles: [{ role: policyPublisherRole, db: "fraud_alerts" }],
};
if (publisherDb.getUser(publisher.user)) {
  publisherDb.updateUser(publisher.user, { pwd: publisher.pwd, roles: publisher.roles });
} else {
  publisherDb.createUser(publisher);
}
