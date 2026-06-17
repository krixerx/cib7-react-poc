# CIB seven POC — integration bus ("esb").
#
# Apache Camel JBang running declarative YAML routes. No Java, no RouteBuilder:
# all mediation logic lives in routes/*.yaml. The base image's ENTRYPOINT is
# `camel`, so CMD supplies the sub-command + args (i.e. it runs
# `camel run --source-dir=/routes`, loading every *.yaml route in the directory).
# Adding an integration in a later stage is just dropping a new route file in
# esb/routes/ — no Dockerfile change.
FROM apache/camel-jbang:4.20.0-21-jdk

# NOTE: startup logs one benign warning — "Failed to create working directory:
# /.camel-jbang" — because the base image's user has OS home "/" (not writable).
# It's cosmetic: the route still resolves its components and runs. The path comes
# from the JVM user.home, so an ENV HOME override does not move it.

# Declarative route definitions (Camel YAML DSL).
COPY routes/ /routes/

# platform-http serves the routes on 0.0.0.0:8080 (Camel's default embedded
# server). The engine reaches the bus at http://esb:8080 (see docker-compose.yml).
EXPOSE 8080

# First start may resolve a few component jars (platform-http, http) before the
# HTTP server is ready — give it a moment after `docker compose up`.
CMD ["run", "--source-dir=/routes"]
