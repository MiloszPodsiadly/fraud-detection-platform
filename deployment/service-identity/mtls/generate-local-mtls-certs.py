from __future__ import annotations

from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID


OUTPUT_DIR = Path(__file__).resolve().parent
TRUST_DOMAIN = "fraud-platform"


def private_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def write_private_key(path: Path, key) -> None:
    path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )


def write_certificate(path: Path, certificate: x509.Certificate) -> None:
    path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))


def name(common_name: str) -> x509.Name:
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common_name)])


def certificate_builder(subject: x509.Name, issuer: x509.Name, public_key, serial: int) -> x509.CertificateBuilder:
    now = datetime.now(timezone.utc).replace(microsecond=0)
    return (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(public_key)
        .serial_number(serial)
        .not_valid_before(now - timedelta(minutes=5))
        .not_valid_after(now + timedelta(days=365))
    )


def create_ca():
    key = private_key()
    subject = name("fraud-platform-local-dev-ca")
    certificate = (
        certificate_builder(subject, subject, key.public_key(), 1000)
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .add_extension(x509.KeyUsage(
            digital_signature=True,
            key_cert_sign=True,
            crl_sign=True,
            key_encipherment=False,
            data_encipherment=False,
            key_agreement=False,
            content_commitment=False,
            encipher_only=False,
            decipher_only=False,
        ), critical=True)
        .sign(key, hashes.SHA256())
    )
    return key, certificate


def create_server_certificate(ca_key, ca_certificate):
    key = private_key()
    certificate = (
        certificate_builder(name("ml-inference-service"), ca_certificate.subject, key.public_key(), 2000)
        .add_extension(
            x509.SubjectAlternativeName([
                x509.DNSName("ml-inference-service"),
                x509.DNSName("localhost"),
            ]),
            critical=False,
        )
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(ca_key, hashes.SHA256())
    )
    return key, certificate


def create_client_certificate(ca_key, ca_certificate, service_name: str, serial: int, expired: bool = False):
    key = private_key()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    builder = (
        x509.CertificateBuilder()
        .subject_name(name(service_name))
        .issuer_name(ca_certificate.subject)
        .public_key(key.public_key())
        .serial_number(serial)
        .not_valid_before(now - timedelta(days=2) if expired else now - timedelta(minutes=5))
        .not_valid_after(now - timedelta(days=1) if expired else now + timedelta(days=365))
        .add_extension(
            x509.SubjectAlternativeName([
                x509.UniformResourceIdentifier(f"spiffe://{TRUST_DOMAIN}/{service_name}"),
            ]),
            critical=False,
        )
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
    )
    return key, builder.sign(ca_key, hashes.SHA256())


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    ca_key, ca_certificate = create_ca()
    write_private_key(OUTPUT_DIR / "local-dev-ca-key.pem", ca_key)
    write_certificate(OUTPUT_DIR / "local-dev-ca.pem", ca_certificate)

    server_key, server_certificate = create_server_certificate(ca_key, ca_certificate)
    write_private_key(OUTPUT_DIR / "ml-inference-service-key.pem", server_key)
    write_certificate(OUTPUT_DIR / "ml-inference-service.pem", server_certificate)

    for service_name, serial, expired in (
        ("fraud-scoring-service", 3000, False),
        ("alert-service", 3001, False),
        ("unknown-service", 3002, False),
        ("expired-service", 3003, True),
    ):
        key, certificate = create_client_certificate(ca_key, ca_certificate, service_name, serial, expired)
        write_private_key(OUTPUT_DIR / f"{service_name}-key.pem", key)
        write_certificate(OUTPUT_DIR / f"{service_name}.pem", certificate)


if __name__ == "__main__":
    main()
