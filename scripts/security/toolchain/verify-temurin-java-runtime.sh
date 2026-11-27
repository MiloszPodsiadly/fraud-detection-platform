#!/bin/sh
set -eu

policy_file=${JAVA_RUNTIME_POLICY_FILE:-/usr/local/share/fdp/java-runtime-policy.properties}
if [ ! -r "$policy_file" ]; then
  echo "Java runtime policy failed: approved runtime policy file is unavailable" >&2
  exit 1
fi

policy_value() {
  awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$policy_file"
}

policy_vendor=$(policy_value application.vendor)
policy_feature=$(policy_value application.feature)
policy_version=$(policy_value application.version)
policy_runtime_versions=$(policy_value application.runtime.versions)
policy_vm_versions=$(policy_value application.vm.versions)
policy_vendor_versions=$(policy_value application.vendor.versions)
if [ -z "$policy_vendor" ] || [ -z "$policy_feature" ] || [ -z "$policy_version" ] \
  || [ -z "$policy_runtime_versions" ] || [ -z "$policy_vm_versions" ] || [ -z "$policy_vendor_versions" ]; then
  echo "Java runtime policy failed: approved runtime policy is incomplete" >&2
  exit 1
fi

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
vendor_version=$(property java.vendor.version)
reported_home=$(property java.home)

if [ "$vendor" != "$policy_vendor" ] || [ "$vm_vendor" != "$policy_vendor" ]; then
  echo "Java runtime policy failed: expected $policy_vendor, got $vendor / $vm_vendor" >&2
  exit 1
fi
if [ "$feature" != "$policy_feature" ] || [ "$version" != "$policy_version" ]; then
  echo "Java runtime policy failed: expected Java $policy_version, got $feature / $version" >&2
  exit 1
fi
case ",$policy_runtime_versions," in *",$runtime,"*) ;; *) echo "Java runtime policy failed: unapproved runtime build $runtime" >&2; exit 1 ;; esac
case ",$policy_vm_versions," in *",$vm_version,"*) ;; *) echo "Java runtime policy failed: unapproved VM build $vm_version" >&2; exit 1 ;; esac
case ",$policy_vendor_versions," in *",$vendor_version,"*) ;; *) echo "Java runtime policy failed: unapproved vendor build $vendor_version" >&2; exit 1 ;; esac
if [ -z "$reported_home" ] || [ "$(canonical "$reported_home")" != "$(canonical "$java_home")" ]; then
  echo "Java runtime policy failed: java.home does not match JAVA_HOME" >&2
  exit 1
fi

echo "Verified Java runtime: $vendor $runtime at $reported_home"
