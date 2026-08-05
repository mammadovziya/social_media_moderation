package com.example.moderation.media;

class VisualRetrievalUnavailableException extends RuntimeException {
    VisualRetrievalUnavailableException(String message) {
        super(message);
    }

    VisualRetrievalUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
