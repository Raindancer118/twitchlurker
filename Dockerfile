# syntax=docker/dockerfile:1
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests && cp target/twitchlurker-*.jar /app.jar

FROM eclipse-temurin:25-jre-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv git \
 && rm -rf /var/lib/apt/lists/*
COPY runner/requirements.txt /opt/runner-requirements.txt
# TCPM's setup.py pulls pre-commit; install it without deps and pin the runtime deps ourselves.
RUN python3 -m venv /opt/venv \
 && grep -v "^Twitch-Channel" /opt/runner-requirements.txt > /tmp/deps.txt \
 && /opt/venv/bin/pip install --no-cache-dir -r /tmp/deps.txt \
 && /opt/venv/bin/pip install --no-cache-dir --no-deps "$(grep '^Twitch-Channel' /opt/runner-requirements.txt)" \
 && apt-get purge -y git && apt-get autoremove -y
RUN useradd --system --uid 10001 --home /app lurker && mkdir -p /data && chown lurker /data
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
COPY runner/*.py /app/runner/
ENV LURKER_DATA_DIR=/data \
    LURKER_PYTHON=/opt/venv/bin/python \
    LURKER_RUNNER=/app/runner/lurker_runner.py \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60"
USER lurker
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD /opt/venv/bin/python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/actuator/health', timeout=4).status == 200 else 1)"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
