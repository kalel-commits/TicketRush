package com.ticketrush.event.messaging;

/** A message that can never be processed (bad JSON, missing fields). Not retried: goes straight to the DLT. */
public class MalformedMessageException extends RuntimeException {

    public MalformedMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
