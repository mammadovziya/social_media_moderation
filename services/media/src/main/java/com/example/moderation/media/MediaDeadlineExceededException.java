package com.example.moderation.media;

final class MediaDeadlineExceededException extends RuntimeException {
    MediaDeadlineExceededException() {
        super("moderation deadline expired");
    }

    MediaDeadlineExceededException(Throwable cause) {
        super("moderation deadline expired", cause);
    }
}
