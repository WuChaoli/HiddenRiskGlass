#!/usr/bin/env python3
"""AES/ECB/PKCS5Padding utility matching the original Java helper."""

from __future__ import annotations

import base64
import json
import secrets
from dataclasses import dataclass
from datetime import date

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


SECRET = "Btm/Cb6N6glbcOEvjV8qGnyQELjWFUkD"
AES_BLOCK_SIZE = 16


@dataclass
class AESSecret:
    appKey: str
    appSecret: str


def _key_bytes(key: str) -> bytes:
    key_data = key.encode()
    if len(key_data) not in (16, 24, 32):
        raise ValueError("AES key must be 16, 24, or 32 bytes after UTF-8 encoding")
    return key_data


def _pkcs5_pad(data: bytes) -> bytes:
    # AES 的块大小为 16 字节；Java PKCS5Padding 在 AES 场景下等价于 PKCS7。
    padding_len = AES_BLOCK_SIZE - (len(data) % AES_BLOCK_SIZE)
    return data + bytes([padding_len]) * padding_len


def _pkcs5_unpad(data: bytes) -> bytes:
    if not data:
        raise ValueError("decrypted data is empty")
    padding_len = data[-1]
    if padding_len < 1 or padding_len > AES_BLOCK_SIZE:
        raise ValueError("invalid PKCS5 padding")
    if data[-padding_len:] != bytes([padding_len]) * padding_len:
        raise ValueError("invalid PKCS5 padding")
    return data[:-padding_len]


def aes_encrypt(text: str, key: str) -> str:
    """Encrypt text with AES/ECB/PKCS5Padding and return Base64 ciphertext."""
    cipher = Cipher(algorithms.AES(_key_bytes(key)), modes.ECB())
    encryptor = cipher.encryptor()
    encrypted = encryptor.update(_pkcs5_pad(text.encode())) + encryptor.finalize()
    return base64.b64encode(encrypted).decode()


def aes_decrypt(base64_encrypted: str, key: str) -> str:
    """Decrypt Base64 ciphertext created by aes_encrypt."""
    cipher = Cipher(algorithms.AES(_key_bytes(key)), modes.ECB())
    decryptor = cipher.decryptor()
    encrypted = base64.b64decode(base64_encrypted)
    decrypted = decryptor.update(encrypted) + decryptor.finalize()
    return _pkcs5_unpad(decrypted).decode()


def get_app_key_and_secret() -> AESSecret:
    """Generate an appKey/appSecret pair in the same Base64 shape as Java."""
    app_key = base64.b64encode(secrets.token_bytes(16)).decode()
    app_secret = base64.b64encode(secrets.token_bytes(24)).decode()
    return AESSecret(appKey=app_key, appSecret=app_secret)


# 保留 Java 原方法名，便于直接替换旧调用。
def AESEncrypt(text: str, key: str) -> str:
    return aes_encrypt(text, key)


def AESDecrypt(base64Encrypted: str, key: str) -> str:
    return aes_decrypt(base64Encrypted, key)


def getAPPKeyAndSecret() -> AESSecret:
    return get_app_key_and_secret()


def main() -> None:
    body = {
        "snCode": "111",
        "date": date.today().isoformat(),
    }
    text = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
    print(text)
    encrypted = aes_encrypt(text, SECRET)
    print(encrypted)
    print(aes_decrypt(encrypted, SECRET))


if __name__ == "__main__":
    main()
