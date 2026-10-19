import os
import smtplib
import ssl
from dataclasses import dataclass
from email.message import EmailMessage
from email.utils import formataddr


@dataclass(frozen=True)
class SmtpSettings:
    host: str
    port: int
    username: str
    password: str
    sender_address: str
    sender_name: str
    use_starttls: bool
    use_ssl: bool
    timeout_seconds: float

    @classmethod
    def from_environment(cls) -> "SmtpSettings | None":
        host = os.getenv("BIKE_SMTP_HOST", "").strip()
        username = os.getenv("BIKE_SMTP_USER", "").strip()
        password = os.getenv("BIKE_SMTP_PASSWORD", "").replace(" ", "").strip()
        sender_address = os.getenv("BIKE_SMTP_FROM", "").strip()
        if not all((host, username, password, sender_address)):
            return None

        use_starttls = _environment_flag("BIKE_SMTP_USE_STARTTLS", True)
        use_ssl = _environment_flag("BIKE_SMTP_USE_SSL", False)
        if use_starttls and use_ssl:
            raise RuntimeError("SMTP STARTTLS and implicit SSL cannot both be enabled")

        return cls(
            host=host,
            port=int(os.getenv("BIKE_SMTP_PORT", "587")),
            username=username,
            password=password,
            sender_address=sender_address,
            sender_name=os.getenv("BIKE_SMTP_FROM_NAME", "BikeEyes").strip() or "BikeEyes",
            use_starttls=use_starttls,
            use_ssl=use_ssl,
            timeout_seconds=float(os.getenv("BIKE_SMTP_TIMEOUT_SECONDS", "10")),
        )


def _environment_flag(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def password_email_configured() -> bool:
    return SmtpSettings.from_environment() is not None


def send_temporary_password(recipient: str, temporary_password: str) -> None:
    settings = SmtpSettings.from_environment()
    if settings is None:
        raise RuntimeError("password reset email is not configured")

    message = EmailMessage()
    message["Subject"] = "BikeEyes 임시 비밀번호 안내"
    message["From"] = formataddr((settings.sender_name, settings.sender_address))
    message["To"] = recipient
    message.set_content(
        "BikeEyes 비밀번호 재설정 요청으로 임시 비밀번호가 발급되었습니다.\n\n"
        f"임시 비밀번호: {temporary_password}\n\n"
        "임시 비밀번호로 로그인한 뒤 반드시 새 비밀번호로 변경해 주세요.\n"
        "본인이 요청하지 않았다면 BikeEyes 관리자에게 문의해 주세요."
    )

    context = ssl.create_default_context()
    if settings.use_ssl:
        smtp_connection = smtplib.SMTP_SSL(
            settings.host,
            settings.port,
            timeout=settings.timeout_seconds,
            context=context,
        )
    else:
        smtp_connection = smtplib.SMTP(
            settings.host,
            settings.port,
            timeout=settings.timeout_seconds,
        )

    with smtp_connection as smtp:
        if settings.use_starttls:
            smtp.ehlo()
            smtp.starttls(context=context)
            smtp.ehlo()
        smtp.login(settings.username, settings.password)
        smtp.send_message(message)
