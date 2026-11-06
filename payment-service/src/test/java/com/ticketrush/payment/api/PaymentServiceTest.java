package com.ticketrush.payment.api;

import com.ticketrush.payment.bookingclient.BookingClient;
import com.ticketrush.payment.bookingclient.BookingClient.BookingLookup;
import com.ticketrush.payment.domain.Payment;
import com.ticketrush.payment.domain.PaymentRepository;
import com.ticketrush.payment.domain.PaymentStatus;
import com.ticketrush.payment.outbox.OutboxWriter;
import com.ticketrush.payment.provider.ProviderClient;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 4 deferred race: the attempt that blocked our insert turned FAILED before we looked it up, so the
 * booking is free again. Retry the insert once instead of answering 409 with paymentId=null.
 */
class PaymentServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void duplicateWithNoActiveAttemptRetriesInsertOnce() {
        PaymentRepository payments = mock(PaymentRepository.class);
        BookingClient bookings = mock(BookingClient.class);
        ProviderClient provider = mock(ProviderClient.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        long user = 5L;
        UUID booking = UUID.randomUUID();
        AtomicReference<Payment> inserted = new AtomicReference<>();

        when(payments.findByIdempotency(user, "k1")).thenReturn(Optional.empty());
        when(payments.findActiveByBooking(booking)).thenReturn(Optional.empty());
        when(bookings.get(booking)).thenReturn(new BookingLookup.Found(booking, user, "PENDING_PAYMENT", 2500,
                Instant.now().plusSeconds(600)));
        doThrow(new DuplicateKeyException("uk_payment_active_booking"))
                .doAnswer(inv -> { inserted.set(inv.getArgument(0)); return null; })
                .when(payments).insertProcessing(any());
        when(provider.charge(any(), anyLong(), eq(booking))).thenReturn(new ProviderClient.ChargeOutcome.Approved("ch_1"));
        doAnswer(inv -> { ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null); return null; })
                .when(tx).executeWithoutResult(any());
        when(payments.transition(any(), eq(PaymentStatus.PROCESSING), eq(PaymentStatus.SUCCEEDED), anyString(), any())).thenReturn(true);
        when(payments.findById(any())).thenAnswer(inv -> {
            Payment p = inserted.get();
            return Optional.of(new Payment(p.id(), p.bookingId(), p.userId(), p.amountCents(), PaymentStatus.SUCCEEDED,
                    p.idempotencyKey(), p.requestHash(), "ch_1", null, p.createdAt(), p.updatedAt()));
        });

        var service = new PaymentService(payments, bookings, provider, mock(OutboxWriter.class), tx, Clock.systemUTC());
        Payment result = service.pay(user, "k1", booking);

        verify(payments, times(2)).insertProcessing(any());
        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
}
