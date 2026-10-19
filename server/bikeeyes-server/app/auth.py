import base64
import hashlib
import hmac
import json
import os
import secrets
import string
import time
from dataclasses import dataclass

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from fastapi import HTTPException, Request

from app.db import get_connection
from app.email_service import password_email_configured, send_temporary_password


ACCESS_TOKEN_SECONDS = 30 * 60
REFRESH_TOKEN_SECONDS = 30 * 24 * 60 * 60
JWT_SECRET = os.getenv("BIKE_JWT_SECRET", "change-this-development-secret")
JWT_ISSUER = "bikeeyes"
password_hasher = PasswordHasher()

if (
    os.getenv("BIKE_ENV", "development").lower() != "development"
    and JWT_SECRET == "change-this-development-secret"
):
    raise RuntimeError("BIKE_JWT_SECRET must be configured outside development")


@dataclass(frozen=True)
class AuthPrincipal:
    user_id: int
    device_id: str


def hash_password(password: str) -> str:
    return password_hasher.hash(password)


def verify_password(password_hash: str, password: str) -> bool:
    try:
        return password_hasher.verify(password_hash, password)
    except VerifyMismatchError:
        return False


def validate_password(password: str):
    special = set(string.punctuation)
    if len(password) < 8 or not any(character in special for character in password):
        raise HTTPException(
            status_code=422,
            detail="password must be at least 8 characters and include a special character"
        )


def _b64encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _b64decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def create_access_token(user_id: int, device_id: str) -> str:
    now = int(time.time())
    header = _b64encode(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    payload = _b64encode(json.dumps({
        "sub": str(user_id),
        "device_id": device_id,
        "iss": JWT_ISSUER,
        "iat": now,
        "exp": now + ACCESS_TOKEN_SECONDS
    }).encode())
    signature = _b64encode(
        hmac.new(JWT_SECRET.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest()
    )
    return f"{header}.{payload}.{signature}"


def decode_access_token(token: str) -> AuthPrincipal:
    try:
        header, payload, signature = token.split(".")
        expected = _b64encode(
            hmac.new(JWT_SECRET.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest()
        )
        if not hmac.compare_digest(signature, expected):
            raise ValueError("invalid signature")
        claims = json.loads(_b64decode(payload))
        if claims.get("iss") != JWT_ISSUER or int(claims["exp"]) <= int(time.time()):
            raise ValueError("expired token")
        return AuthPrincipal(int(claims["sub"]), claims["device_id"])
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
        raise HTTPException(status_code=401, detail="invalid or expired access token")


def optional_principal(request: Request) -> AuthPrincipal | None:
    authorization = request.headers.get("Authorization", "")
    if not authorization.startswith("Bearer "):
        return None
    principal = decode_access_token(authorization[7:])
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM users WHERE user_id=%s AND is_active=TRUE",
                (principal.user_id,),
            )
            if cur.fetchone() is None:
                raise HTTPException(
                    status_code=401,
                    detail="account is no longer active",
                )
    finally:
        conn.close()
    return principal


def require_principal(request: Request) -> AuthPrincipal:
    principal = optional_principal(request)
    if principal is None:
        raise HTTPException(status_code=401, detail="authentication required")
    return principal


def new_refresh_token() -> str:
    return secrets.token_urlsafe(48)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def issue_token_pair(user_id: int, device_id: str) -> dict:
    refresh_token = new_refresh_token()
    now_ms = int(time.time() * 1000)
    expires_at = now_ms + REFRESH_TOKEN_SECONDS * 1000
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO devices (user_id, device_id, last_seen_at)
                VALUES (%s,%s,CURRENT_TIMESTAMP)
                ON CONFLICT (user_id, device_id)
                DO UPDATE SET last_seen_at=CURRENT_TIMESTAMP
                """,
                (user_id, device_id)
            )
            cur.execute(
                """
                INSERT INTO refresh_tokens (
                    user_id, device_id, token_hash, expires_at
                ) VALUES (%s,%s,%s,%s)
                """,
                (user_id, device_id, token_hash(refresh_token), expires_at)
            )
        conn.commit()
    finally:
        conn.close()
    return {
        "access_token": create_access_token(user_id, device_id),
        "refresh_token": refresh_token,
        "token_type": "bearer",
        "access_expires_in": ACCESS_TOKEN_SECONDS,
        "refresh_expires_in": REFRESH_TOKEN_SECONDS
    }

