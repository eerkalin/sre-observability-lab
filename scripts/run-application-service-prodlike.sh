#!/usr/bin/env bash

set -euo pipefail

cd "$(dirname "$0")/../app/application-service"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"

./mvnw spring-boot:run -Dspring-boot.run.profiles=prodlike
