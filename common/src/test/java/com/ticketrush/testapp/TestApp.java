package com.ticketrush.testapp;

import com.ticketrush.common.error.ApiException;
import com.ticketrush.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Lives OUTSIDE com.ticketrush.common, so the handler can only arrive through auto-configuration. */
@SpringBootApplication
public class TestApp {

    @RestController
    static class Endpoints {
        @GetMapping("/boom")
        String boom() {
            throw new ApiException(ErrorCode.SEAT_LIMIT_EXCEEDED, "max 4");
        }

        @GetMapping("/boom-with-property")
        String boomWithProperty() {
            throw new ApiException(ErrorCode.PAYMENT_IN_PROGRESS_OR_DONE, "in flight").withProperty("paymentId", "p-1");
        }

        @GetMapping("/crash")
        String crash() {
            throw new IllegalStateException("db password leaked");
        }

        @GetMapping("/typed/{id}")
        String typed(@org.springframework.web.bind.annotation.PathVariable long id) {
            return "ok";
        }

        @PostMapping("/validate")
        String validate(@Valid @RequestBody Body body) {
            return "ok";
        }
    }

    record Body(@NotBlank String name) {
    }
}
