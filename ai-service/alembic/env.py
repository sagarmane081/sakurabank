import os
from logging.config import fileConfig

from sqlalchemy import engine_from_config, pool, text

from alembic import context
from app.models import Base

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)


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


config.set_main_option("sqlalchemy.url", get_database_url())

target_metadata = Base.metadata

def include_object(
    object,
    name,
    type_,
    reflected,
    compare_to,
) -> bool:
    if type_ == "table":
        schema = object.schema if reflected else object.schema
        return schema == "ai"

    if type_ == "index":
        table = object.table
        return table.schema == "ai"

    return True


def run_migrations_offline() -> None:
    url = config.get_main_option("sqlalchemy.url")

    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        version_table_schema="ai",
        include_schemas=True,
        include_object=include_object,
    )

    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )

    with connectable.connect() as connection:
        connection.execute(text("CREATE SCHEMA IF NOT EXISTS ai"))

        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            version_table_schema="ai",
            include_schemas=True,
            include_object=include_object,
        )

        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()