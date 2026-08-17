package com.example.moderation.media;

final class MediaCapacityExceededException extends RuntimeException {
    MediaCapacityExceededException(String stage) {
        super(stage + " capacity is temporarily exhausted");
    }
}
