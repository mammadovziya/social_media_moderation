package com.example.moderation.media;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Opens an appeal against one audited handle decision. */
public record UsernameAppealRequest(
        @NotNull @Min(1) Long auditEventId,
        @Size(max = 2000) String appellantStatement) {}
