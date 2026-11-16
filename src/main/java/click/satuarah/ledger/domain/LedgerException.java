package click.satuarah.ledger.domain;

import java.util.Objects;

/** A business rule was broken. Never used for bugs: those stay as ordinary exceptions. */
public final class LedgerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public LedgerException(ErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode code() {
        return code;
    }

    public static LedgerException invalid(String message) {
        return new LedgerException(ErrorCode.INVALID_REQUEST, message);
    }
}
