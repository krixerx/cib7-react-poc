# CIB seven POC — integration bus ("esb").
#
# Apache Camel JBang running declarative YAML routes. No Java, no RouteBuilder:
# all mediation logic lives in routes/*.yaml. The base image's ENTRYPOINT is
# `camel`, so CMD supplies the sub-command + args (i.e. it runs
# `camel run --source-dir=/routes`, loading every *.yaml route in the directory).
# Adding an integration in a later stage is just dropping a new route file in
# esb/routes/ — no Dockerfile change.
FROM apache/camel-jbang:4.22.1-21-jdk

# The base image lags Ubuntu's security pocket, so every package is upgraded
# rather than a named list: the list blocked publishing each time a fix landed
# in a package it did not name yet (libbrotli1, CVE-2026-111497, 2026-11-02).
# Pebble, the Temurin base's Go-built service manager, had fixed HIGH Go stdlib
# CVEs (2026-10-13); camel is the entrypoint and nothing starts Pebble, so it
# goes.
# PACKAGES_REFRESH (the build date, docker-publish.yml) changes daily, so the
# CI layer cache cannot keep serving an upgrade layer older than Ubuntu's
# newest fixes (a fixed HIGH in libcurl4t64 blocked publishing, 2026-10-19).
ARG PACKAGES_REFRESH
USER root
RUN apt-get update \
  && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y --no-install-recommends \
  && rm -rf /var/lib/apt/lists/* \
  && rm -f /usr/bin/pebble

# NOTE: startup logs one benign warning — "Failed to create working directory:
# /.camel-jbang" — because the base image's user has OS home "/" (not writable).
# It's cosmetic: the route still resolves its components and runs. The path comes
# from the JVM user.home, so an ENV HOME override does not move it.

# Declarative route definitions (Camel YAML DSL).
COPY routes/ /routes/

# Log4j2 configuration that adds a GELF appender next to the console one, so the
# bus's own logs land in Graylog along with every other service's. Camel JBang
# owns the Log4j2 setup itself, and --logging-config-path (see CMD) is the
# supported way to replace it.
COPY log4j2-graylog.xml /etc/camel/log4j2-graylog.xml

# platform-http serves the routes on 0.0.0.0:8080 (Camel's default embedded
# server). The engine reaches the bus at http://esb:8080 (see docker-compose.yml).
EXPOSE 8080

# The base image already drops to its `ubuntu` user (uid 1000); restated here so
# the non-root guarantee (docs/security.md rule 11) does not hinge on a base
# image default that a version bump could change.
USER ubuntu

# camel-jbang-core 4.22.1 pins Jolokia 2.6.0 (CVE-2026-84218, HIGH; Trivy
# 2026-10-14), jackson 2.22.1 (CVE-2026-89407 and others, HIGH) and freemarker
# 2.3.34 (CVE-2026-84939, CRITICAL; Trivy 2026-11-23), all pre-warmed in the
# Maven cache. A direct DEPS line in the launcher script wins over a
# transitive pin, so the fixed versions are resolved here, and the old jars go
# so nothing can load them. Drop each pin once a camel-jbang image ships the
# fix.
#
# JBang itself only launches the script, and its jar shades httpclient5,
# httpcore5 and jsoup with HIGH findings that no JBang release fixes yet
# (0.143.0 checked, 2026-11-23). So the script is compiled here, its resolved
# classpath goes into a java argument file, and the container starts Camel
# with plain java (ENTRYPOINT below); JBang leaves the image.
RUN sed -i '/^\/\/DEPS org.apache.camel:camel-jbang-core/a //DEPS org.jolokia:jolokia-agent-jvm:2.6.3:javaagent' /opt/jbang/CamelJBang.java \
  && sed -i '/^\/\/DEPS org.jolokia:jolokia-agent-jvm/a //DEPS com.fasterxml.jackson.core:jackson-core:2.22.4' /opt/jbang/CamelJBang.java \
  && sed -i '/^\/\/DEPS com.fasterxml.jackson.core:jackson-core/a //DEPS com.fasterxml.jackson.core:jackson-databind:2.22.4' /opt/jbang/CamelJBang.java \
  && sed -i '/^\/\/DEPS com.fasterxml.jackson.core:jackson-databind/a //DEPS org.freemarker:freemarker:2.3.35' /opt/jbang/CamelJBang.java \
  && rm -rf /home/ubuntu/.m2/repository/org/jolokia \
     /home/ubuntu/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.22.1 \
     /home/ubuntu/.m2/repository/com/fasterxml/jackson/core/jackson-databind/2.22.1 \
     /home/ubuntu/.m2/repository/org/freemarker/freemarker/2.3.34 \
  && jbang build /opt/jbang/CamelJBang.java \
  && cp "$(find /home/ubuntu/.jbang/cache/jars -name CamelJBang.jar | head -n 1)" /tmp/CamelJBang.jar \
  && echo "-cp /opt/camel/CamelJBang.jar:$(jbang info classpath /opt/jbang/CamelJBang.java)" > /tmp/classpath.args

# The launcher and its argument file are root-owned and read-only to ubuntu.
# The build fails if an old jar is still on the classpath.
USER root
RUN mkdir -p /opt/camel \
  && mv /tmp/CamelJBang.jar /tmp/classpath.args /opt/camel/ \
  && chown -R root:root /opt/camel \
  && rm -rf /opt/jbang /usr/local/bin/camel /usr/local/bin/jbang /home/ubuntu/.jbang \
  && ! grep -qE 'jackson-(core|databind)-2\.22\.1|freemarker-2\.3\.34|jolokia-agent-jvm-2\.6\.0' /opt/camel/classpath.args
USER ubuntu

# What the base image's `camel` script ran (`jbang run CamelJBang.java`), as
# plain java on the classpath resolved above.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "@/opt/camel/classpath.args", "main.CamelJBang"]

# First start may resolve a few component jars (platform-http, http) before the
# HTTP server is ready — give it a moment after `docker compose up`.
CMD ["run", "--source-dir=/routes", "--logging-config-path=/etc/camel/log4j2-graylog.xml"]
