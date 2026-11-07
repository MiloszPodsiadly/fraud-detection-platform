const MAX_MERCHANT_DISPLAY_LENGTH = 128;
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f-\u009f]/u;
const BIDI_CONTROLS = /[\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]/u;

export function merchantDisplayValue(merchantInfo, field) {
  if (merchantInfo === null || typeof merchantInfo !== "object" || Array.isArray(merchantInfo)) {
    return null;
  }
  const prototype = Object.getPrototypeOf(merchantInfo);
  if ((prototype !== Object.prototype && prototype !== null)
    || !Object.prototype.hasOwnProperty.call(merchantInfo, field)) {
    return null;
  }
  const value = merchantInfo[field];
  if (typeof value !== "string" || value.length === 0 || value.length > MAX_MERCHANT_DISPLAY_LENGTH
    || CONTROL_CHARACTERS.test(value) || BIDI_CONTROLS.test(value)) {
    return null;
  }
  if (field === "merchantName") {
    const normalizedName = value.trim();
    return normalizedName.length > 0 ? normalizedName : null;
  }
  return value.trim() === value ? value : null;
}
