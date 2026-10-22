# Stage 7.3B3 compilation fix

Symptom: `Stage7GatewaySignatureFilterTest`, `Stage7PaymentLifecycleTest`
and `Stage7MultiJvmPaymentFlowTest` fail to resolve classes from upstream
application modules after the runner builds with `mvn ... install -DskipTests`.

Cause: The Spring Boot `repackage` goal replaces ordinary JARs with executable
archives, so their classes move to `BOOT-INF/classes`. They cannot then
be used as normal Java compile dependencies by the Stage 7 module.

Fix: Install ordinary JARs to the *local Maven repository for the test
classpath setup only*, using the official Spring Boot Maven plugin parameter:

    -Dspring-boot.repackage.skip=true

This DOES NOT alter the production pom.xml or Java classes and DOES NOT
disable WebFlux, MVC, Triple RPC, or Triple REST. The final test command
runs without this property. A normal production `mvn package` without the
property still produces executable Spring Boot JARs.

Apply: Extract into the repository root, replacing only
`stage7-3b3/run-stage7-3b3.ps1`. No Java or POM changes.

Then run:

    & .\stage7-3b3\run-stage7-3b3.ps1

This fix has NOT been run or verified on the user's Windows/JDK 27 setup.
The subsequent test may reveal independent runtime or compilation errors.
Do not contact bank providers, production webhooks, Nacos or existing
PostgreSQL; the script's test fixtures use local/disposable resources.
Do not commit before verifying the test output.

Reference: Spring Boot Maven plugin `RepackageMojo.skip` property is
`spring-boot.repackage.skip`.
