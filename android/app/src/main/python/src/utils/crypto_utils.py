"""加密工具模块 — 纯 Python 实现，无外部依赖。

提供基于 PBKDF2 + HMAC-SHA256 的简单加密方案，
用于保护插件状态、世界书等敏感数据的本地存储。

安全说明：
- 使用 PBKDF2-HMAC-SHA256 从设备标识派生密钥（100,000 次迭代）
- 使用 HMAC-SHA256 进行完整性验证
- 密钥派生盐和 HMAC 均随密文存储
- 此方案适用于本地数据保护，不适用于网络传输加密
"""

import hashlib
import hmac
import os
from typing import Optional


# ── 常量 ──

_PBKDF2_ITERATIONS = 100_000
_KEY_LENGTH = 32  # 256 bits
_SALT_LENGTH = 16
_HMAC_LENGTH = 32  # SHA-256


def _derive_key(password: str, salt: bytes) -> bytes:
    """从密码和盐派生加密密钥。

    Args:
        password: 密码字符串（通常为设备标识）。
        salt: 随机盐（16 字节）。

    Returns:
        32 字节的派生密钥。
    """
    return hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt,
        _PBKDF2_ITERATIONS,
        dklen=_KEY_LENGTH,
    )


def _generate_key_stream(key: bytes, iv: bytes, length: int) -> bytes:
    """生成密钥流（用于 XOR 加密）。

    使用 HMAC-SHA256 作为 PRF，以 CTR 模式生成密钥流。

    Args:
        key: 加密密钥（32 字节）。
        iv: 初始化向量（16 字节）。
        length: 需要的密钥流长度。

    Returns:
        length 字节的密钥流。
    """
    stream = bytearray()
    counter = 0
    while len(stream) < length:
        counter_bytes = counter.to_bytes(8, "big")
        block = hmac.new(key, iv + counter_bytes, hashlib.sha256).digest()
        stream.extend(block)
        counter += 1
    return bytes(stream[:length])


def encrypt(plaintext: str, password: str) -> bytes:
    """加密文本数据。

    输出格式: salt(16) + iv(16) + ciphertext + hmac(32)

    Args:
        plaintext: 要加密的文本。
        password: 加密密码（通常为设备标识）。

    Returns:
        加密后的字节数据。
    """
    salt = os.urandom(_SALT_LENGTH)
    iv = os.urandom(16)
    key = _derive_key(password, salt)

    plaintext_bytes = plaintext.encode("utf-8")
    key_stream = _generate_key_stream(key, iv, len(plaintext_bytes))

    # XOR 加密
    ciphertext = bytes(a ^ b for a, b in zip(plaintext_bytes, key_stream))

    # HMAC 完整性验证（覆盖 salt + iv + ciphertext）
    hmac_input = salt + iv + ciphertext
    mac = hmac.new(key, hmac_input, hashlib.sha256).digest()

    return salt + iv + ciphertext + mac


def decrypt(encrypted_data: bytes, password: str) -> Optional[str]:
    """解密数据。

    Args:
        encrypted_data: 加密后的字节数据（格式: salt + iv + ciphertext + hmac）。
        password: 解密密码（与加密时相同）。

    Returns:
        解密后的文本，如果 HMAC 验证失败则返回 None。
    """
    if len(encrypted_data) < _SALT_LENGTH + 16 + _HMAC_LENGTH:
        return None

    salt = encrypted_data[:_SALT_LENGTH]
    iv = encrypted_data[_SALT_LENGTH:_SALT_LENGTH + 16]
    mac = encrypted_data[-_HMAC_LENGTH:]
    ciphertext = encrypted_data[_SALT_LENGTH + 16:-_HMAC_LENGTH]

    key = _derive_key(password, salt)

    # HMAC 验证
    hmac_input = salt + iv + ciphertext
    expected_mac = hmac.new(key, hmac_input, hashlib.sha256).digest()
    if not hmac.compare_digest(mac, expected_mac):
        return None  # 数据被篡改或密码错误

    # XOR 解密
    key_stream = _generate_key_stream(key, iv, len(ciphertext))
    plaintext_bytes = bytes(a ^ b for a, b in zip(ciphertext, key_stream))

    return plaintext_bytes.decode("utf-8")


def get_device_password() -> str:
    """获取设备特定的加密密码。

    使用 os.uname() 和 os.getpid() 等设备信息生成一个设备特定的密码。
    注意：此密码在不同进程间可能不同，实际使用时应使用更稳定的设备标识。

    Returns:
        设备特定密码字符串。
    """
    # 组合多个设备信息源
    sources = [
        os.uname().nodename if hasattr(os, "uname") else "android",
        str(os.getpid()),
        "ai_companion_v1",
    ]
    return "|".join(sources)