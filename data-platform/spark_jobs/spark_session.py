import os

from pyspark.sql import SparkSession

from spark_jobs.config import DATA_LAKE_PATH

_DELTA_PACKAGE = "io.delta:delta-spark_2.12:3.2.0,org.postgresql:postgresql:42.7.4"


def get_spark(app_name: str) -> SparkSession:
    return (
        SparkSession.builder.appName(app_name)
        .master(os.environ.get("SPARK_MASTER", "local[*]"))
        .config("spark.jars.packages", _DELTA_PACKAGE)
        .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
        .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
        .config("spark.sql.warehouse.dir", os.path.join(DATA_LAKE_PATH, "_spark-warehouse"))
        .config("spark.driver.memory", os.environ.get("SPARK_DRIVER_MEMORY", "2g"))
        .getOrCreate()
    )


def delta_path(layer: str, table: str) -> str:
    return os.path.join(DATA_LAKE_PATH, layer, table)


def idempotent_write(df, layer: str, table: str, business_date: str):
    """Write a business_date partition of `df` to the Delta table at layer/table.

    Uses Delta's `replaceWhere` to overwrite only the target partition, so rerunning
    the same business_date (e.g. after a mid-run failure) replaces that day's data
    instead of appending duplicates alongside it.

    `mergeSchema` lets a new column flow through (older partitions read it as NULL) while
    Delta still rejects incompatible type changes. Without it, a column the source
    contract treats as additive and allowed crashed the very next Bronze write -- found by
    test_contract.py::test_additive_column_flows_into_existing_table.
    """
    from delta.tables import DeltaTable

    path = delta_path(layer, table)
    writer = (
        df.write.format("delta")
        .mode("overwrite")
        .option("mergeSchema", "true")
        .partitionBy("business_date")
    )
    if DeltaTable.isDeltaTable(df.sparkSession, path):
        writer = writer.option("replaceWhere", f"business_date = '{business_date}'")
    writer.save(path)
    return path


def read_delta(spark: SparkSession, layer: str, table: str):
    return spark.read.format("delta").load(delta_path(layer, table))
