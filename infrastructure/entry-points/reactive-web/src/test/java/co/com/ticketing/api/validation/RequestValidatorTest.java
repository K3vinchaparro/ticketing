package co.com.ticketing.api.validation;

import co.com.ticketing.api.ApiTestSupport;
import co.com.ticketing.api.dto.ComplimentaryRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestValidatorTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(original);
    }

    @Test
    void validBodyPassesThrough() {
        ComplimentaryRequest body = new ComplimentaryRequest(5);

        StepVerifier.create(ApiTestSupport.validator().validate(body)).expectNext(body).verifyComplete();
    }

    @Test
    void messagesAreInEnglishRegardlessOfTheServerLocale() {
        Locale.setDefault(Locale.forLanguageTag("es-CO"));

        StepVerifier.create(ApiTestSupport.validator().validate(new ComplimentaryRequest(null)))
                .expectErrorSatisfies(error -> assertThat(((RequestValidationException) error).errors())
                        .isEqualTo(Map.of("quantity", "must not be null")))
                .verify();
    }
}
