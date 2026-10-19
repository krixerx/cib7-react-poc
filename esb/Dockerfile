# CIB seven POC — integration bus ("esb").
#
# Apache Camel JBang running declarative YAML routes. No Java, no RouteBuilder:
# all mediation logic lives in routes/*.yaml. The base image's ENTRYPOINT is
# `camel`, so CMD supplies the sub-command + args (i.e. it runs
# `camel run --source-dir=/routes`, loading every *.yaml route in the directory).
# Adding an integration in a later stage is just dropping a new route file in
# esb/routes/ — no Dockerfile change.
FROM apache/camel-jbang:4.22.1-21-jdk

# The base image lags Ubuntu's security pocket; OpenSSL had fixed HIGH CVEs
# (Trivy in docker-publish.yml) that a package upgrade picks up, and curl,
# expat and SQLite had fixed MEDIUM ones (Trivy, 2026-10-06). Pebble, the
# Temurin base's Go-built service manager, had fixed HIGH Go stdlib CVEs
# (2026-10-13); camel is the entrypoint and nothing starts Pebble, so it goes.
# PACKAGES_REFRESH (the build date, docker-publish.yml) changes daily, so the
# CI layer cache cannot keep serving an upgrade layer older than Ubuntu's
# newest fixes (a fixed HIGH in libcurl4t64 blocked publishing, 2026-10-19).
ARG PACKAGES_REFRESH
USER root
RUN apt-get update \
  && apt-get install -y --only-upgrade --no-install-recommends openssl libssl3t64 openssl-provider-legacy \
       curl libcurl4t64 libexpat1 libsqlite3-0 \
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
# 2026-10-14), and the image pre-warms it in the Maven cache. A direct DEPS line
# in the launcher script wins over that transitive pin, so JBang resolves the
# fixed Jolokia set; the 2.6.0 jars go so nothing can load them, and resolving
# here keeps the new jars in the image instead of downloading them at start.
# Drop this once a camel-jbang image ships Jolokia >= 2.6.2.
RUN sed -i '/^\/\/DEPS org.apache.camel:camel-jbang-core/a //DEPS org.jolokia:jolokia-agent-jvm:2.6.3:javaagent' /opt/jbang/CamelJBang.java \
  && rm -rf /home/ubuntu/.m2/repository/org/jolokia \
  && jbang info classpath /opt/jbang/CamelJBang.java > /dev/null

# First start may resolve a few component jars (platform-http, http) before the
# HTTP server is ready — give it a moment after `docker compose up`.
CMD ["run", "--source-dir=/routes", "--logging-config-path=/etc/camel/log4j2-graylog.xml"]
