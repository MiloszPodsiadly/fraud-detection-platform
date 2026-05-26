#!/usr/bin/env bash
set -euo pipefail

if ! command -v openssl >/dev/null 2>&1; then
  echo "OpenSSL is required to generate local identity fixtures. Install OpenSSL and rerun this command." >&2
  exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
local_root="$repo_root/deployment/.local"
output_dir="$local_root/service-identity"

mkdir -p "$local_root"
staging_dir="$(mktemp -d "$local_root/service-identity.tmp.XXXXXX")"
mtls_dir="$staging_dir/mtls"
trap 'rm -rf "$staging_dir"' EXIT

umask 077
mkdir -p "$mtls_dir"

cat > "$staging_dir/ca.cnf" <<'EOF'
[req]
distinguished_name = dn
prompt = no
x509_extensions = v3_ca

[dn]
CN = fraud-platform-local-dev-ca

[v3_ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,digitalSignature,keyCertSign,cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer
EOF

openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 365 \
  -config "$staging_dir/ca.cnf" \
  -keyout "$mtls_dir/local-dev-ca-key.pem" \
  -out "$mtls_dir/local-dev-ca.pem" >/dev/null 2>&1

create_certificate() {
  local service_name="$1"
  local usage="$2"
  local sans="$3"
  local days="$4"
  local config="$staging_dir/$service_name.cnf"

  cat > "$config" <<EOF
[req]
distinguished_name = dn
prompt = no
req_extensions = v3_req

[dn]
CN = $service_name

[v3_req]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = $usage
subjectAltName = $sans
EOF

  openssl req -new -newkey rsa:2048 -nodes -sha256 \
    -config "$config" \
    -keyout "$mtls_dir/$service_name-key.pem" \
    -out "$staging_dir/$service_name.csr" >/dev/null 2>&1
  openssl x509 -req -sha256 -days "$days" \
    -in "$staging_dir/$service_name.csr" \
    -CA "$mtls_dir/local-dev-ca.pem" \
    -CAkey "$mtls_dir/local-dev-ca-key.pem" \
    -CAcreateserial \
    -extfile "$config" \
    -extensions v3_req \
    -out "$mtls_dir/$service_name.pem" >/dev/null 2>&1
}

create_certificate "ml-inference-service" "serverAuth" \
  "DNS:ml-inference-service,DNS:localhost,IP:127.0.0.1,URI:spiffe://fraud-platform/ml-inference-service" 365
create_certificate "alert-service" "clientAuth" \
  "DNS:alert-service,URI:spiffe://fraud-platform/alert-service" 365
create_certificate "fraud-scoring-service" "clientAuth" \
  "DNS:fraud-scoring-service,URI:spiffe://fraud-platform/fraud-scoring-service" 365
create_certificate "unknown-service" "clientAuth" \
  "DNS:unknown-service,URI:spiffe://fraud-platform/unknown-service" 365
create_certificate "expired-service" "clientAuth" \
  "DNS:expired-service,URI:spiffe://fraud-platform/expired-service" 0

for service_name in fraud-scoring-service alert-service; do
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "$staging_dir/$service_name-private.pem" >/dev/null 2>&1
  openssl pkey -in "$staging_dir/$service_name-private.pem" -pubout \
    -out "$staging_dir/$service_name-public.pem" >/dev/null 2>&1
done

rsa_modulus_base64url() {
  local private_key="$1"
  local hex
  local binary_file="$staging_dir/modulus.bin"
  hex="$(openssl rsa -in "$private_key" -noout -modulus 2>/dev/null)"
  hex="${hex#Modulus=}"
  : > "$binary_file"
  while [[ -n "$hex" ]]; do
    printf '%b' "\\x${hex:0:2}" >> "$binary_file"
    hex="${hex:2}"
  done
  openssl base64 -A -in "$binary_file" | tr '+/' '-_' | tr -d '='
}

scoring_modulus="$(rsa_modulus_base64url "$staging_dir/fraud-scoring-service-private.pem")"
alert_modulus="$(rsa_modulus_base64url "$staging_dir/alert-service-private.pem")"
cat > "$staging_dir/jwks.json" <<EOF
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "scoring-key-1",
      "n": "$scoring_modulus",
      "e": "AQAB"
    },
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "alert-key-1",
      "n": "$alert_modulus",
      "e": "AQAB"
    }
  ]
}
EOF

rm -f "$staging_dir"/*.cnf "$staging_dir"/*.csr "$staging_dir/modulus.bin" "$mtls_dir/local-dev-ca.srl"
# Linux bind mounts preserve host modes, while application containers run as
# fixed non-root UIDs distinct from the user that generates these local fixtures.
chmod 755 "$staging_dir" "$mtls_dir"
chmod 444 "$staging_dir"/*.pem "$staging_dir/jwks.json" "$mtls_dir"/*.pem

rm -rf "$output_dir"
mv "$staging_dir" "$output_dir"
trap - EXIT

echo "Generated local-only identity fixtures in deployment/.local/service-identity/."
echo "This directory is ignored by Git and excluded from Docker build contexts."
