#!/usr/bin/env bash
set -euo pipefail

customer_count=1000
merchant_count=240
seed=7341
output_path="data/generated/dimensions.json"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --customer-count) customer_count="$2"; shift 2 ;;
    --merchant-count) merchant_count="$2"; shift 2 ;;
    --seed) seed="$2"; shift 2 ;;
    --output) output_path="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

python_bin="${PYTHON:-}"
if [[ -z "$python_bin" ]]; then
  if command -v python3 >/dev/null 2>&1 && python3 -c "import sys" >/dev/null 2>&1; then
    python_bin="python3"
  else
    python_bin="python"
  fi
fi

"$python_bin" - "$customer_count" "$merchant_count" "$seed" "$output_path" <<'PY'
import json
import random
import sys
from datetime import datetime, timezone
from pathlib import Path

customer_count = int(sys.argv[1])
merchant_count = int(sys.argv[2])
seed = int(sys.argv[3])
output_path = Path(sys.argv[4])

rng = random.Random(seed)
countries = [
    ("US", "USD", "America/New_York", "New York", "New York", "10001", 40.7128, -74.0060),
    ("GB", "GBP", "Europe/London", "England", "London", "EC1A", 51.5074, -0.1278),
    ("DE", "EUR", "Europe/Berlin", "Berlin", "Berlin", "10115", 52.5200, 13.4050),
    ("PL", "PLN", "Europe/Warsaw", "Mazowieckie", "Warsaw", "00-001", 52.2297, 21.0122),
    ("FR", "EUR", "Europe/Paris", "Ile-de-France", "Paris", "75001", 48.8566, 2.3522),
    ("NL", "EUR", "Europe/Amsterdam", "North Holland", "Amsterdam", "1012", 52.3676, 4.9041),
]
segments = ["STANDARD", "AFFLUENT", "SMB", "DIGITAL_FIRST"]
email_domains = ["customer.example", "trustedmail.example", "securepay.example", "banking.example"]
merchant_catalog = [
    ("grocery", "Trusted Grocer", "5411", "Groceries", "POS", "LOW"),
    ("fuel", "Metro Fuel", "5541", "Fuel", "POS", "LOW"),
    ("retail", "Urban Retail", "5311", "Retail", "POS", "LOW"),
    ("travel", "Skyward Travel", "4511", "Travel", "ECOMMERCE", "MEDIUM"),
    ("electronics", "Device Center", "5732", "Electronics", "ECOMMERCE", "MEDIUM"),
    ("digital", "Digital Vault", "5815", "Digital Goods", "ECOMMERCE", "HIGH"),
    ("giftcard", "Gift Card Hub", "5947", "Gift Cards", "ECOMMERCE", "HIGH"),
    ("crypto", "Crypto Exchange", "6051", "Crypto Exchange", "ECOMMERCE", "HIGH"),
    ("remote", "Remote Services", "7399", "Remote Services", "ECOMMERCE", "HIGH"),
]

customers = []
for i in range(1, customer_count + 1):
    code, currency, tz, region, city, postal, lat, lon = countries[(i - 1) % len(countries)]
    known_devices = [f"syn-device-{i}-{d}" for d in range(1, 2 + rng.randint(0, 1))]
    customers.append({
        "customerId": f"syn-customer-{i}",
        "accountId": f"syn-account-{i}",
        "paymentInstrumentId": f"syn-card-{i}",
        "homeCountryCode": code,
        "preferredCurrency": currency,
        "timezone": tz,
        "segment": rng.choice(segments),
        "emailDomain": rng.choice(email_domains),
        "accountAgeDays": 60 + rng.randint(0, 1800),
        "emailVerified": rng.random() >= 0.05,
        "phoneVerified": rng.random() >= 0.08,
        "knownDeviceIds": known_devices,
        "homeLocation": {
            "region": region,
            "city": city,
            "postalCode": postal,
            "latitude": lat,
            "longitude": lon,
        },
    })

merchants = []
for i in range(1, merchant_count + 1):
    _, name, mcc, category, channel, risk_tier = merchant_catalog[(i - 1) % len(merchant_catalog)]
    country = rng.choice(countries)
    merchants.append({
        "merchantId": f"syn-merchant-{i}",
        "merchantName": f"{name} {i}",
        "merchantCategoryCode": mcc,
        "merchantCategory": category,
        "acquiringCountryCode": country[0],
        "defaultChannel": channel,
        "riskTier": risk_tier,
        "cardPresentDefault": channel == "POS",
    })

output_path.parent.mkdir(parents=True, exist_ok=True)
output_path.write_text(json.dumps({
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "seed": seed,
    "customers": customers,
    "merchants": merchants,
}, indent=2), encoding="utf-8")
print(f"Generated dimensions at {output_path}")
PY
