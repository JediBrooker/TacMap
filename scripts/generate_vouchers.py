#!/usr/bin/env python3
"""Generate TacMap voucher codes + the salted SHA-256 hashes to embed.

Usage:
    python3 scripts/generate_vouchers.py 20

Prints N codes (KEEP THESE PRIVATE — they're what you hand out) and the
hash lines to paste into BOTH:
  - ios/TacticalMaps/Billing/VoucherManager.swift  -> validHashes
  - android/.../billing/VoucherManager.kt          -> VALID_HASHES
Only the hashes ship in the apps; codes can't be recovered from the binary.
"""
import hashlib
import secrets
import sys

SALT = "tacmap-voucher-v1"  # must match both apps
# No 0/O/1/I to avoid transcription errors.
ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"


def make_code() -> str:
    groups = ["".join(secrets.choice(ALPHABET) for _ in range(4)) for _ in range(3)]
    return "TACMAP-" + "-".join(groups)


def normalize(code: str) -> str:
    return "".join(c for c in code.upper() if c.isalnum())


def hash_code(code: str) -> str:
    return hashlib.sha256((SALT + normalize(code)).encode()).hexdigest()


def main() -> None:
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    codes = [make_code() for _ in range(n)]

    print("== CODES (keep private, distribute individually) ==")
    for c in codes:
        print(c)

    print("\n== Swift: paste into VoucherManager.validHashes ==")
    for c in codes:
        print(f'        "{hash_code(c)}",')

    print("\n== Kotlin: paste into VoucherManager.VALID_HASHES ==")
    for c in codes:
        print(f'            "{hash_code(c)}",')


if __name__ == "__main__":
    main()
