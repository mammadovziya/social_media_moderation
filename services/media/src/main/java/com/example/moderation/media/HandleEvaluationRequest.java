package com.example.moderation.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Candidate handle to check against stored handle state.
 *
 * <p>The analyzer-contract fields are optional. When they are absent the service simply reports no
 * cached verdict, so a caller that has not bound its model configuration still gets the
 * deterministic evidence.
 */
public record HandleEvaluationRequest(
        @NotBlank @Size(max = 64) String handle,
        @Size(max = 128) String subjectId,
        @Size(max = 128) String classificationModel,
        @Pattern(regexp = "^$|^[0-9a-f]{64}$") String promptBundleSha256,
        @Pattern(regexp = "^$|^[0-9a-f]{64}$") String classificationProfileSha256) {

    public HandleEvaluationRequest {
        subjectId = subjectId == null ? "" : subjectId;
        classificationModel = classificationModel == null ? "" : classificationModel;
        promptBundleSha256 = promptBundleSha256 == null ? "" : promptBundleSha256;
        classificationProfileSha256 =
                classificationProfileSha256 == null ? "" : classificationProfileSha256;
    }
}
