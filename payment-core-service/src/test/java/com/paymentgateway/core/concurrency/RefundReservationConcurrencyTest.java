package com.paymentgateway.core.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL concurrency proof for the refund reservation pattern used by
 * PaymentPersistenceService: lock the payment aggregate, sum every reserved
 * refund state, then reserve only when the total remains within captured funds.
 */
@Testcontainers(disabledWithoutDocker = true)
class RefundReservationConcurrencyTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    /**
     * Starts database.
     *
     * @throws Exception if  processing fails
     */
    @BeforeAll
    static void startDatabase() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE payment(payment_id UUID PRIMARY KEY, amount_minor BIGINT NOT NULL, status VARCHAR(40) NOT NULL)");
            s.execute("CREATE TABLE payment_refund(refund_id UUID PRIMARY KEY, payment_id UUID NOT NULL REFERENCES payment(payment_id), amount_minor BIGINT NOT NULL, status VARCHAR(40) NOT NULL)");
            s.execute("CREATE TABLE payment_provider_attempt_counter(payment_id UUID PRIMARY KEY REFERENCES payment(payment_id), last_attempt_no INTEGER NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        }
    }


    /**
     * Executes the two concurrent seventy percent refunds cannot over refund operation.
     *
     * @throws Exception if  processing fails
     */
    @Test
    void twoConcurrentSeventyPercentRefundsCannotOverRefund() throws Exception {
        UUID paymentId = UUID.randomUUID();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO payment(payment_id,amount_minor,status) VALUES (?,?,?)")) {
            ps.setObject(1, paymentId);
            ps.setLong(2, 100_000L);
            ps.setString(3, "SUCCEEDED");
            ps.executeUpdate();
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> a = pool.submit(() -> reserve(paymentId, 70_000L, ready, start));
            Future<Boolean> b = pool.submit(() -> reserve(paymentId, 70_000L, ready, start));
            ready.await();
            start.countDown();

            int accepted = (a.get() ? 1 : 0) + (b.get() ? 1 : 0);
            assertEquals(1, accepted, "Exactly one reservation may fit within the payment amount");
        }

        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(amount_minor),0) FROM payment_refund WHERE payment_id=? AND status IN ('REQUESTED','PENDING','UNKNOWN','SUCCEEDED')")) {
            ps.setObject(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertTrue(rs.getLong(1) <= 100_000L, "Reserved refunds must never exceed captured funds");
            }
        }
    }

    /**
     * Executes the atomic provider attempt allocator never returns duplicate attempt numbers operation.
     *
     * @throws Exception if  processing fails
     */
    @Test
    void atomicProviderAttemptAllocatorNeverReturnsDuplicateAttemptNumbers() throws Exception {
        UUID paymentId = UUID.randomUUID();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO payment(payment_id,amount_minor,status) VALUES (?,?,?)")) {
            ps.setObject(1, paymentId);
            ps.setLong(2, 1_000L);
            ps.setString(3, "PENDING");
            ps.executeUpdate();
        }

        int calls = 16;
        CountDownLatch ready = new CountDownLatch(calls);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(calls)) {
            java.util.List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < calls; i++) {
                futures.add(pool.submit(() -> { ready.countDown(); start.await(); return allocateAttempt(paymentId); }));
            }
            ready.await();
            start.countDown();
            java.util.Set<Integer> numbers = new java.util.HashSet<>();
            for (Future<Integer> future : futures) numbers.add(future.get());
            assertEquals(calls, numbers.size());
            for (int i = 1; i <= calls; i++) assertTrue(numbers.contains(i));
        }
    }

    /**
     * Executes the allocate attempt operation.
     *
     * @param paymentId the payment identifier
     * @return the resulting allocate attempt value
     * @throws Exception if  processing fails
     */
    private static int allocateAttempt(UUID paymentId) throws Exception {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO payment_provider_attempt_counter(payment_id,last_attempt_no) VALUES (?,1)
                ON CONFLICT(payment_id) DO UPDATE
                SET last_attempt_no=payment_provider_attempt_counter.last_attempt_no+1, updated_at=now()
                RETURNING last_attempt_no
                """)) {
            ps.setObject(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    /**
     * Executes the reserve operation.
     *
     * @param paymentId the payment identifier
     * @param requested the requested value
     * @param ready the ready value
     * @param start the start value
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     * @throws Exception if  processing fails
     */
    private static boolean reserve(UUID paymentId, long requested, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        start.await();
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                long amount;
                try (PreparedStatement lock = c.prepareStatement(
                        "SELECT amount_minor FROM payment WHERE payment_id=? FOR UPDATE")) {
                    lock.setObject(1, paymentId);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) throw new IllegalStateException("payment missing");
                        amount = rs.getLong(1);
                    }
                }
                long reserved;
                try (PreparedStatement sum = c.prepareStatement(
                        "SELECT COALESCE(SUM(amount_minor),0) FROM payment_refund WHERE payment_id=? AND status IN ('REQUESTED','PENDING','UNKNOWN','SUCCEEDED')")) {
                    sum.setObject(1, paymentId);
                    try (ResultSet rs = sum.executeQuery()) {
                        rs.next();
                        reserved = rs.getLong(1);
                    }
                }
                if (Math.addExact(reserved, requested) > amount) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO payment_refund(refund_id,payment_id,amount_minor,status) VALUES (?,?,?,'REQUESTED')")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, paymentId);
                    insert.setLong(3, requested);
                    insert.executeUpdate();
                }
                c.commit();
                return true;
            }
            catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * Executes the connection operation.
     *
     * @return the resulting {@code Connection} value
     * @throws Exception if  processing fails
     */
    private static Connection connection() throws Exception {
        return java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
