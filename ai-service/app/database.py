import os

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker


def get_database_url() -> str:
    host = os.getenv("DB_HOST", "localhost")
    port = os.getenv("DB_PORT", "5432")
    database = os.getenv("DB_NAME", "sakurabank")
    user = os.getenv("DB_USER", "sakura")
    password = os.getenv("DB_PASSWORD", "sakura_local_dev")

    return (
        f"postgresql+psycopg://{user}:{password}"
        f"@{host}:{port}/{database}"
    )


engine = create_engine(
    get_database_url(),
    pool_pre_ping=True,
)

SessionLocal = sessionmaker(
    bind=engine,
    autoflush=False,
    autocommit=False,
)