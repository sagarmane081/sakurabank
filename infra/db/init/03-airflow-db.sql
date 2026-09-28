-- Airflow's own metadata database (DAG/task/run state) is kept fully isolated from the
-- `sakurabank` application database — Airflow owns and migrates this schema itself via
-- `airflow db migrate`, so it must not live inside `sakurabank`/`core`/`ai`/`audit`.
CREATE DATABASE airflow;
