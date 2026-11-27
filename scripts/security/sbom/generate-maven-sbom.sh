#!/usr/bin/env bash
set -euo pipefail

workspace="$(git rev-parse --show-toplevel)"
cd "$workspace"
mkdir -p target/sbom
maven_repository="$workspace/target/sbom/maven-repository"
maven_dependency_plugin="$(node scripts/security/run-security-gate.mjs policy --get tools.mavenDependencyPlugin)"
cyclonedx_maven_plugin="$(node scripts/security/run-security-gate.mjs policy --get tools.cyclonedxMavenPlugin)"

mvn -B -C -DskipTests "-Dmaven.repo.local=$maven_repository" install

while IFS= read -r -d '' pom; do
  module_dir="$(dirname "$pom")"
  mkdir -p "$module_dir/target"
  tree_output="$workspace/${module_dir#./}/target/maven-dependency-tree.json"
  plugin_output="$workspace/${module_dir#./}/target/maven-plugin-dependencies.txt"

  mvn -B -C -N -DskipTests "-Dmaven.repo.local=$maven_repository" -f "$pom" \
    "org.apache.maven.plugins:maven-dependency-plugin:${maven_dependency_plugin}:tree" \
    -DoutputType=json \
    "-DoutputFile=$tree_output"
  mvn -B -C -N -DskipTests "-Dmaven.repo.local=$maven_repository" -f "$pom" \
    "org.apache.maven.plugins:maven-dependency-plugin:${maven_dependency_plugin}:resolve-plugins" \
    "-DoutputFile=$plugin_output"
done < <(node scripts/security/run-security-gate.mjs maven-reactor-list)

mvn -B -C -DskipTests "-Dmaven.repo.local=$maven_repository" \
  -Dproject.build.outputTimestamp=0 \
  "org.cyclonedx:cyclonedx-maven-plugin:${cyclonedx_maven_plugin}:makeAggregateBom" \
  -DschemaVersion=1.6 \
  -DoutputFormat=json \
  -DoutputName=maven-resolved \
  -DoutputDirectory=target/sbom \
  -DincludeBomSerialNumber=false \
  -DincludeTestScope=true \
  -DoutputReactorProjects=false

mv target/sbom/maven-resolved.json target/sbom/maven-resolved.cdx.json
