package dev.quickdrop.core.order;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PlaceOrderRequest(@NotNull Long customerId,
                                @NotNull Long storeId,
                                @NotEmpty @Size(max = 20) List<@Valid Line> items) {

    public record Line(@NotNull Long productId, @NotNull @Min(1) Integer quantity) {
    }
}
