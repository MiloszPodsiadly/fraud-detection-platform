#!/usr/bin/env bash
set -euo pipefail

count=50000
seed=7341
dimensions_path="data/generated/dimensions.json"
output_path="data/generated/canonical-replay.jsonl"
labels_output_path=""
reference_instant="2026-01-01T08:00:00Z"

normal_percentage=93
rapid_transfer_seed_percentage=2
rapid_transfer_percentage=1
new_device_percentage=1
high_proxy_percentage=1
country_mismatch_percentage=1
account_takeover_percentage=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --count) count="$2"; shift 2 ;;
    --seed) seed="$2"; shift 2 ;;
    --dimensions) dimensions_path="$2"; shift 2 ;;
    --output) output_path="$2"; shift 2 ;;
    --labels-output) labels_output_path="$2"; shift 2 ;;
    --reference-instant) reference_instant="$2"; shift 2 ;;
    --normal-percentage) normal_percentage="$2"; shift 2 ;;
    --rapid-transfer-seed-percentage) rapid_transfer_seed_percentage="$2"; shift 2 ;;
    --rapid-transfer-percentage) rapid_transfer_percentage="$2"; shift 2 ;;
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
  "$normal_percentage" "$rapid_transfer_seed_percentage" "$rapid_transfer_percentage" \
  "$new_device_percentage" "$high_proxy_percentage" "$country_mismatch_percentage" "$account_takeover_percentage" <<'PY'
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
    "rapid_transfer_seed": int(sys.argv[8]),
    "rapid_transfer_burst": int(sys.argv[9]),
    "new_device": int(sys.argv[10]),
    "high_proxy_purchase": int(sys.argv[11]),
    "country_mismatch": int(sys.argv[12]),
    "account_takeover": int(sys.argv[13]),
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
    "rapid_transfer_seed": ("LOW", False, "BASELINE_BEHAVIOUR"),
    "rapid_transfer_burst": ("HIGH", True, "RAPID_PLN_20K_BURST"),
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

    default_weights = {
        "normal": 93,
        "rapid_transfer_seed": 2,
        "rapid_transfer_burst": 1,
        "new_device": 1,
        "high_proxy_purchase": 1,
        "country_mismatch": 1,
        "account_takeover": 1,
    }
    if weights == default_weights:
        sequence = []
        block_index = 0
        while len(sequence) < total_count:
            block = ["normal"] * 993
            if block_index % 2 == 0:
                block.append("rapid_transfer_seed")
            else:
                block.append("normal")
            block.extend([
                "rapid_transfer_seed",
                "rapid_transfer_burst",
                "new_device",
                "high_proxy_purchase",
                "country_mismatch",
                "account_takeover" if block_index % 2 == 0 else "normal",
            ])
            sequence.extend(block)
            block_index += 1
        return sequence[:total_count]

    sequence = []
    for name, allocated_count in allocations.items():
        sequence.extend([name] * allocated_count)
    rng.shuffle(sequence)
    return sequence

def rapid_amount_for_index(index):
    bucket = index % 1000
    block = index // 1000
    variant = block % 4
    three_transfer_cases = [
        (7400.00, 8600.00, 6800.00),
        (7750.00, 8350.00, 7150.00),
        (6900.00, 9100.00, 7450.00),
        (8200.00, 7800.00, 7300.00),
    ]
    two_transfer_cases = [
        (11250.00, 9300.00),
        (10400.00, 10150.00),
        (9800.00, 10850.00),
        (12100.00, 8450.00),
    ]
    if block % 2 == 0:
        return three_transfer_cases[variant][max(0, min(bucket - 993, 2))]
    return two_transfer_cases[variant][0 if bucket == 994 else 1]

def amount_for(category, scenario, index):
    low, high = amount_ranges.get(category, (25, 250))
    multiplier = {
        "normal": 1.0,
        "rapid_transfer_seed": 1.0,
        "rapid_transfer_burst": 1.0,
        "new_device": 1.15,
        "high_proxy_purchase": 5.5,
        "country_mismatch": 1.25,
        "account_takeover": 6.5,
    }[scenario]
    amount = (low + rng.random() * (high - low)) * multiplier
    if scenario in {"rapid_transfer_seed", "rapid_transfer_burst"}:
        amount = rapid_amount_for_index(index)
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
        rapid_transfer = scenario in {"rapid_transfer_seed", "rapid_transfer_burst"}
        customer = customers[(index // 1000) % len(customers)] if rapid_transfer else rng.choice(customers)
        merchant = rng.choice(risk_merchants if scenario not in {"normal", "rapid_transfer_seed", "rapid_transfer_burst"} else merchants)
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

        amount = amount_for(merchant["merchantCategory"], scenario, index)
        currency = "PLN" if rapid_transfer else customer["preferredCurrency"]
        transaction_id = f"syn-txn-{index + 1}"
        if rapid_transfer:
            transaction_timestamp = reference_instant + timedelta(minutes=index // 1000, seconds=max(index % 1000 - 993, 0) * 20)
        else:
            transaction_timestamp = reference_instant + timedelta(seconds=index * 45 + rng.randrange(15))
        event = {
            "eventId": str(uuid.uuid4()),
            "transactionId": transaction_id,
            "correlationId": str(uuid.uuid4()),
            "customerId": customer["customerId"],
            "accountId": customer["accountId"],
            "paymentInstrumentId": customer["paymentInstrumentId"],
            "createdAt": datetime.now(timezone.utc).isoformat(),
            "transactionTimestamp": transaction_timestamp.isoformat(),
            "transactionAmount": {"amount": amount, "currency": currency},
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
