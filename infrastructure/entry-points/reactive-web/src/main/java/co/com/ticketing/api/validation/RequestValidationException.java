package co.com.ticketing.api.validation;

import java.util.Map;

public class RequestValidationException extends RuntimeException {

    private final transient Map<String, String> errors;

    public RequestValidationException(Map<String, String> errors) {
        super("Request validation failed");
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> errors() {
        return errors;
    }
}
