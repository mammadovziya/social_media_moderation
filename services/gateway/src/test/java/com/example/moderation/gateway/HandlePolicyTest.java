package com.example.moderation.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HandlePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc", "normal_name", "value.investor", "a1b", "user123"})
    void acceptsHandlesInTheContract(String handle) {
        assertThat(HandlePolicy.evaluate(handle).valid()).isTrue();
    }

    @Test
    void normalizesCaseAndExposesTheSkeleton() {
        HandlePolicy.Result result = HandlePolicy.evaluate("Value.Investor");

        assertThat(result.valid()).isTrue();
        assertThat(result.normalized()).isEqualTo("value.investor");
        assertThat(result.skeleton()).isEqualTo(HandleSkeleton.of("value.investor"));
    }

    @Test
    void rejectsHandlesOutsideTheLengthContract() {
        assertThat(HandlePolicy.evaluate("ab").reason())
                .isEqualTo(HandlePolicy.Reason.TOO_SHORT);
        assertThat(HandlePolicy.evaluate("a".repeat(31)).reason())
                .isEqualTo(HandlePolicy.Reason.TOO_LONG);
    }

    /**
     * The whole point of the ASCII contract: a confusable, bidirectional, or zero-width character
     * is refused at the boundary rather than reasoned about downstream.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "kаpitalbank",
        "kapital bank",
        "kapital-bank",
        "admin​istrator",
        "əliməmmədov",
        "user@bank",
        "用户名"
    })
    void rejectsAnythingOutsideTheAsciiAlphabet(String handle) {
        assertThat(HandlePolicy.evaluate(handle).reason())
                .isEqualTo(HandlePolicy.Reason.INVALID_CHARACTER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"_name", "name_", ".name", "name.", "a__b", "a._b", "a..b"})
    void rejectsLeadingTrailingAndRepeatedSeparators(String handle) {
        assertThat(HandlePolicy.evaluate(handle).reason())
                .isEqualTo(HandlePolicy.Reason.INVALID_FORMAT);
    }

    @Test
    void rejectionMessagesNeverEchoTheHandle() {
        for (HandlePolicy.Reason reason : HandlePolicy.Reason.values()) {
            assertThat(reason.message()).doesNotContain("kapitalbank");
        }
    }

    @Test
    void profileDigestCoversTheSkeletonProfile() {
        assertThat(HandlePolicy.PROFILE_VERSION).isEqualTo("handle-structure-v2");
        assertThat(HandlePolicy.PROFILE_SHA256).hasSize(64).matches("[0-9a-f]{64}");
    }
}
