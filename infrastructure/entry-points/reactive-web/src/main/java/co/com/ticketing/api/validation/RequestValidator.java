package co.com.ticketing.api.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

@Component
public class RequestValidator {

    private final Validator validator;

    public RequestValidator(ValidatorFactory factory) {
        this.validator = factory.usingContext()
                .messageInterpolator(new EnglishMessages(factory.getMessageInterpolator()))
                .getValidator();
    }

    public <T> Mono<T> validate(T body) {
        Set<ConstraintViolation<T>> violations = validator.validate(body);
        if (violations.isEmpty()) {
            return Mono.just(body);
        }
        TreeMap<String, String> errors = new TreeMap<>();
        violations.forEach(violation -> errors.put(violation.getPropertyPath().toString(), violation.getMessage()));
        return Mono.error(new RequestValidationException(errors));
    }

    private record EnglishMessages(MessageInterpolator delegate) implements MessageInterpolator {

        @Override
        public String interpolate(String template, Context context) {
            return delegate.interpolate(template, context, Locale.ENGLISH);
        }

        @Override
        public String interpolate(String template, Context context, Locale locale) {
            return delegate.interpolate(template, context, Locale.ENGLISH);
        }
    }
}
