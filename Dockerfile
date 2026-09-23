# base images are pinned by digest, which dependabot refreshes: a tag can be rebuilt between two
# builds of the same release, a digest cannot
FROM gradle:9.7.1-jdk21-alpine@sha256:c5b166bec57ad50776e622b8d3e8db0afc09cba80cfe8b199ab865a3ab04c114 AS builder

WORKDIR /app

RUN jlink \
      --add-modules java.base,java.logging,java.naming,java.net.http,java.xml,jdk.crypto.ec \
      --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
      --output /javaruntime

COPY build.gradle.kts gradle.properties settings.gradle.kts ./
COPY gradle/libs.versions.toml gradle/

RUN gradle --no-daemon shadowJar

COPY src ./src
RUN gradle --no-daemon shadowJar


FROM alpine:3.24@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6

WORKDIR /app

ENV JAVA_HOME=/opt/java
ENV PATH="$JAVA_HOME/bin:$PATH"

RUN adduser -u 10001 -D -H -s /sbin/nologin varta
USER varta

COPY --from=builder /javaruntime $JAVA_HOME
COPY --from=builder /app/build/libs/*-all.jar varta.jar

HEALTHCHECK --interval=60s --timeout=5s --start-period=120s --retries=3 \
    CMD test $(( $(date +%s) - $(stat -c %Y /tmp/health 2>/dev/null || echo 0) )) -lt ${HEALTH_STALE_SECONDS:-600}

ENTRYPOINT ["java", "-Dkotlin-logging.logStartupMessage=false", "-jar", "varta.jar"]
