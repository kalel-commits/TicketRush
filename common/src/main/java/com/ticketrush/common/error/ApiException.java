package com.ticketrush.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Business error that maps to a ProblemDetail with a stable {@link ErrorCode} (plus optional extra properties). */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    /** Adds a top-level ProblemDetail property, e.g. the paymentId of the attempt that blocked this one. */
    public ApiException withProperty(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }
}
