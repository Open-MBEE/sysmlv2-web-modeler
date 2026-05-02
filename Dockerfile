FROM maven:3.9.9-eclipse-temurin-21 AS pilot-build

ARG PILOT_REPO=https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation.git

WORKDIR /tmp

COPY .pilot-version /tmp/.pilot-version

RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*

RUN set -e; \
    PILOT_REF="$(grep -v '^#' /tmp/.pilot-version | tr -d '[:space:]')"; \
    if [ -z "${PILOT_REF}" ]; then \
      echo ".pilot-version is empty or missing" >&2; \
      exit 1; \
    fi; \
    git init /tmp/pilot-src; \
    cd /tmp/pilot-src; \
    git remote add origin "${PILOT_REPO}"; \
    git fetch --depth=1 origin "${PILOT_REF}"; \
    git checkout FETCH_HEAD; \
    cd /tmp/pilot-src; \
    mvn clean package -DskipTests -B -q; \
    mkdir -p /opt/pilot; \
    find /tmp/pilot-src \
      -name "*.jar" \
      ! -name "*-sources.jar" \
      ! -name "*-javadoc.jar" \
      ! -name "*-all.jar" \
      ! -name "*.source_*.jar" \
      ! -name "*.source-*.jar" \
      ! -name "*.feature*.jar" \
      ! -name "*tests*.jar" \
      ! -name "jupyter-sysml-kernel-*.jar" \
      ! -path "*/test-classes/*" \
      -exec cp -n {} /opt/pilot/ \; ; \
    cp -r /tmp/pilot-src/sysml.library /opt/sysml.library

FROM eclipse-temurin:21-jdk AS build

WORKDIR /opt/app

COPY app/ /opt/app/
COPY openmbee-export/app/static/ /opt/app/static/
COPY .pilot-version /opt/app/.pilot-version
COPY --from=pilot-build /opt/pilot /opt/pilot
COPY --from=pilot-build /opt/sysml.library /opt/sysml.library

RUN find /opt/app -name "*.java" -print0 | xargs -0 javac -encoding windows-1252 -cp "/opt/pilot/*"

FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y graphviz python3 && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/app

COPY --from=build /opt/app /opt/app
COPY --from=build /opt/pilot /opt/pilot
COPY --from=build /opt/sysml.library /opt/sysml.library

EXPOSE 8088
ENV PORT=8088
ENV PYTHON_BIN=python3
ENV SYSML_LIBRARY_PATH=/opt/sysml.library
ENV SYSML_API_BASE=https://experimental.starforge.app/
ENV SYSML_API_TOKEN="Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJmbGV4by1tbXMtYXVkaWVuY2UiLCJpc3MiOiJodHRwOi8vZmxleG8tbW1zLXNlcnZpY2VzIiwidXNlcm5hbWUiOiJ1c2VyMDEiLCJncm91cHMiOlsic3VwZXJfYWRtaW5zIl0sImV4cCI6MTgwMTIwOTYwMH0.xv6cRFq8KgtkuBJYGdwJSvgpktJUcWvsivSn9UJmwAk"

CMD ["bash", "-lc", "java -cp '/opt/app:/opt/pilot/*' SysMLVizServer"]
