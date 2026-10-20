package com.paymentgateway.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.dubbo.common.config.ReferenceCache;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.ReferenceConfigBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.paymentgateway.common.ProviderCapability;
import com.paymentgateway.contract.provider.ProviderDescriptor;
import com.paymentgateway.contract.provider.ProviderRpcService;
import com.paymentgateway.core.persistence.entity.PaymentProviderEntity;
import com.paymentgateway.core.persistence.repo.PaymentProviderRepository;

import tools.jackson.databind.ObjectMapper;

/** Regression tests for provider cache identity and descriptor freshness. */
class ProviderClientRegistryTest {
    /**
     * Changing only timeout or retries must create new Dubbo references.
     */
    @Test
    void timeoutAndRetryChangesCreateNewReferences() {
        PaymentProviderRepository repository = mock(PaymentProviderRepository.class);
        ReferenceCache cache = mock(ReferenceCache.class);
        when(repository.findById("AYAPAY")).thenReturn(
                Optional.of(provider("provider-ayapay", "1.0.0", 8000, 0)),
                Optional.of(provider("provider-ayapay", "1.0.0", 3000, 0)),
                Optional.of(provider("provider-ayapay", "1.0.0", 3000, 1)));
        ProviderRpcService first = mock(ProviderRpcService.class);
        ProviderRpcService second = mock(ProviderRpcService.class);
        ProviderRpcService third = mock(ProviderRpcService.class);
        when(first.descriptor()).thenReturn(descriptor());
        when(second.descriptor()).thenReturn(descriptor());
        when(third.descriptor()).thenReturn(descriptor());
        when(cache.get(org.mockito.ArgumentMatchers.<ReferenceConfigBase<ProviderRpcService>>any()))
                .thenReturn(first, second, third);
        ProviderClientRegistry registry = new ProviderClientRegistry(repository, new ObjectMapper(), cache, () -> 0L);
        assertSame(first, registry.requireBase("AYAPAY"));
        assertSame(second, registry.requireBase("AYAPAY"));
        assertSame(third, registry.requireBase("AYAPAY"));

        ArgumentCaptor<ReferenceConfigBase> captured = ArgumentCaptor.forClass(ReferenceConfigBase.class);
        verify(cache, times(3)).get(captured.capture());
        assertEquals(8000, captured.getAllValues().get(0).getTimeout());
        assertEquals(3000, captured.getAllValues().get(1).getTimeout());
        assertEquals(3000, captured.getAllValues().get(2).getTimeout());
        assertEquals(0, captured.getAllValues().get(0).getRetries());
        assertEquals(0, captured.getAllValues().get(1).getRetries());
        assertEquals(1, captured.getAllValues().get(2).getRetries());
    }

    /**
     * Group and version changes must invalidate the descriptor immediately.
     */
    @Test
    void groupAndVersionChangesRefreshDescriptor() {
        PaymentProviderRepository repository = mock(PaymentProviderRepository.class);
        ReferenceCache cache = mock(ReferenceCache.class);
        when(repository.findById("AYAPAY")).thenReturn(
                Optional.of(provider("provider-ayapay", "1.0.0", 8000, 0)),
                Optional.of(provider("provider-ayapay-v2", "1.0.0", 8000, 0)),
                Optional.of(provider("provider-ayapay-v2", "2.0.0", 8000, 0)));
        ProviderRpcService first = mock(ProviderRpcService.class);
        ProviderRpcService second = mock(ProviderRpcService.class);
        ProviderRpcService third = mock(ProviderRpcService.class);
        when(first.descriptor()).thenReturn(descriptor());
        when(second.descriptor()).thenReturn(descriptor());
        when(third.descriptor()).thenReturn(descriptor());
        when(cache.get(org.mockito.ArgumentMatchers.<ReferenceConfigBase<ProviderRpcService>>any()))
                .thenReturn(first, second, third);
        ProviderClientRegistry registry = new ProviderClientRegistry(repository, new ObjectMapper(), cache, () -> 0L);
        registry.descriptor("AYAPAY");
        registry.descriptor("AYAPAY");
        registry.descriptor("AYAPAY");
        verify(first).descriptor();
        verify(second).descriptor();
        verify(third).descriptor();
    }

    /**
     * Stable references are reused while descriptors refresh after 60 seconds.
     */
    @Test
    void descriptorExpiresWithoutRecreatingReference() {
        PaymentProviderRepository repository = mock(PaymentProviderRepository.class);
        ReferenceCache cache = mock(ReferenceCache.class);
        when(repository.findById("AYAPAY")).thenReturn(
                Optional.of(provider("provider-ayapay", "1.0.0", 8000, 0)));
        ProviderRpcService remote = mock(ProviderRpcService.class);
        when(remote.descriptor()).thenReturn(descriptor());
        when(cache.get(org.mockito.ArgumentMatchers.<ReferenceConfigBase<ProviderRpcService>>any()))
                .thenReturn(remote);
        AtomicLong clock = new AtomicLong();
        ProviderClientRegistry registry = new ProviderClientRegistry(repository, new ObjectMapper(), cache, clock::get);
        registry.descriptor("AYAPAY");
        registry.descriptor("AYAPAY");
        verify(remote, times(1)).descriptor();
        clock.addAndGet(Duration.ofSeconds(61).toNanos());
        registry.descriptor("AYAPAY");
        verify(remote, times(2)).descriptor();
        verify(cache, times(1)).get(org.mockito.ArgumentMatchers.<ReferenceConfigBase<ProviderRpcService>>any());
    }

    /**
     * The Dubbo-level key must distinguish timeout and retries.
     */
    @Test
    void dubboCacheKeyIncludesTimeoutAndRetries() {
        assertNotEquals(ProviderClientRegistry.dubboCacheKey(reference(8000, 0)),
                ProviderClientRegistry.dubboCacheKey(reference(3000, 0)));
        assertNotEquals(ProviderClientRegistry.dubboCacheKey(reference(8000, 0)),
                ProviderClientRegistry.dubboCacheKey(reference(8000, 1)));
    }

    /**
     * Registry shutdown releases the dedicated reference cache.
     */
    @Test
    void destroysReferencesOnShutdown() {
        ReferenceCache cache = mock(ReferenceCache.class);
        ProviderClientRegistry registry = new ProviderClientRegistry(
                mock(PaymentProviderRepository.class), new ObjectMapper(), cache, () -> 0L);
        registry.shutdown();
        verify(cache).destroyAll();
    }

    /**
     * Creates a mocked provider row.
     *
     * @param group Dubbo group
     * @param version Dubbo version
     * @param timeout RPC timeout in milliseconds
     * @param retries RPC retry count
     * @return provider row
     */
    private static PaymentProviderEntity provider(String group, String version, int timeout, int retries) {
        PaymentProviderEntity config = mock(PaymentProviderEntity.class);
        when(config.getProviderCode()).thenReturn("AYAPAY");
        when(config.getDubboGroup()).thenReturn(group);
        when(config.getServiceVersion()).thenReturn(version);
        when(config.getRpcTimeoutMs()).thenReturn(timeout);
        when(config.getRpcRetries()).thenReturn(retries);
        when(config.isEnabled()).thenReturn(true);
        return config;
    }

    /**
     * Creates a matching provider descriptor.
     *
     * @return provider descriptor
     */
    private static ProviderDescriptor descriptor() {
        return new ProviderDescriptor("AYAPAY", "AYA Pay", Set.of(ProviderCapability.ENQUIRY));
    }

    /**
     * Creates an uninitialized Dubbo reference without any network calls.
     *
     * @param timeout RPC timeout
     * @param retries RPC retry count
     * @return reference configuration
     */
    private static ReferenceConfig<ProviderRpcService> reference(int timeout, int retries) {
        ReferenceConfig<ProviderRpcService> reference = new ReferenceConfig<>();
        reference.setInterface(ProviderRpcService.class);
        reference.setGroup("provider-ayapay");
        reference.setVersion("1.0.0");
        reference.setProtocol("tri");
        reference.setTimeout(timeout);
        reference.setRetries(retries);
        return reference;
    }
}
