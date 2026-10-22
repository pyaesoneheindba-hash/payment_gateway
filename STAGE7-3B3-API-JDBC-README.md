# Stage 7.3B3 API JDBC constructor fixture fix

Error: `Property 'dataSource' is required` when creating the test `isolatedJdbc` bean.

Cause: `new JdbcTemplate()` creates an instance without DataSource and Spring 7
validates JdbcAccessor when the bean is initialized. This failure is in the
*test-only* `Stage7PaymentApiChild`, not the production checkout service.

Fix: `new JdbcTemplate(Mockito.mock(javax.sql.DataSource.class))` inside this
specific test-only bean. The mock cannot establish a database connection.
Only the `QR_STRING` checkout flow is exercised here; it calls the real
Dubbo `configureCheckout` RPC and does not use CheckoutSessionService JDBC.
The `HOSTED_PAGE` API JDBC path is not covered by this patch; it will require
real disposable DB connectivity in its own test.

Procedure: extract this ZIP into the payment gateway repository root,
then run `& .\stage7-3b3\patch-api-jdbc.ps1`, followed by
`& .\stage7-3b3\run-stage7-3b3.ps1`.

This patch creates a backup inside `stage7-drafts` and changes no production
source files, POMs, Docker, Nacos, production database, or bank configuration.
It has not been executed on your Windows / JDK 27 machine yet.
