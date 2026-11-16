package click.satuarah.ledger.web;

import click.satuarah.ledger.domain.AccountType;
import click.satuarah.ledger.domain.LedgerException;
import click.satuarah.ledger.domain.NewAccount;
import click.satuarah.ledger.domain.NewTransaction;
import click.satuarah.ledger.domain.Posting;
import click.satuarah.ledger.domain.ReversalRequest;
import click.satuarah.ledger.domain.Side;
import click.satuarah.ledger.web.ApiModels.OpenAccountRequest;
import click.satuarah.ledger.web.ApiModels.PostTransactionRequest;
import click.satuarah.ledger.web.ApiModels.PostingRequest;
import click.satuarah.ledger.web.ApiModels.ReverseRequestBody;
import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Turns what a client sent into domain requests, refusing anything that is not exactly right. */
final class RequestParsing {

    private RequestParsing() {}

    static NewAccount account(OpenAccountRequest body) {
        if (body == null) {
            throw LedgerException.invalid("a request body is required");
        }
        return new NewAccount(
                body.code(),
                body.name(),
                enumOf(AccountType.class, body.type(), "type"),
                body.currency(),
                Boolean.TRUE.equals(body.allowNegative()));
    }

    static NewTransaction transaction(String idempotencyKey, PostTransactionRequest body) {
        if (body == null) {
            throw LedgerException.invalid("a request body is required");
        }
        if (body.postings() == null) {
            throw LedgerException.invalid("postings are required");
        }
        List<Posting> postings = new ArrayList<>();
        for (PostingRequest line : body.postings()) {
            if (line == null) {
                throw LedgerException.invalid("postings must not contain null");
            }
            postings.add(new Posting(line.account(), enumOf(Side.class, line.side(), "side"), amount(line.amount())));
        }
        return new NewTransaction(idempotencyKey, body.description(), instant(body.occurredAt(), "occurredAt"), postings);
    }

    static ReversalRequest reversal(String id, String idempotencyKey, ReverseRequestBody body) {
        if (body == null) {
            throw LedgerException.invalid("a request body is required");
        }
        return new ReversalRequest(uuid(id), idempotencyKey, body.reason());
    }

    static String requireKey(String header) {
        if (header == null || header.isBlank()) {
            throw LedgerException.invalid("the Idempotency-Key header is required");
        }
        return header;
    }

    static UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw LedgerException.invalid("not a valid transaction id: " + text);
        }
    }

    static Instant instant(String text, String field) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw LedgerException.invalid(field + " must be an ISO-8601 instant such as 2026-11-16T08:00:00Z");
        }
    }

    /** Whole numbers only. A decimal such as 100.5, or a string, is refused rather than rounded. */
    static long amount(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger big && big.bitLength() < 64) {
            return big.longValue();
        }
        throw LedgerException.invalid("amount must be a whole number of minor units");
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String field) {
        if (value != null) {
            try {
                return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // falls through to the error below
            }
        }
        throw LedgerException.invalid(field + " must be one of " + Arrays.toString(type.getEnumConstants()));
    }
}
