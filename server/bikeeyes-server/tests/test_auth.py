import pytest

from app.auth import hash_password, validate_password, verify_password


def test_password_hash_is_not_plaintext_and_verifies():
    password = "bikeeyes!"
    encoded = hash_password(password)
    assert encoded != password
    assert verify_password(encoded, password)
    assert not verify_password(encoded, "wrong!")


@pytest.mark.parametrize("password", ["short!", "abcdefgh", ""])
def test_password_policy_rejects_invalid_values(password):
    with pytest.raises(Exception):
        validate_password(password)
