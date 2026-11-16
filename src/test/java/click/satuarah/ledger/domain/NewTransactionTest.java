package click.satuarah.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewTransactionTest {

    private static NewTransaction sale(String key, long amount) {
        return new NewTransaction(
                key, "Pro plan, 3 months", null, List.of(Posting.debit("BANK", amount), Posting.credit("DEFERRED", amount)));
    }

    private static ErrorCode codeOf(Runnable action) {
        return assertThrows(LedgerException.class, action::run).code();
    }

    @Test
    void aBalancedTransactionIsAccepted() {
        NewTransaction tx = sale("k1", 189_000);
        assertEquals(2, tx.postings().size());
    }

    @Test
    void debitsMustEqualCredits() {
        List<Posting> postings = List.of(Posting.debit("BANK", 100), Posting.credit("DEFERRED", 99));
        assertEquals(ErrorCode.UNBALANCED, codeOf(() -> new NewTransaction("k", "x", null, postings)));
    }

    @Test
    void severalLinesCanShareASide() {
        List<Posting> postings =
                List.of(Posting.debit("BANK", 90), Posting.debit("FEES", 10), Posting.credit("DEFERRED", 100));
        assertEquals(3, new NewTransaction("k", "split", null, postings).postings().size());
    }

    @Test
    void aTransactionNeedsAtLeastTwoLines() {
        List<Posting> one = List.of(Posting.debit("BANK", 1));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> new NewTransaction("k", "x", null, one)));
    }

    @Test
    void aTransactionHasAnUpperBoundOnLines() {
        List<Posting> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(Posting.debit("A", 1));
            many.add(Posting.credit("B", 1));
        }
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> new NewTransaction("k", "x", null, many)));
    }

    @Test
    void amountsMustBePositiveAndBounded() {
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> Posting.debit("BANK", 0)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> Posting.debit("BANK", -5)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> Posting.credit("BANK", Posting.MAX_AMOUNT + 1)));
    }

    @Test
    void postingsNeedAValidAccountCode() {
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> Posting.debit("bad code", 1)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> Posting.debit(null, 1)));
    }

    @Test
    void theDescriptionIsRequiredAndBounded() {
        List<Posting> postings = List.of(Posting.debit("A", 1), Posting.credit("B", 1));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> new NewTransaction("k", " ", null, postings)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> new NewTransaction("k", null, null, postings)));
        assertEquals(
                ErrorCode.INVALID_REQUEST, codeOf(() -> new NewTransaction("k", "x".repeat(501), null, postings)));
    }

    @Test
    void theIdempotencyKeyIsPrintableAsciiWithoutSpaces() {
        List<Posting> postings = List.of(Posting.debit("A", 1), Posting.credit("B", 1));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> new NewTransaction(null, "x", null, postings)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> new NewTransaction("", "x", null, postings)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> new NewTransaction("has space", "x", null, postings)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> new NewTransaction("café", "x", null, postings)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> new NewTransaction("k".repeat(256), "x", null, postings)));
    }

    @Test
    void theFingerprintIgnoresTheKeyAndSeesEverythingElse() {
        assertEquals(sale("one", 100).fingerprint(), sale("two", 100).fingerprint());
        assertNotEquals(sale("one", 100).fingerprint(), sale("one", 101).fingerprint());

        List<Posting> postings = List.of(Posting.debit("BANK", 100), Posting.credit("DEFERRED", 100));
        String base = new NewTransaction("k", "a", null, postings).fingerprint();
        assertNotEquals(base, new NewTransaction("k", "b", null, postings).fingerprint());
        assertNotEquals(base, new NewTransaction("k", "a", Instant.parse("2026-01-01T00:00:00Z"), postings).fingerprint());
        List<Posting> flipped = List.of(Posting.credit("BANK", 100), Posting.debit("DEFERRED", 100));
        assertNotEquals(base, new NewTransaction("k", "a", null, flipped).fingerprint());
    }

    @Test
    void theFingerprintIsAHexSha256() {
        assertEquals(64, sale("k", 1).fingerprint().length());
    }

    @Test
    void postingsAreCopiedSoTheCallerCannotChangeThemLater() {
        List<Posting> postings = new ArrayList<>(List.of(Posting.debit("A", 5), Posting.credit("B", 5)));
        NewTransaction tx = new NewTransaction("k", "x", null, postings);
        postings.clear();
        assertEquals(2, tx.postings().size());
    }

    @Test
    void aReversalRequestIsValidatedAndFingerprinted() {
        java.util.UUID id = java.util.UUID.randomUUID();
        ReversalRequest a = new ReversalRequest(id, "k", "duplicate payment");
        assertEquals(a.fingerprint(), new ReversalRequest(id, "other", "duplicate payment").fingerprint());
        assertNotEquals(a.fingerprint(), new ReversalRequest(id, "k", "wrong amount").fingerprint());
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> new ReversalRequest(null, "k", "r")));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> new ReversalRequest(id, "k", " ")));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> new ReversalRequest(id, "k", "r".repeat(301))));
    }
}
