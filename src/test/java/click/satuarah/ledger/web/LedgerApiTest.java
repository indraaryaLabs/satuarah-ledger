package click.satuarah.ledger.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import click.satuarah.ledger.support.Http;
import click.satuarah.ledger.support.Http.Response;
import click.satuarah.ledger.support.PostgresSupport;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The whole service over real HTTP, on a real server, against real PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerApiTest {

    private static final AtomicInteger NEXT = new AtomicInteger();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresSupport::url);
        registry.add("spring.datasource.username", PostgresSupport::user);
        registry.add("spring.datasource.password", PostgresSupport::password);
    }

    @Value("${local.server.port}")
    private int port;

    private Http http;
    private String cash;
    private String revenue;
    private String deferred;

    @BeforeEach
    void setUp() {
        http = new Http(port);
        int n = NEXT.incrementAndGet();
        cash = "api-cash-" + n;
        revenue = "api-rev-" + n;
        deferred = "api-def-" + n;
        open(cash, "Bank BCA", "ASSET", false);
        open(revenue, "Subscription revenue", "REVENUE", false);
        open(deferred, "Deferred revenue", "LIABILITY", false);
    }

    private Response open(String code, String name, String type, boolean allowNegative) {
        Response r = http.post(
                "/v1/accounts",
                """
                {"code":"%s","name":"%s","type":"%s","currency":"IDR","allowNegative":%s}"""
                        .formatted(code, name, type, allowNegative));
        assertEquals(201, r.status(), r.body());
        return r;
    }

    private static String transfer(String debit, String credit, Object amount) {
        return """
                {"description":"Pro plan, November","postings":[
                  {"account":"%s","side":"DEBIT","amount":%s},
                  {"account":"%s","side":"CREDIT","amount":%s}]}"""
                .formatted(debit, amount, credit, amount);
    }

    private static String key() {
        return "api-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("opening an account returns it with a Location header")
    void opensAccount() {
        Response r = http.get("/v1/accounts/" + cash);
        assertEquals(200, r.status());
        assertEquals(cash, r.<String>json("$.code"));
        assertEquals("DEBIT", r.<String>json("$.normalSide"));
        assertEquals(0, r.<Integer>json("$.balance"));
    }

    @Test
    @DisplayName("an account code cannot be opened twice")
    void duplicateAccount() {
        Response r = http.post(
                "/v1/accounts", """
                {"code":"%s","name":"Again","type":"ASSET","currency":"IDR"}""".formatted(cash));
        assertEquals(409, r.status());
        assertEquals("ACCOUNT_EXISTS", r.<String>json("$.code"));
    }

    @Test
    @DisplayName("an unknown account is a 404 problem")
    void unknownAccount() {
        Response r = http.get("/v1/accounts/does-not-exist");
        assertEquals(404, r.status());
        assertEquals("ACCOUNT_NOT_FOUND", r.<String>json("$.code"));
        assertTrue(r.header("Content-Type").orElse("").contains("application/problem+json"));
    }

    @Test
    @DisplayName("posting a transaction moves both accounts and returns 201")
    void postsTransaction() {
        Response r = http.postWithKey("/v1/transactions", key(), transfer(cash, deferred, 189_000));
        assertEquals(201, r.status(), r.body());
        assertTrue(r.header("Location").orElse("").startsWith("/v1/transactions/"));
        assertEquals(2, r.<List<?>>json("$.lines").size());
        assertEquals(189_000, http.get("/v1/accounts/" + cash).<Integer>json("$.balance"));
        assertEquals(189_000, http.get("/v1/accounts/" + deferred).<Integer>json("$.balance"));
    }

    @Test
    @DisplayName("repeating a request with the same key returns the original and posts nothing twice")
    void replay() {
        String key = key();
        Response first = http.postWithKey("/v1/transactions", key, transfer(cash, deferred, 50_000));
        Response again = http.postWithKey("/v1/transactions", key, transfer(cash, deferred, 50_000));
        assertEquals(201, first.status());
        assertEquals(200, again.status());
        assertEquals("true", again.header("Idempotent-Replayed").orElse(""));
        assertEquals(first.<String>json("$.id"), again.<String>json("$.id"));
        assertEquals(50_000, http.get("/v1/accounts/" + cash).<Integer>json("$.balance"));
    }

    @Test
    @DisplayName("reusing a key for a different request is a conflict")
    void keyReusedForDifferentPayload() {
        String key = key();
        http.postWithKey("/v1/transactions", key, transfer(cash, deferred, 50_000));
        Response r = http.postWithKey("/v1/transactions", key, transfer(cash, deferred, 60_000));
        assertEquals(409, r.status());
        assertEquals("IDEMPOTENCY_CONFLICT", r.<String>json("$.code"));
        assertEquals(50_000, http.get("/v1/accounts/" + cash).<Integer>json("$.balance"));
    }

    @Test
    @DisplayName("the Idempotency-Key header is required")
    void keyRequired() {
        Response r = http.post("/v1/transactions", transfer(cash, deferred, 1_000));
        assertEquals(400, r.status());
        assertEquals("INVALID_REQUEST", r.<String>json("$.code"));
    }

    @Test
    @DisplayName("an unbalanced transaction is refused with 422")
    void unbalanced() {
        Response r = http.postWithKey(
                "/v1/transactions",
                key(),
                """
                {"description":"Lopsided","postings":[
                  {"account":"%s","side":"DEBIT","amount":100},
                  {"account":"%s","side":"CREDIT","amount":90}]}"""
                        .formatted(cash, revenue));
        assertEquals(422, r.status());
        assertEquals("UNBALANCED", r.<String>json("$.code"));
    }

    @Test
    @DisplayName("a fractional amount is refused, never rounded")
    void fractionalAmount() {
        Response r = http.postWithKey("/v1/transactions", key(), transfer(cash, deferred, "100.5"));
        assertEquals(400, r.status());
        assertEquals("INVALID_REQUEST", r.<String>json("$.code"));
    }

    @Test
    @DisplayName("an amount sent as a string is refused")
    void stringAmount() {
        Response r = http.postWithKey("/v1/transactions", key(), transfer(cash, deferred, "\"100\""));
        assertEquals(400, r.status());
    }

    @Test
    @DisplayName("malformed JSON is a 400 problem, not a stack trace")
    void malformedJson() {
        Response r = http.postWithKey("/v1/transactions", key(), "{not json");
        assertEquals(400, r.status());
        assertEquals("INVALID_REQUEST", r.<String>json("$.code"));
        assertFalse(r.body().contains("Exception"));
    }

    @Test
    @DisplayName("spending money an account does not have is refused with 422")
    void insufficientFunds() {
        Response r = http.postWithKey("/v1/transactions", key(), transfer(deferred, cash, 5_000));
        assertEquals(422, r.status());
        assertEquals("INSUFFICIENT_FUNDS", r.<String>json("$.code"));
    }

    @Test
    @DisplayName("a transaction is reversed once, by posting the opposite lines")
    void reversal() {
        String id = http.postWithKey("/v1/transactions", key(), transfer(cash, deferred, 189_000))
                .<String>json("$.id");

        String reversalKey = key();
        Response reversed =
                http.postWithKey("/v1/transactions/" + id + "/reversal", reversalKey, "{\"reason\":\"Customer refund\"}");
        assertEquals(201, reversed.status(), reversed.body());
        assertEquals(id, reversed.<String>json("$.reversalOf"));
        assertEquals(0, http.get("/v1/accounts/" + cash).<Integer>json("$.balance"));
        assertEquals(0, http.get("/v1/accounts/" + deferred).<Integer>json("$.balance"));

        Response replay =
                http.postWithKey("/v1/transactions/" + id + "/reversal", reversalKey, "{\"reason\":\"Customer refund\"}");
        assertEquals(200, replay.status());

        Response second =
                http.postWithKey("/v1/transactions/" + id + "/reversal", key(), "{\"reason\":\"Again\"}");
        assertEquals(409, second.status());
        assertEquals("ALREADY_REVERSED", second.<String>json("$.code"));
    }

    @Test
    @DisplayName("a reversal cannot itself be reversed")
    void reversalOfReversal() {
        String id = http.postWithKey("/v1/transactions", key(), transfer(cash, deferred, 10_000)).<String>json("$.id");
        String reversal = http.postWithKey("/v1/transactions/" + id + "/reversal", key(), "{\"reason\":\"Mistake\"}")
                .<String>json("$.id");
        Response r = http.postWithKey("/v1/transactions/" + reversal + "/reversal", key(), "{\"reason\":\"Undo\"}");
        assertEquals(422, r.status());
        assertEquals("NOT_REVERSIBLE", r.<String>json("$.code"));
    }

    @Test
    @DisplayName("a transaction can be read back by id")
    void readsTransaction() {
        String id = http.postWithKey("/v1/transactions", key(), transfer(cash, deferred, 2_000)).<String>json("$.id");
        Response r = http.get("/v1/transactions/" + id);
        assertEquals(200, r.status());
        assertEquals("Pro plan, November", r.<String>json("$.description"));
        assertEquals(404, http.get("/v1/transactions/" + UUID.randomUUID()).status());
        assertEquals(400, http.get("/v1/transactions/not-a-uuid").status());
    }

    @Test
    @DisplayName("a statement pages by cursor and shows the running balance")
    void statementPages() {
        for (int i = 1; i <= 5; i++) {
            http.postWithKey("/v1/transactions", key(), transfer(cash, deferred, i * 1_000));
        }
        Response first = http.get("/v1/accounts/" + cash + "/statement?limit=2");
        assertEquals(200, first.status());
        assertEquals(2, first.<List<?>>json("$.lines").size());
        assertEquals(1_000, first.<Integer>json("$.lines[0].balanceAfter"));
        assertEquals(3_000, first.<Integer>json("$.lines[1].balanceAfter"));
        Number cursor = first.json("$.nextAfter");
        assertNotNull(cursor);

        Response second = http.get("/v1/accounts/" + cash + "/statement?limit=2&after=" + cursor);
        assertEquals(6_000, second.<Integer>json("$.lines[0].balanceAfter"));

        Response last = http.get("/v1/accounts/" + cash + "/statement?limit=10&after=" + second.<Number>json("$.nextAfter"));
        assertEquals(1, last.<List<?>>json("$.lines").size());
        assertEquals(15_000, last.<Integer>json("$.lines[0].balanceAfter"));
        assertEquals(null, last.<Object>json("$.nextAfter"));
    }

    @Test
    @DisplayName("a statement limit out of range is refused")
    void statementLimit() {
        assertEquals(400, http.get("/v1/accounts/" + cash + "/statement?limit=0").status());
        assertEquals(400, http.get("/v1/accounts/" + cash + "/statement?limit=1000").status());
        assertEquals(400, http.get("/v1/accounts/" + cash + "/statement?limit=abc").status());
    }

    @Test
    @DisplayName("a balance can be read as of a moment in the past")
    void balanceAsOf() {
        http.postWithKey("/v1/transactions", key(), transfer(cash, deferred, 7_000));
        Response before = http.get("/v1/accounts/" + cash + "/balance?asOf=2000-01-01T00:00:00Z");
        assertEquals(0, before.<Integer>json("$.balance"));
        Response now = http.get("/v1/accounts/" + cash + "/balance");
        assertEquals(7_000, now.<Integer>json("$.balance"));
        assertEquals(400, http.get("/v1/accounts/" + cash + "/balance?asOf=yesterday").status());
    }

    @Test
    @DisplayName("the trial balance and the integrity report agree the books are sound")
    void reports() {
        http.postWithKey("/v1/transactions", key(), transfer(cash, deferred, 3_000));
        Response trial = http.get("/v1/reports/trial-balance");
        assertEquals(200, trial.status());
        assertTrue(trial.<Boolean>json("$.balanced"));
        Response integrity = http.get("/v1/reports/integrity");
        assertTrue(integrity.<Boolean>json("$.healthy"));
        assertEquals(0, integrity.<List<?>>json("$.discrepancies").size());
    }

    @Test
    @DisplayName("an unknown route is a 404 problem")
    void unknownRoute() {
        Response r = http.get("/v1/nothing-here");
        assertEquals(404, r.status());
        assertEquals("NOT_FOUND", r.<String>json("$.code"));
    }

    @Test
    @DisplayName("the health endpoint is up")
    void health() {
        Response r = http.get("/actuator/health");
        assertEquals(200, r.status());
        assertEquals("UP", r.<String>json("$.status"));
    }
}
