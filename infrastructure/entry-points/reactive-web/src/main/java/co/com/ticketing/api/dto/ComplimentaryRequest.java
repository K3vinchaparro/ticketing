package co.com.ticketing.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ComplimentaryRequest(@NotNull @Min(1) @Max(1_000) Integer quantity) {
}
