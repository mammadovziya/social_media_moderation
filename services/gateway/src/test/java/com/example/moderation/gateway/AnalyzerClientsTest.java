package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalyzerClientsTest {
    @Test
    void forwardsOnlyBoundedCandidateAndVisualVerificationEvidence() {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("referenceId", "reference-1");
        candidate.put("decisionBasis", "TEXT_DEPENDENT");
        candidate.put("fingerprintTypes", List.of("FULL_PDQ", "ORB_HOMOGRAPHY"));
        candidate.put("visualAlgorithm", "ORB");
        candidate.put("visualVersion", "orb-opencv-4.12.0-v1");
        candidate.put("visualImplementationVersion", "4.12.0");
        candidate.put("visualChannel", "UNMASKED");
        candidate.put("visualInliers", 41);
        candidate.put("visualGoodMatches", 52);
        candidate.put("visualInlierRatio", 0.788);
        candidate.put("visualLshVotes", 68);
        candidate.put("visualMedianHammingDistance", 17.0);
        candidate.put("visualRank", 1);
        candidate.put("descriptorBytes", "must-never-cross-the-boundary");
        candidate.put("ocrText", "must-never-cross-the-boundary");

        Map<String, Object> evidence = AnalyzerClients.candidateEvidence(candidate);

        assertThat(evidence)
                .containsEntry("referenceId", "reference-1")
                .containsEntry("visualAlgorithm", "ORB")
                .containsEntry("visualVersion", "orb-opencv-4.12.0-v1")
                .containsEntry("visualImplementationVersion", "4.12.0")
                .containsEntry("visualChannel", "UNMASKED")
                .containsEntry("visualInliers", 41)
                .containsEntry("visualGoodMatches", 52)
                .containsEntry("visualInlierRatio", 0.788)
                .containsEntry("visualLshVotes", 68)
                .containsEntry("visualMedianHammingDistance", 17.0)
                .containsEntry("visualRank", 1)
                .doesNotContainKeys("descriptorBytes", "ocrText");
    }
}
