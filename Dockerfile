FROM maven:3.9.9-eclipse-temurin-21 AS pilot-build

ARG PILOT_REPO=https://github.com/Systems-Modeling/SysML-v2-Pilot-Implementation.git

WORKDIR /tmp

COPY .pilot-version /tmp/.pilot-version
COPY patches/pilot-missing-reference-id.patch /tmp/pilot-missing-reference-id.patch

RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*

# Rebuild the API client that Pilot vendors with fixed Gson and OkHttp versions.
RUN set -e; \
    git init /tmp/sysml-api-client; \
    cd /tmp/sysml-api-client; \
    git remote add origin https://github.com/Systems-Modeling/SysML-v2-API-Java-Client.git; \
    git fetch --depth=1 origin 09be58a2de0bb44fa8f4c9deb2537a0bdd822068; \
    git checkout FETCH_HEAD; \
    sed -i '0,/<plugins>/s|<plugins>|<plugins><plugin><artifactId>maven-assembly-plugin</artifactId><configuration><descriptorRefs><descriptorRef>jar-with-dependencies</descriptorRef></descriptorRefs></configuration></plugin>|' pom.xml; \
    mvn -B -q -Dmaven.test.skip=true -Djava.version=8 -Dgson-version=2.13.2 -Dokhttp-version=4.12.0 \
      compile jar:jar org.apache.maven.plugins:maven-assembly-plugin:3.7.1:single; \
    cp target/sysml-v2-api-client-*-jar-with-dependencies.jar /tmp/sysml-v2-api-client-all.jar

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
    git apply --ignore-space-change /tmp/pilot-missing-reference-id.patch; \
    cp /tmp/sysml-v2-api-client-all.jar org.omg.sysml/lib/sysml-v2-api-client-all.jar; \
    mvn clean package -DskipTests -B -q; \
    mkdir -p /opt/pilot; \
    find /tmp/pilot-src \
      -name "*.jar" \
      ! -name "*-sources.jar" \
      ! -name "*-javadoc.jar" \
      ! -name "*-all.jar" \
      ! -name "guava-*.jar" \
      ! -name "*.source_*.jar" \
      ! -name "*.source-*.jar" \
      ! -name "*.feature*.jar" \
      ! -name "*tests*.jar" \
      ! -name "jupyter-sysml-kernel-*.jar" \
      ! -path "*/test-classes/*" \
      -exec cp -n {} /opt/pilot/ \; ; \
    cp -r /tmp/pilot-src/sysml.library /opt/sysml.library

RUN mvn -B -q org.apache.maven.plugins:maven-dependency-plugin:3.9.0:copy \
      -Dartifact=com.google.guava:guava:33.7.2-jre -DoutputDirectory=/opt/pilot

FROM eclipse-temurin:21-jdk AS build

WORKDIR /opt/app

COPY app/ /opt/app/
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
COPY LICENSE-EPL LICENSE-LGPL LICENSE-GPL /opt/pilot-licenses/

EXPOSE 8088
ENV PORT=8088
ENV PYTHON_BIN=python3
ENV SYSML_LIBRARY_PATH=/opt/sysml.library

CMD ["bash", "-lc", "java -cp '/opt/app:/opt/pilot/*' SysMLVizServer"]
