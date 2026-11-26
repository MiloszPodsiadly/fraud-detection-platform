import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const IMAGE_SECURITY_INVENTORY_PATH = fileURLToPath(new URL("./image-security-inventory.json", import.meta.url));

let inventory;
try {
  inventory = JSON.parse(fs.readFileSync(IMAGE_SECURITY_INVENTORY_PATH, "utf8"));
} catch (error) {
  throw new Error(`${IMAGE_SECURITY_INVENTORY_PATH} is missing or invalid JSON: ${error.message}`);
}

if (!inventory || typeof inventory !== "object" || inventory.schemaVersion !== 1
  || !inventory.groups || typeof inventory.groups !== "object"
  || !inventory.recipes || typeof inventory.recipes !== "object") {
  throw new Error(`${IMAGE_SECURITY_INVENTORY_PATH} does not match the supported image inventory schema.`);
}

export const IMAGE_SECURITY_INVENTORY = deepFreeze(inventory);
export const IMAGE_SECURITY_INVENTORY_RELATIVE_PATH = path.relative(process.cwd(), IMAGE_SECURITY_INVENTORY_PATH).replaceAll("\\", "/");

export function imageEntry(groupName, logicalName) {
  const group = IMAGE_SECURITY_INVENTORY.groups[groupName];
  return Array.isArray(group) ? group.find((entry) => entry.logicalName === logicalName) : undefined;
}

function deepFreeze(value) {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value)) deepFreeze(child);
  }
  return value;
}
