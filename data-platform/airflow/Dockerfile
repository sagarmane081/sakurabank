# Extends the official Airflow image with a JVM (required for PySpark/Delta Lake) and
# the Python packages the DAGs and Spark jobs need.
FROM apache/airflow:2.9.3-python3.11

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* \
    && JAVA_DIR="$(dirname "$(dirname "$(readlink -f "$(which java)")")")" \
    && ln -s "$JAVA_DIR" /usr/lib/jvm/default-java

ENV JAVA_HOME=/usr/lib/jvm/default-java
ENV PATH="$JAVA_HOME/bin:$PATH"

USER airflow
COPY requirements.txt /requirements.txt
RUN pip install --no-cache-dir -r /requirements.txt

# Ivy (Spark's dependency resolver, used for spark.jars.packages) expects its cache/jars
# subdirectories to already exist and fails outright if they don't. `.ivy2` is also a
# volume mount point (so resolved packages persist across container restarts) -- Docker
# only seeds a *new* named volume from the image on its first mount, so these directories
# must exist here for that seeding to work.
RUN mkdir -p /home/airflow/.ivy2/cache /home/airflow/.ivy2/jars
