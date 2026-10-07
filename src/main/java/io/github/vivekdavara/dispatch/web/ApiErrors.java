package io.github.vivekdavara.dispatch.web;

import io.github.vivekdavara.dispatch.order.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain errors to RFC 9457 problem responses ({@code application/problem+json}). Spring's own errors
 * (validation, malformed JSON, missing headers) use the same format via {@code spring.mvc.problemdetails}.
 */
@RestControllerAdvice
public class ApiErrors {

    /** Thrown when a path id doesn't name an existing resource. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String what) {
            super(what + " not found");
        }
    }

    /** A request that is well-formed but conflicts with the resource's current state. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({OrderService.IdempotencyKeyReusedException.class, ConflictException.class})
    ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({OrderService.UnknownZoneException.class, OrderService.PickupOutsideZoneException.class})
    ProblemDetail unprocessable(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
