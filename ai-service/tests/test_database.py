from app.database import SessionLocal, engine, get_database_url


def test_get_database_url_uses_environment_variables(monkeypatch):
    monkeypatch.setenv("DB_HOST", "test-host")
    monkeypatch.setenv("DB_PORT", "5433")
    monkeypatch.setenv("DB_NAME", "test-db")
    monkeypatch.setenv("DB_USER", "test-user")
    monkeypatch.setenv("DB_PASSWORD", "test-password")

    url = get_database_url()

    assert url == (
        "postgresql+psycopg://test-user:test-password"
        "@test-host:5433/test-db"
    )


def test_get_database_url_uses_local_defaults(monkeypatch):
    for variable in (
        "DB_HOST",
        "DB_PORT",
        "DB_NAME",
        "DB_USER",
        "DB_PASSWORD",
    ):
        monkeypatch.delenv(variable, raising=False)

    url = get_database_url()

    assert url == (
        "postgresql+psycopg://sakura:sakura_local_dev"
        "@localhost:5432/sakurabank"
    )


def test_engine_uses_psycopg():
    assert engine.url.drivername == "postgresql+psycopg"


def test_session_factory_is_configured():
    assert SessionLocal.kw["bind"] is engine