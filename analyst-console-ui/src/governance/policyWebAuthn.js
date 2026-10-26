function decodeBase64Url(value) {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/");
  const bytes = atob(padded.padEnd(Math.ceil(padded.length / 4) * 4, "="));
  return Uint8Array.from(bytes, (character) => character.charCodeAt(0));
}

function encodeBase64Url(buffer) {
  const bytes = new Uint8Array(buffer);
  let text = "";
  for (const byte of bytes) text += String.fromCharCode(byte);
  return btoa(text).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function creationOptions(optionsJson) {
  const { publicKey } = JSON.parse(optionsJson);
  return {
    ...publicKey,
    challenge: decodeBase64Url(publicKey.challenge),
    user: { ...publicKey.user, id: decodeBase64Url(publicKey.user.id) },
    excludeCredentials: publicKey.excludeCredentials?.map((item) => ({
      ...item, id: decodeBase64Url(item.id)
    }))
  };
}

function requestOptions(optionsJson) {
  const { publicKey } = JSON.parse(optionsJson);
  return {
    ...publicKey,
    challenge: decodeBase64Url(publicKey.challenge),
    allowCredentials: publicKey.allowCredentials?.map((item) => ({
      ...item, id: decodeBase64Url(item.id)
    }))
  };
}

function credentialJson(credential, fields) {
  return JSON.stringify({
    id: credential.id,
    rawId: encodeBase64Url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment || undefined,
    clientExtensionResults: credential.getClientExtensionResults(),
    response: Object.fromEntries(fields.map((field) => [field, credential.response[field]
      ? encodeBase64Url(credential.response[field]) : null]))
  });
}

export async function confirmPolicyAssertion(optionsJson) {
  if (!navigator.credentials?.get) throw new Error("WebAuthn is unavailable in this browser.");
  const credential = await navigator.credentials.get({ publicKey: requestOptions(optionsJson) });
  if (!credential) throw new Error("WebAuthn confirmation was cancelled.");
  return credentialJson(credential, ["authenticatorData", "clientDataJSON", "signature", "userHandle"]);
}

export async function registerPolicyCredential(optionsJson) {
  if (!navigator.credentials?.create) throw new Error("WebAuthn is unavailable in this browser.");
  const credential = await navigator.credentials.create({ publicKey: creationOptions(optionsJson) });
  if (!credential) throw new Error("WebAuthn registration was cancelled.");
  return credentialJson(credential, ["attestationObject", "clientDataJSON"]);
}
