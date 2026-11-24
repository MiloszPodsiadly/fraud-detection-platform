#!/bin/sh
set -eu

java_home=$JAVA_HOME
if [ -z "$java_home" ] || [ ! -x "$java_home/bin/java" ]; then
  echo "Java runtime policy failed: JAVA_HOME/bin/java is unavailable" >&2
  exit 1
fi

selected_java=$(command -v java || true)
if [ -z "$selected_java" ]; then
  echo "Java runtime policy failed: java is unavailable on PATH" >&2
  exit 1
fi

canonical() {
  readlink -f "$1"
}

if [ "$(canonical "$selected_java")" != "$(canonical "$java_home/bin/java")" ]; then
  echo "Java runtime policy failed: PATH java is outside JAVA_HOME" >&2
  exit 1
fi

properties_file=$(mktemp)
trap 'rm -f "$properties_file"' EXIT HUP INT TERM
if ! "$selected_java" -XshowSettings:properties -version >"$properties_file" 2>&1; then
  echo "Java runtime policy failed: JVM properties could not be read" >&2
  exit 1
fi

property() {
  grep -F "$1 =" "$properties_file" | sed -n 's/^[[:space:]]*[^=]*=[[:space:]]*//p' | sed -n '1p'
}

vendor=$(property java.vendor)
vm_vendor=$(property java.vm.vendor)
feature=$(property java.specification.version)
version=$(property java.version)
runtime=$(property java.runtime.version)
vm_version=$(property java.vm.version)
reported_home=$(property java.home)

if [ "$vendor" != "Eclipse Adoptium" ] || [ "$vm_vendor" != "Eclipse Adoptium" ]; then
  echo "Java runtime policy failed: expected Eclipse Adoptium, got $vendor / $vm_vendor" >&2
  exit 1
fi
if [ "$feature" != "21" ] || [ "$version" != "21.0.13.1" ]; then
  echo "Java runtime policy failed: expected Java 21.0.13.1, got $feature / $version" >&2
  exit 1
fi
case "$runtime" in
  21.0.13.1+2|21.0.13.1+2-LTS) ;;
  *) echo "Java runtime policy failed: unapproved runtime build $runtime" >&2; exit 1 ;;
esac
case "$vm_version" in
  21.0.13.1+2|21.0.13.1+2-LTS) ;;
  *) echo "Java runtime policy failed: unapproved VM build $vm_version" >&2; exit 1 ;;
esac
if [ -z "$reported_home" ] || [ "$(canonical "$reported_home")" != "$(canonical "$java_home")" ]; then
  echo "Java runtime policy failed: java.home does not match JAVA_HOME" >&2
  exit 1
fi

echo "Verified Java runtime: $vendor $runtime at $reported_home"