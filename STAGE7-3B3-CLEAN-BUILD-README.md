# Stage 7.3B3 — stale executable JAR / reactor compile correction

Your log shows 41 `cannot find symbol` errors for production classes from
api-gateway, payment-core-service, and notification-service. The runner
stopped before starting ANY application or PostgreSQL test container.

Likely root cause: the prior `mvn package -DskipTests` repackaged Boot apps
as executable JARs. A later incremental `mvn install` with
`-Dspring-boot.repackage.skip=true` might reuse the **already generated**
executable JAR files in `target/`. Those JARs have classes under
`BOOT-INF/classes`, and cannot be consumed as ordinary Maven dependencies.

The new runner performs:

  mvn -pl api-gateway,payment-api-service,payment-core-service,stage7-integration-tests -am clean install -DskipTests -Dspring-boot.repackage.skip=true

The important correction is **clean** before building the ordinary JARs.
It then verifies the layout of three actual reactor JARs using the Java
`jar tf` tool. If the class files aren't at the JAR root, it stops BEFORE
spawning any integration processes, with a useful error.

This DOES NOT disable any Dubbo Triple REST, Gateway WebFlux, security,
or payment functionality. The `spring-boot.repackage.skip` flag only
selects ordinary Maven dependency artifact layout for the test-build step;
production packaging using a normal `mvn package` has no such flag.

## Impact

`clean` deletes **local target/ build outputs and saved Surefire reports**
for modules in this reactor. It does not remove Java source, pom.xml,
tracked Git files, production Docker stack, databases, or bank secrets.
The `install` phase updates only your **local** Maven `.m2/repository`
snapshot artifacts, not your Git repo.

## Apply

Unzip in repository root, overwriting ONLY:
`stage7-3b3/run-stage7-3b3.ps1`.
You may optionally back up the old runner to `stage7-drafts/` first.

In PowerShell:

  & .\stage7-3b3\run-stage7-3b3.ps1

Success at the build phase is characterized by:

  Verified ordinary test-dependency JAR: api-gateway
  Verified ordinary test-dependency JAR: payment-core-service
  Verified ordinary test-dependency JAR: notification-service

If compilation still fails after these checks, inspect the exact
class names in the JARs and the Maven test dependency resolution rather
than applying further flags.

This runner has not been executed with your JDK 27/Windows environment.
Do not commit it until compilation and integration behavior are confirmed.
