package com.example.moderation.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HandleEvaluationServiceTest {

    @Test
    void genuineIAndLHandlesUseDifferentVerdictCacheKeys() {
        ProtectedNameIndex protectedNames = mock(ProtectedNameIndex.class);
        when(protectedNames.digest()).thenReturn("0".repeat(64));
        when(protectedNames.activeCount()).thenReturn(0);
        when(protectedNames.match(any())).thenReturn(Optional.empty());
        UsernameVerdictCacheRepository verdictCache = mock(UsernameVerdictCacheRepository.class);
        when(verdictCache.find(any())).thenReturn(Optional.empty());
        HandleEvaluationService service =
                new HandleEvaluationService(protectedNames, verdictCache);

        service.evaluate(request("ziya.murad"));
        service.evaluate(request("zlya.murad"));

        ArgumentCaptor<UsernameVerdictCacheRepository.CacheKey> keys =
                ArgumentCaptor.forClass(UsernameVerdictCacheRepository.CacheKey.class);
        verify(verdictCache, times(2)).find(keys.capture());
        assertThat(keys.getAllValues())
                .extracting(UsernameVerdictCacheRepository.CacheKey::skeleton)
                .containsExactly("ziyamurad", "zlyamurad");
        assertThat(keys.getAllValues())
                .extracting(UsernameVerdictCacheRepository.CacheKey::skeletonProfileSha256)
                .containsOnly(HandleSkeleton.PROFILE_SHA256);
    }

    private static HandleEvaluationRequest request(String handle) {
        return new HandleEvaluationRequest(
                handle,
                "gpt-test",
                "1".repeat(64),
                "2".repeat(64));
    }
}
