package click.satuarah.ledger.web;

import click.satuarah.ledger.domain.ErrorCode;
import click.satuarah.ledger.domain.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every failure leaves as RFC 9457 {@code application/problem+json} with a stable {@code code}
 * property, so a client can branch on the code and never has to parse the message.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case ACCOUNT_NOT_FOUND, TRANSACTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ACCOUNT_EXISTS, IDEMPOTENCY_CONFLICT, ALREADY_REVERSED -> HttpStatus.CONFLICT;
            case UNBALANCED, CURRENCY_MISMATCH, INSUFFICIENT_FUNDS, NOT_REVERSIBLE -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<ProblemDetail> ledger(LedgerException e) {
        HttpStatus status = statusOf(e.code());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(e.code().name());
        problem.setProperty("code", e.code().name());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("unhandled error", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side. The request was not applied.");
        problem.setProperty("code", "INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /** Framework-level failures (bad JSON, wrong type, missing parameter) are all invalid requests. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getProperties() == null) {
            String code = statusCode.value() == 404
                    ? "NOT_FOUND"
                    : statusCode.is4xxClientError() ? ErrorCode.INVALID_REQUEST.name() : "INTERNAL_ERROR";
            problem.setProperty("code", code);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }
}
