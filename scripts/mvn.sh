#!/usr/bin/env bash
# Runs the Maven wrapper inside a JDK 21 container, so nobody needs a local JDK.
# The Maven cache lives in ~/.m2 on the host and files are written as your own user.
# label=disable: on SELinux hosts (Fedora) bind mounts are otherwise unreadable inside the container.
#   scripts/mvn.sh -DskipTests package
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p "$HOME/.m2"
exec docker run --rm --security-opt label=disable \
  --user "$(id -u):$(id -g)" \
  -v "$PWD":/workspace -w /workspace \
  -v "$HOME/.m2":/m2 -e MAVEN_USER_HOME=/m2 \
  -v /var/run/docker.sock:/var/run/docker.sock --group-add "$(stat -c %g /var/run/docker.sock)" \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --add-host=host.docker.internal:host-gateway \
  -e TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=true \
  eclipse-temurin:21-jdk \
  ./mvnw -B -Dmaven.repo.local=/m2/repository "$@"
