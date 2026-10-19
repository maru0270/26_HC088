import pytest

from app import main


class FakeCursor:
    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class FakeConnection:
    def __init__(self):
        self.committed = False
        self.rolled_back = False
        self.closed = False

    def cursor(self):
        return FakeCursor()

    def commit(self):
        self.committed = True

    def rollback(self):
        self.rolled_back = True

    def close(self):
        self.closed = True


def test_db_transaction_commits_and_closes(monkeypatch):
    connection = FakeConnection()
    monkeypatch.setattr(main, "get_connection", lambda: connection)

    with main.db_transaction():
        pass

    assert connection.committed is True
    assert connection.rolled_back is False
    assert connection.closed is True


def test_db_transaction_rolls_back_and_closes(monkeypatch):
    connection = FakeConnection()
    monkeypatch.setattr(main, "get_connection", lambda: connection)

    with pytest.raises(RuntimeError):
        with main.db_transaction():
            raise RuntimeError("forced failure")

    assert connection.committed is False
    assert connection.rolled_back is True
    assert connection.closed is True
