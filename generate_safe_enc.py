#!/usr/bin/env python3
"""
Encrypt strings for use with Safe.s() in BladeWatch.

Uses the same AES-256-CBC key and IV as Safe.java to produce
Base64-encoded ciphertext that Safe.s() can decrypt at runtime.

IMPORTANT: Safe.s() is for NON-SECRET obfuscation only (internal paths,
service names, shell commands, package names). The AES key is embedded in
the APK and can be recovered by decompiling — so Safe.s() provides light
obfuscation against casual inspection, NOT real confidentiality.

Do NOT use Safe.s() for secrets (credentials, proxy addresses, tokens,
API keys). Secrets must be stored in bladewatch_secrets.json via
SecretConfigStore, written at runtime by the daemon process (UID 2000).

Usage:
    python3 generate_safe_enc.py "your plaintext string"
    python3 generate_safe_enc.py              # interactive mode

Requires: pip install pycryptodome
"""

import sys
import base64
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad

# Key parts from Safe.java (same byte arrays)
K1 = bytes([0x38, 0x39, 0x33, 0x38, 0x34, 0x37, 0x32, 0x38])
K2 = bytes([0x33, 0x37, 0x34, 0x38, 0x32, 0x39, 0x33, 0x30])
K3 = bytes([0x31, 0x38, 0x32, 0x37, 0x33, 0x38, 0x34, 0x39])
K4 = bytes([0x31, 0x30, 0x32, 0x39, 0x33, 0x38, 0x34, 0x37])
KEY = K1 + K2 + K3 + K4
IV = bytes([0x31, 0x30, 0x32, 0x39, 0x33, 0x38, 0x34, 0x37,
            0x35, 0x36, 0x31, 0x30, 0x32, 0x39, 0x33, 0x38])


def encrypt(plaintext: str) -> str:
    cipher = AES.new(KEY, AES.MODE_CBC, IV)
    ct = cipher.encrypt(pad(plaintext.encode("utf-8"), AES.block_size))
    return base64.b64encode(ct).decode("utf-8")


if __name__ == "__main__":
    if len(sys.argv) > 1:
        text = " ".join(sys.argv[1:])
        print(encrypt(text))
    else:
        print("Enter strings to encrypt (one per line, Ctrl+D to quit):")
        for line in sys.stdin:
            line = line.rstrip("\n")
            if line:
                print(f"  {line}  ->  {encrypt(line)}")
