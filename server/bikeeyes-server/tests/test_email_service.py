from email.message import EmailMessage

import pytest

from app import email_service


SMTP_ENV = {
    "BIKE_SMTP_HOST": "smtp.gmail.com",
    "BIKE_SMTP_PORT": "587",
    "BIKE_SMTP_USE_STARTTLS": "true",
    "BIKE_SMTP_USE_SSL": "false",
    "BIKE_SMTP_USER": "bikeeyes.project@gmail.com",
    "BIKE_SMTP_PASSWORD": "abcdefghijklmnop",
    "BIKE_SMTP_FROM": "bikeeyes.project@gmail.com",
    "BIKE_SMTP_FROM_NAME": "BikeEyes",
    "BIKE_SMTP_TIMEOUT_SECONDS": "10",
}


def set_smtp_environment(monkeypatch):
    for key, value in SMTP_ENV.items():
        monkeypatch.setenv(key, value)


def test_password_email_requires_all_credentials(monkeypatch):
    set_smtp_environment(monkeypatch)
    assert email_service.password_email_configured()
    monkeypatch.delenv("BIKE_SMTP_PASSWORD")
    assert not email_service.password_email_configured()


def test_gmail_starttls_delivery(monkeypatch):
    set_smtp_environment(monkeypatch)
    calls = []

    class FakeSmtp:
        def __init__(self, host, port, timeout, context=None):
            calls.append(("connect", host, port, timeout, context))

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return None

        def ehlo(self):
            calls.append(("ehlo",))

        def starttls(self, context):
            calls.append(("starttls", context))

        def login(self, username, password):
            calls.append(("login", username, password))

        def send_message(self, message: EmailMessage):
            calls.append(("send", message))

    monkeypatch.setattr(email_service.smtplib, "SMTP", FakeSmtp)
    email_service.send_temporary_password("rider@example.com", "temporary!")

    assert calls[0][0:4] == ("connect", "smtp.gmail.com", 587, 10.0)
    assert [call[0] for call in calls].count("ehlo") == 2
    assert any(call[0] == "starttls" for call in calls)
    assert ("login", "bikeeyes.project@gmail.com", "abcdefghijklmnop") in calls
    sent = next(call[1] for call in calls if call[0] == "send")
    assert sent["To"] == "rider@example.com"
    assert "temporary!" in sent.get_content()


def test_starttls_and_ssl_cannot_both_be_enabled(monkeypatch):
    set_smtp_environment(monkeypatch)
    monkeypatch.setenv("BIKE_SMTP_USE_SSL", "true")
    with pytest.raises(RuntimeError):
        email_service.SmtpSettings.from_environment()
