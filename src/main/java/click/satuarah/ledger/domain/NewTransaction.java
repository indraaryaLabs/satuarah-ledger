package click.satuarah.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * A request to record a transaction. Constructing one proves the parts that do not need the
 * database: it has at least two lines, every amount is valid, and debits equal credits.
 *
 * <p>{@code occurredAt} is when the event happened, and may be null to mean "when it is recorded".
 */
public record NewTransaction(String idempotencyKey, String description, Instant occurredAt, List<Posting> postings) {

    public static final int MAX_POSTINGS = 100;
    private static final int MAX_KEY_LENGTH = 255;

    public NewTransaction {
        requireKey(idempotencyKey);
        if (description == null || description.isBlank() || description.length() > 500) {
            throw LedgerException.invalid("description must be 1 to 500 characters");
        }
        if (postings == null || postings.size() < 2 || postings.size() > MAX_POSTINGS) {
            throw LedgerException.invalid("a transaction needs between 2 and " + MAX_POSTINGS + " postings");
        }
        postings = List.copyOf(postings);
        long debits = 0;
        long credits = 0;
        for (Posting posting : postings) {
            if (posting == null) {
                throw LedgerException.invalid("postings must not contain null");
            }
            if (posting.side() == Side.DEBIT) {
                debits = Math.addExact(debits, posting.amount());
            } else {
                credits = Math.addExact(credits, posting.amount());
            }
        }
        if (debits != credits) {
            throw new LedgerException(
                    ErrorCode.UNBALANCED, "debits " + debits + " do not equal credits " + credits);
        }
    }

    static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw LedgerException.invalid("idempotency key must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                throw LedgerException.invalid("idempotency key must be printable ASCII without spaces");
            }
        }
    }

    /**
     * A digest of everything the client chose, so that a retry with the same key can be told apart
     * from a different request that reuses the key. The key itself is not part of it.
     */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder("tx\n");
        canonical.append(description).append('\n');
        canonical.append(occurredAt == null ? "-" : occurredAt.toString()).append('\n');
        for (Posting p : postings) {
            canonical.append(p.account()).append('|').append(p.side()).append('|').append(p.amount()).append('\n');
        }
        return sha256(canonical.toString());
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
