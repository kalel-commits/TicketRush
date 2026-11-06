package com.ticketrush.event.catalog;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Instant;
import java.util.List;

public final class EventDtos {

    private EventDtos() {
    }

    @ValidSaleWindow
    public record CreateEventRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 200) String venue,
            @NotNull Instant startsAt,
            @NotNull Instant saleOpensAt,
            Instant saleClosesAt,
            @Positive long priceCents,
            @Min(1) @Max(26) int rows,
            @Min(1) @Max(50) int seatsPerRow,
            Listing listing,
            @Size(max = 200) String artist,
            Category category,
            @Size(max = 100) String city,
            @Size(max = 2000) String description) {

        public Instant effectiveSaleClosesAt() {
            return saleClosesAt != null ? saleClosesAt : startsAt;
        }
    }

    /** Cached in Redis (JDK serialisation), hence Serializable. */
    public record EventView(long id, String name, String venue, Instant startsAt, Instant saleOpensAt,
                            Instant saleClosesAt, long priceCents, int totalSeats, int availableSeats,
                            Listing listing, String artist, Category category, String city, String description) implements Serializable {

        static EventView of(Event e, int availableSeats) {
            return new EventView(e.getId(), e.getName(), e.getVenue(), e.getStartsAt(), e.getSaleOpensAt(),
                    e.getSaleClosesAt(), e.getPriceCents(), e.getTotalSeats(), availableSeats,
                    e.getListing(), e.getArtist(), e.getCategory(), e.getCity(), e.getDescription());
        }

        public EventView withAvailable(int available) {
            return new EventView(id, name, venue, startsAt, saleOpensAt, saleClosesAt, priceCents, totalSeats, available,
                    listing, artist, category, city, description);
        }
    }

    public record EventPage(List<EventView> content, int page, int size, long totalElements) implements Serializable {
    }

    public record SeatView(long id, String seatNo, String status) {
    }

    /** saleOpensAt must be before the (effective) sale close; reported on the saleOpensAt field. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = SaleWindowValidator.class)
    public @interface ValidSaleWindow {
        String message() default "must be before the sale closes (saleClosesAt, default startsAt)";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class SaleWindowValidator implements ConstraintValidator<ValidSaleWindow, CreateEventRequest> {
        @Override
        public boolean isValid(CreateEventRequest r, ConstraintValidatorContext ctx) {
            if (r == null || r.saleOpensAt() == null || r.effectiveSaleClosesAt() == null) {
                return true;   // @NotNull reports the missing field
            }
            if (r.saleOpensAt().isBefore(r.effectiveSaleClosesAt())) {
                return true;
            }
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(ctx.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("saleOpensAt").addConstraintViolation();
            return false;
        }
    }
}
