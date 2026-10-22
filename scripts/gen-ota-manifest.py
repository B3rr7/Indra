#!/usr/bin/env python3
"""Generate a signed OTA manifest for Indra's native core binaries.

Envelope contract (what OtaUpdateManager expects):
{
  "payload":   "<manifest-json>",
  "signature": "<base64(SHA256withRSA(payload-bytes))>"
}

Entry file format (JSON array); "url" falls back to <cdn>/<name>-<abi>:
[
  {
    "name": "nmap",
    "version": "6.46",
    "armeabi-v7a": {"file": "dist/bin/nmap-armeabi-v7a"},
    "arm64-v8a": null
  }
]

Usage:
  python3 scripts/gen-ota-manifest.py \
      --core-version 1.0.2 \
      --cdn https://cdn.example.com/indra \
      --entries entries.json \
      [--key release-private.pem] \
      --out manifest.json
"""

import argparse
import base64
import hashlib
import json
import subprocess
import sys
from pathlib import Path


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_payload(core_version: str, cdn: str, entries: list) -> dict:
    binaries = []
    for entry in entries:
        binary = {
            "name": entry["name"],
            "version": entry["version"],
        }
        for abi in ("armeabi-v7a", "arm64-v8a"):
            spec = entry.get(abi)
            if not spec:
                continue
            src = Path(spec["file"])
            if not src.is_file():
                sys.exit(f"error: artifact file not found: {src}")
            binary[abi] = {
                "url": spec.get("url", f"{cdn.rstrip('/')}/{entry['name']}-{abi}"),
                "sha256": sha256_of(src),
            }
        binaries.append(binary)
    return {"version": core_version, "binaries": binaries}


def sign_payload(payload: str, key_path: str) -> str:
    payload_bin = Path("/tmp/opencode/ota_payload.bin")
    payload_bin.parent.mkdir(parents=True, exist_ok=True)
    payload_bin.write_bytes(payload.encode("utf-8"))
    sig_bin = Path("/tmp/opencode/ota_signature.bin")
    subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", key_path, "-out", str(sig_bin), str(payload_bin)],
        check=True,
    )
    return base64.b64encode(sig_bin.read_bytes()).decode("ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-version", required=True)
    parser.add_argument("--cdn", required=True)
    parser.add_argument("--entries", required=True, help="JSON array of binary entries")
    parser.add_argument("--key", default=None, help="RSA private key PEM (SHA256withRSA)")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    entries = json.loads(Path(args.entries).read_text(encoding="utf-8"))
    payload_obj = build_payload(args.core_version, args.cdn, entries)
    payload = json.dumps(payload_obj, sort_keys=True, separators=(",", ":"))

    signature = sign_payload(payload, args.key) if args.key else ""
    if not args.key:
        print("warning: no signing key given; empty signature (app skips verify only in dev)", file=sys.stderr)

    envelope = {"payload": payload, "signature": signature}
    Path(args.out).write_text(json.dumps(envelope, indent=2), encoding="utf-8")
    print(f"written: {args.out}  (payload sha256={hashlib.sha256(payload.encode()).hexdigest()})")


if __name__ == "__main__":
    main()
