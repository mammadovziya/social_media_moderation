package com.example.moderation.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Binds an accepted handle to a subject.
 *
 * <p>Allocation is a separate operation from moderation on purpose. A moderation ALLOW is a
 * statement about the string; only the caller knows whether the account actually took the handle,
 * and only an allocated handle should occupy the collision space.
 */
public record HandleAllocationRequest(
        @NotBlank @Size(max = 128) String subjectId,
        @NotBlank @Size(max = 30) @Pattern(regexp = "^[a-z0-9]([a-z0-9._]{1,28})[a-z0-9]$")
                String handle) {}
