package co.com.ticketing.api.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateEventRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Future Instant date,
        @NotBlank @Size(max = 200) String venue,
        @NotNull @Min(1) @Max(1_000_000) Integer capacity) {
}
