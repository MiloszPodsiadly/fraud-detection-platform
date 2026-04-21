#!/usr/bin/env bash
set -euo pipefail

count=10000
seed=7341
dimensions_path="data/generated/dimensions.json"
output_path="data/generated/canonical-replay.jsonl"
labels_output_path=""
reference_instant="2026-01-01T08:00:00Z"

normal_percentage=80
new_device_percentage=10
high_proxy_percentage=7
country_mismatch_percentage=1
account_takeover_percentage=2

while [[ $# -gt 0 ]]; do
  case "$1" in
    --count) count="$2"; shift 2 ;;
    --seed) seed="$2"; shift 2 ;;
    --dimensions) dimensions_path="$2"; shift 2 ;;
    --output) output_path="$2"; shift 2 ;;
    --labels-output) labels_output_path="$2"; shift 2 ;;
    --reference-instant) reference_instant="$2"; shift 2 ;;
    --normal-percentage) normal_percentage="$2"; shift 2 ;;
    --new-device-percentage) new_device_percentage="$2"; shift 2 ;;
    --high-proxy-percentage) high_proxy_percentage="$2"; shift 2 ;;
    --country-mismatch-percentage) country_mismatch_percentage="$2"; shift 2 ;;
    --account-takeover-percentage) account_takeover_percentage="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ ! -f "$dimensions_path" ]]; then
  "$(dirname "$0")/generate-dimensions.sh" --seed "$seed" --output "$dimensions_path"
fi

python_bin="${PYTHON:-}"
if [[ -z "$python_bin" ]]; then
  if command -v python3 >/dev/null 2>&1 && python3 -c "import sys" >/dev/null 2>&1; then
    python_bin="python3"
  else
    python_bin="python"
  fi
fi

"$python_bin" - "$count" "$seed" "$dimensions_path" "$output_path" "$labels_output_path" "$reference_instant" \
  "$normal_percentage" "$new_device_percentage" "$high_proxy_percentage" "$country_mismatch_percentage" "$account_takeover_percentage" <<'PY'
import csv
import json
import random
import sys
import uuid
from datetime import datetime, timezone, timedelta
from pathlib import Path

count = int(sys.argv[1])
seed = int(sys.argv[2])
dimensions_path = Path(sys.argv[3])
output_path = Path(sys.argv[4])
labels_output_path = Path(sys.argv[5]) if sys.argv[5] else None
reference_instant = datetime.fromisoformat(sys.argv[6].replace("Z", "+00:00"))
weights = {
    "normal": int(sys.argv[7]),
    "new_device": int(sys.argv[8]),
    "high_proxy_purchase": int(sys.argv[9]),
    "country_mismatch": int(sys.argv[10]),
    "account_takeover": int(sys.argv[11]),
}
if any(value < 0 for value in weights.values()) or sum(weights.values()) != 100:
    raise SystemExit(f"Scenario percentages must be non-negative and sum to 100. Current sum: {sum(weights.values())}")

rng = random.Random(seed)
dimensions = json.loads(dimensions_path.read_text(encoding="utf-8"))
customers = dimensions["customers"]
merchants = dimensions["merchants"]
high_risk_countries = ["BR", "NG", "RO", "MX"]
risk_merchants = [merchant for merchant in merchants if merchant["riskTier"] == "HIGH"] or merchants

amount_ranges = {
    "Groceries": (18, 140), "Fuel": (35, 120), "Retail": (25, 240),
    "Travel": (120, 720), "Electronics": (90, 950), "Digital Goods": (40, 400),
    "Gift Cards": (50, 300), "Crypto Exchange": (150, 1200), "Remote Services": (60, 500),
}
scenario_meta = {
    "normal": ("LOW", False, "BASELINE_BEHAVIOUR"),
    "new_device": ("MEDIUM", False, "DEVICE_NOVELTY|PROXY_OR_VPN"),
    "high_proxy_purchase": ("HIGH", True, "DEVICE_NOVELTY|PROXY_OR_VPN|HIGH_TRANSACTION_AMOUNT"),
    "country_mismatch": ("MEDIUM", False, "COUNTRY_MISMATCH|DEVICE_NOVELTY"),
    "account_takeover": ("CRITICAL", True, "ACCOUNT_TAKEOVER_SIGNAL|DEVICE_NOVELTY|COUNTRY_MISMATCH|PROXY_OR_VPN"),
}

def build_scenario_sequence(total_count):
    allocations = {}
    fractions = []
    allocated = 0
    for name, weight in weights.items():
        exact = total_count * weight / 100
        whole = int(exact)
        allocations[name] = whole
        allocated += whole
        fractions.append((exact - whole, name))

    for _, name in sorted(fractions, reverse=True)[:total_count - allocated]:
        allocations[name] += 1

    sequence = []
    for name, allocated_count in allocations.items():
        sequence.extend([name] * allocated_count)
    rng.shuffle(sequence)
    return sequence

def amount_for(category, scenario):
    low, high = amount_ranges.get(category, (25, 250))
    multiplier = {
        "normal": 1.0,
        "new_device": 1.15,
        "high_proxy_purchase": 5.5,
        "country_mismatch": 1.25,
        "account_takeover": 6.5,
    }[scenario]
    amount = (low + rng.random() * (high - low)) * multiplier
    if scenario in {"new_device", "country_mismatch"}:
        amount = min(amount, 940 + rng.random() * 40)
    if scenario in {"high_proxy_purchase", "account_takeover"}:
        amount = max(amount, 1100 + rng.random() * 450)
    return round(amount, 2)

output_path.parent.mkdir(parents=True, exist_ok=True)
if labels_output_path:
    labels_output_path.parent.mkdir(parents=True, exist_ok=True)

label_rows = []
scenario_sequence = build_scenario_sequence(count)
with output_path.open("w", encoding="utf-8", newline="\n") as out:
    for index in range(count):
        scenario = scenario_sequence[index]
        risk_level, expected_fraud, reason_codes = scenario_meta[scenario]
        customer = rng.choice(customers)
        merchant = rng.choice(risk_merchants if scenario != "normal" else merchants)
        home = customer["homeLocation"]
        known_devices = customer["knownDeviceIds"]
        home_country = customer["homeCountryCode"]
        country_code = home_country
        high_risk_country = False
        trusted_device = True
        proxy = False
        vpn = False
        card_present = merchant["defaultChannel"] == "POS" and scenario == "normal"
        channel = "POS" if card_present else "ECOMMERCE"
        device_id = rng.choice(known_devices)
        segment = customer["segment"]
        email_verified = customer["emailVerified"]
        phone_verified = customer["phoneVerified"]
        account_age_days = customer["accountAgeDays"]
        location = dict(home)
        timezone_name = customer["timezone"]

        if scenario in {"new_device", "high_proxy_purchase", "country_mismatch", "account_takeover"}:
            device_id = f"{customer['customerId'].replace('syn-customer-', 'syn-device-')}-new-{index + 1}"
            trusted_device = False
        if scenario in {"new_device", "high_proxy_purchase", "account_takeover"}:
            proxy = True
            vpn = scenario == "account_takeover" or (scenario == "high_proxy_purchase" and rng.random() >= 0.4)
        if scenario in {"country_mismatch", "account_takeover"}:
            country_code = rng.choice(high_risk_countries)
            high_risk_country = True
            location = {"region": "Risk Region", "city": "Risk City", "postalCode": "00000", "latitude": 0.0, "longitude": 0.0}
            timezone_name = "UTC"
        if scenario == "account_takeover":
            segment = "WATCHLIST"
            email_verified = False
            phone_verified = False
            account_age_days = max(account_age_days - 40, 5)

        amount = amount_for(merchant["merchantCategory"], scenario)
        transaction_id = f"syn-txn-{index + 1}"
        event = {
            "eventId": str(uuid.uuid4()),
            "transactionId": transaction_id,
            "correlationId": str(uuid.uuid4()),
            "customerId": customer["customerId"],
            "accountId": customer["accountId"],
            "paymentInstrumentId": customer["paymentInstrumentId"],
            "createdAt": datetime.now(timezone.utc).isoformat(),
            "transactionTimestamp": (reference_instant + timedelta(seconds=index * 45 + rng.randrange(15))).isoformat(),
            "transactionAmount": {"amount": amount, "currency": customer["preferredCurrency"]},
            "merchantInfo": {
                "merchantId": merchant["merchantId"],
                "merchantName": merchant["merchantName"],
                "merchantCategoryCode": merchant["merchantCategoryCode"],
                "merchantCategory": merchant["merchantCategory"],
                "acquiringCountryCode": country_code,
                "channel": channel,
                "cardPresent": card_present,
                "attributes": {"synthetic": True, "scenario": scenario, "riskTier": merchant["riskTier"]},
            },
            "deviceInfo": {
                "deviceId": device_id,
                "fingerprint": f"syn-fp-{customer['customerId']}-{index + 1}",
                "ipAddress": f"{'198.51.100' if proxy or high_risk_country else '203.0.113'}.{1 + rng.randrange(200)}",
                "userAgent": "Mozilla/5.0 Synthetic Risk Browser" if scenario != "normal" else "Mozilla/5.0 Synthetic Stable Browser",
                "platform": rng.choice(["IOS", "ANDROID", "WEB"]),
                "browser": "MOBILE_WEBVIEW" if scenario != "normal" else "CHROME",
                "trustedDevice": trusted_device,
                "proxyDetected": proxy,
                "vpnDetected": vpn,
                "attributes": {"synthetic": True, "scenario": scenario, "deviceAgeDays": 0 if not trusted_device else 180},
            },
            "locationInfo": {
                "countryCode": country_code,
                "region": location["region"],
                "city": location["city"],
                "postalCode": location["postalCode"],
                "latitude": location["latitude"],
                "longitude": location["longitude"],
                "timezone": timezone_name,
                "highRiskCountry": high_risk_country,
            },
            "customerContext": {
                "customerId": customer["customerId"],
                "accountId": customer["accountId"],
                "segment": segment,
                "emailDomain": customer["emailDomain"],
                "accountAgeDays": account_age_days,
                "emailVerified": email_verified,
                "phoneVerified": phone_verified,
                "homeCountryCode": home_country,
                "preferredCurrency": customer["preferredCurrency"],
                "knownDeviceIds": known_devices,
                "attributes": {"synthetic": True, "scenario": scenario, "expectedRiskLevel": risk_level},
            },
            "transactionType": "CARD_PURCHASE" if card_present else "CARD_NOT_PRESENT_PURCHASE",
            "authorizationMethod": "3DS" if scenario == "normal" else "STEP_UP_CHALLENGE",
            "sourceSystem": "BASH_JSONL_GENERATOR",
            "traceId": f"trace-syn-{index + 1}",
            "attributes": {
                "generator": "bash-canonical-replay",
                "seed": seed,
                "scenario": scenario,
                "suspicious": expected_fraud,
                "expectedRiskLevel": risk_level,
                "expectedReasonCodes": reason_codes.split("|"),
            },
        }
        out.write(json.dumps(event, separators=(",", ":")) + "\n")
        label_rows.append([transaction_id, scenario, str(expected_fraud).lower(), risk_level, reason_codes])

if labels_output_path:
    with labels_output_path.open("w", encoding="utf-8", newline="") as labels:
        writer = csv.writer(labels)
        writer.writerow(["transactionId", "scenario", "expectedFraud", "expectedRiskLevel", "expectedReasonCodes"])
        writer.writerows(label_rows)

print(f"Generated {count} canonical replay events at {output_path}")
if labels_output_path:
    print(f"Generated labels at {labels_output_path}")
PY
