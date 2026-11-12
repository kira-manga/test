# Inspect installed commerce route metadata

This opt-in diagnostic captures the actual `LocalBranchCommerceRegistry` metadata
with explicit unavailable collaborators. It invokes no handler, authorizer,
history join, native factory, transaction or RPC. Private reflection reads only
the registry's installed producer list and the closed rejection-profile enums.
The resulting data is an inventory, not a security or behavior test.

From `apps/pos`, with JDK21 and the normal project build prerequisites, obtain a
fresh JVM runtime classpath. Choose an output directory outside tracked sources;
the tools refuse to overwrite existing capture files.

```sh
./gradlew --init-script ../../scripts/pos/route_inventory/classpath.init.gradle \
  -PposInventoryClasspathOutput=/absolute/output/classpath.txt \
  :adapters:catalog-store:posRouteInventoryClasspath
```

From the repository root, create a class list containing one qualified enum class
per line for the inspected `RuntimeHistorical*RejectionProfile.kt` files. Include
only files declaring an enum with that exact filename; inspect a new profile shape
before adding it. The probe rejects other class-name patterns.

```sh
javac -d /absolute/output/probe-classes \
  scripts/pos/route_inventory/RegistryMetadataProbe.java
java -cp "/absolute/output/probe-classes:$(cat /absolute/output/classpath.txt)" \
  RegistryMetadataProbe /absolute/output/registry-metadata.json \
  /absolute/output/rejection-profile-classes.txt
python3 scripts/pos/route_inventory/build_inventory.py \
  --metadata /absolute/output/registry-metadata.json \
  --output-dir qa/pos/traceability/<new-record-directory>
```

On Windows the classpath separator is `;`. Keep the runtime classpath, machine
configuration and raw build logs local. Publish only project route metadata,
relative source locations/hashes, commands, observed counts and limits. The
renderer expects its report directory under `qa/pos/traceability/` so source links
have the correct relative depth.

The renderer binds all command/read handler classes, producers, enum candidates
and five native-original interpreters to current source. It also parses literal
business RPC procedure declarations and checks that their client profile mapping
matches the actual Host registration call sites. Key selectors are not typed
payload validation. Writable kinds/namespaces are limits, not observed effects;
registered producers still have narrower subtype and source requirements.

The task compiles the normal JVM dependencies without running tests. It is not a
required build gate. Historical inventories remain source-specific evidence after
sources change; generate a successor inventory when a current census is needed.
