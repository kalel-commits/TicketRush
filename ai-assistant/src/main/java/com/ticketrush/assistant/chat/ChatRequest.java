package com.ticketrush.assistant.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Spec §24.8 request; limits enforced before any embedding or model cost (Review Focus 3). */
public record ChatRequest(@NotBlank @Size(max = 1000) String message,
                          @Size(max = 6) List<@NotNull @Valid HistoryItem> history,
                          Context context) {

    public record HistoryItem(@NotNull @Pattern(regexp = "user|assistant") String role,
                              @NotBlank @Size(max = 2000) String content) {}

    public record Context(Long eventId, UUID bookingId) {
        boolean isEmpty() {
            return eventId == null && bookingId == null;
        }
    }

    List<HistoryItem> historyOrEmpty() {
        return history == null ? List.of() : history;
    }

    boolean hasContextIds() {
        return context != null && !context.isEmpty();
    }
}
