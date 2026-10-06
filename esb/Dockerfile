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
# expat and SQLite had fixed MEDIUM ones (Trivy, 2026-10-06).
USER root
RUN apt-get update \
  && apt-get install -y --only-upgrade --no-install-recommends openssl libssl3t64 openssl-provider-legacy \
       curl libcurl4t64 libexpat1 libsqlite3-0 \
  && rm -rf /var/lib/apt/lists/*

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

# First start may resolve a few component jars (platform-http, http) before the
# HTTP server is ready — give it a moment after `docker compose up`.
CMD ["run", "--source-dir=/routes", "--logging-config-path=/etc/camel/log4j2-graylog.xml"]
