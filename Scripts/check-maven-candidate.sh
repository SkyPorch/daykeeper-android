#!/usr/bin/env bash
set -euo pipefail

version="${1:-}"
semver_identifier='(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)'
semver_regex="^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-${semver_identifier}(\\.${semver_identifier})*)?$"
if [[ ! "${version}" =~ ${semver_regex} ]]; then
  echo "usage: $0 <exact-semver>" >&2
  exit 1
fi
if [[ "${version}" =~ [Ss][Nn][Aa][Pp][Ss][Hh][Oo][Tt] ]]; then
  echo "release candidate must not be a snapshot" >&2
  exit 1
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository="${root}/build/repository/io/github/skyporch"
temporary="$(mktemp -d)"
trap 'rm -rf "${temporary}"' EXIT

require_file() {
  if [[ ! -f "$1" ]]; then
    echo "missing Maven candidate file: $1" >&2
    exit 1
  fi
}

require_text() {
  if ! grep -Fq -- "$2" "$1"; then
    echo "missing required Maven metadata in $1: $2" >&2
    exit 1
  fi
}

verify_module() {
  local artifact="$1"
  local license="$2"
  local directory="${repository}/${artifact}/${version}"
  local prefix="${directory}/${artifact}-${version}"
  require_file "${prefix}.aar"
  require_file "${prefix}.pom"
  require_file "${prefix}.module"
  require_file "${prefix}-sources.jar"
  require_file "${prefix}-javadoc.jar"

  local pom="${prefix}.pom"
  require_text "${pom}" "<groupId>io.github.skyporch</groupId>"
  require_text "${pom}" "<artifactId>${artifact}</artifactId>"
  require_text "${pom}" "<version>${version}</version>"
  require_text "${pom}" "<packaging>aar</packaging>"
  require_text "${pom}" "<name>Daykeeper"
  require_text "${pom}" "<inceptionYear>2026</inceptionYear>"
  require_text "${pom}" "<name>MIT License</name>"
  require_text "${pom}" "<distribution>repo</distribution>"
  require_text "${pom}" "<id>skyporch</id>"
  require_text "${pom}" "<developerConnection>scm:git:ssh://git@github.com/SkyPorch/daykeeper-android.git</developerConnection>"

  require_text "${prefix}.module" "\"group\": \"io.github.skyporch\""
  require_text "${prefix}.module" "\"module\": \"${artifact}\""
  require_text "${prefix}.module" "\"version\": \"${version}\""

  unzip -Z1 "${prefix}-sources.jar" > "${temporary}/${artifact}-sources.txt"
  grep -Fxq "META-INF/LICENSE" "${temporary}/${artifact}-sources.txt"
  unzip -p "${prefix}.aar" classes.jar > "${temporary}/${artifact}.jar"
  unzip -Z1 "${temporary}/${artifact}.jar" > "${temporary}/${artifact}-classes.txt"
  grep -Fxq "META-INF/${license}" "${temporary}/${artifact}-classes.txt"

  local candidate
  for candidate in "${prefix}.aar" "${prefix}.pom" "${prefix}.module" \
    "${prefix}-sources.jar" "${prefix}-javadoc.jar"; do
    local expected
    expected="$(<"${candidate}.sha256")"
    local actual
    actual="$(sha256sum "${candidate}" | cut -d' ' -f1)"
    if [[ "${actual}" != "${expected}" ]]; then
      echo "invalid SHA-256 sidecar for ${candidate}" >&2
      exit 1
    fi
  done
}

verify_module "daykeeper-android" "LICENSE.daykeeper-android"
verify_module "daykeeper-android-ui" "LICENSE.daykeeper-android-ui"

ui_pom="${repository}/daykeeper-android-ui/${version}/daykeeper-android-ui-${version}.pom"
require_text "${ui_pom}" "<artifactId>daykeeper-android</artifactId>"
require_text "${ui_pom}" "<version>${version}</version>"

if find "${repository}" -type f -name '*SNAPSHOT*' -print -quit | grep -q .; then
  echo "snapshot file leaked into the exact release candidate" >&2
  exit 1
fi

echo "Exact Maven candidate metadata, archives, licenses and SHA-256 sidecars passed."
