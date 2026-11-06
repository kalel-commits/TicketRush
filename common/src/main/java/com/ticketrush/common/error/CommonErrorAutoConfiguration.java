package com.ticketrush.common.error;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/** Registers {@link ProblemDetailsHandler} in every servlet (MVC) service; never in the WebFlux gateway. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(ProblemDetailsHandler.class)
public class CommonErrorAutoConfiguration {
}
