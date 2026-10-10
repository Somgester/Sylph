#!/usr/bin/env bash
set -euo pipefail

sylph_use_jdk() {
  local jdk_home="$1" java_version javac_version
  [[ -x "$jdk_home/bin/java" && -x "$jdk_home/bin/javac" ]] || return 1
  java_version="$("$jdk_home/bin/java" -version 2>&1)" || return 1
  javac_version="$("$jdk_home/bin/javac" -version 2>&1)" || return 1
  [[ "$java_version" =~ version\ \"21([.\"]|$) ]] || return 1
  [[ "$javac_version" =~ javac[[:space:]]21([.[:space:]]|$) ]] || return 1
  export JAVA_HOME="$jdk_home"
  export PATH="$JAVA_HOME/bin:$PATH"
}

sylph_find_path_jdk() {
  local java_settings line
  command -v java >/dev/null 2>&1 || return 1
  java_settings="$(java -XshowSettings:properties -version 2>&1)" || return 1
  while IFS= read -r line; do
    if [[ "$line" =~ ^[[:space:]]*java\.home[[:space:]]*=[[:space:]]*(.*)$ ]]; then
      sylph_use_jdk "${BASH_REMATCH[1]}"
      return $?
    fi
  done <<< "$java_settings"
  return 1
}

sylph_report_setup_success() {
  if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    echo "$1"
    echo "Next, run ./dev.sh for lint, tests, and the app, or ./run.sh to launch the app only."
  fi
}

sylph_initialize() {
  local script_dir proto_home
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)" || return 1
  cd "$script_dir" || return 1

  if [[ -n "${JAVA_HOME:-}" ]] && sylph_use_jdk "$JAVA_HOME"; then
    sylph_report_setup_success "JDK 21 is already installed. Skipping installation."
    return 0
  fi
  if sylph_find_path_jdk; then
    sylph_report_setup_success "JDK 21 is already installed. Skipping installation."
    return 0
  fi

  proto_home="${PROTO_HOME:-$HOME/.proto}"
  export PATH="$proto_home/bin:$proto_home/shims:$PATH"
  if sylph_find_path_jdk; then
    sylph_report_setup_success "JDK 21 is already installed. Skipping installation."
    return 0
  fi

  if ! command -v proto >/dev/null 2>&1; then
    echo "No working JDK 21 or proto found." >&2
    echo "Install JDK 21 and set JAVA_HOME, or install proto from https://moonrepo.dev/docs/proto/install" >&2
    echo "Then rerun init.sh. Setup does not execute a remote installer script." >&2
    return 1
  fi
  echo "No working JDK 21 found. Setting up Java from .prototools..."
  proto install jdk || return 1
  if ! sylph_find_path_jdk; then
    echo "Java setup failed: a complete JDK 21 is still unavailable." >&2
    return 1
  fi
  sylph_report_setup_success "JDK 21 setup completed successfully."
}

sylph_initialize
  