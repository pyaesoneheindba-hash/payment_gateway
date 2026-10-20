package com.paymentgateway.core.service;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.apache.dubbo.common.config.ReferenceCache;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.ReferenceConfigBase;
import org.apache.dubbo.config.utils.SimpleReferenceCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.paymentgateway.common.ProviderCapability;
import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.contract.error.ProviderCapabilityException;
import com.paymentgateway.contract.error.ProviderNotAvailableException;
import com.paymentgateway.contract.error.ProviderUnavailableException;
import com.paymentgateway.contract.payment.ProviderSummary;
import com.paymentgateway.contract.provider.ProviderDescriptor;
import com.paymentgateway.contract.provider.ProviderRpcService;
import com.paymentgateway.core.persistence.entity.PaymentProviderEntity;
import com.paymentgateway.core.persistence.repo.PaymentProviderRepository;

import jakarta.annotation.PreDestroy;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * DB-driven Dubbo provider registry. Both proxy and Dubbo reference identities
 * include RPC timeout/retry settings; descriptors refresh after a bounded TTL.
 */
@Component
public class ProviderClientRegistry {
    private static final long DESCRIPTOR_TTL_NANOS = Duration.ofSeconds(60).toNanos();

    private final PaymentProviderRepository providerRepository;
    private final ObjectMapper objectMapper;
    private final ReferenceCache referenceCache;
    private final LongSupplier nanoTime;
    private final Map<ProxyKey, Object> proxies = new ConcurrentHashMap<>();
    private final Map<ProviderConfigKey, CachedDescriptor> descriptors = new ConcurrentHashMap<>();

    /**
     * Creates the registry with a dedicated Dubbo cache. The default Dubbo
     * cache key omits timeout and retries, so it must not be used here.
     *
     * @param providerRepository provider repository
     * @param objectMapper JSON mapper
     */
    @Autowired
    public ProviderClientRegistry(PaymentProviderRepository providerRepository, ObjectMapper objectMapper) {
        this(providerRepository, objectMapper,
                SimpleReferenceCache.getCache("payment-core-provider-references",
                        ProviderClientRegistry::dubboCacheKey),
                System::nanoTime);
    }

    /**
     * Supplies a replaceable cache and monotonic clock for local unit tests.
     *
     * @param providerRepository provider repository
     * @param objectMapper JSON mapper
     * @param referenceCache Dubbo cache
     * @param nanoTime monotonic time source
     */
    ProviderClientRegistry(PaymentProviderRepository providerRepository, ObjectMapper objectMapper,
            ReferenceCache referenceCache, LongSupplier nanoTime) {
        this.providerRepository = providerRepository;
        this.objectMapper = objectMapper;
        this.referenceCache = referenceCache;
        this.nanoTime = nanoTime;
    }

    /**
     * Retrieves the base provider RPC contract.
     *
     * @param requestedCode provider code
     * @return provider RPC proxy
     */
    public ProviderRpcService requireBase(String requestedCode) {
        return require(requestedCode, null, ProviderRpcService.class);
    }

    /**
     * Retrieves a capability-checked provider RPC contract.
     *
     * @param requestedCode provider code
     * @param capability required capability, or null
     * @param serviceInterface RPC interface
     * @return provider RPC proxy
     */
    public <T> T require(String requestedCode, ProviderCapability capability, Class<T> serviceInterface) {
        PaymentProviderEntity config = requireConfig(requestedCode);
        if (capability != null && !capabilities(config).contains(capability)) {
            throw new ProviderCapabilityException(config.getProviderCode(), capability);
        }
        ProviderDescriptor descriptor = validatedDescriptor(config);
        if (capability != null
                && (descriptor.capabilities() == null || !descriptor.capabilities().contains(capability))) {
            throw new ProviderUnavailableException(config.getProviderCode(),
                    "Provider adapter capability configuration is inconsistent");
        }
        return proxy(config, serviceInterface);
    }

    /**
     * Retrieves the current validated descriptor.
     *
     * @param requestedCode provider code
     * @return provider descriptor
     */
    public ProviderDescriptor descriptor(String requestedCode) {
        return validatedDescriptor(requireConfig(requestedCode));
    }

    /**
     * Lists providers from the current database state.
     *
     * @return provider summaries
     */
    public List<ProviderSummary> list() {
        return providerRepository.findAll().stream()
                .map(p -> new ProviderSummary(p.getProviderCode(), p.getDisplayName(),
                        p.getDubboGroup(), p.isEnabled(), capabilities(p)))
                .sorted((a, b) -> a.providerCode().compareTo(b.providerCode())).toList();
    }

    /**
     * Caches descriptors for 60 seconds per effective RPC configuration.
     * Configuration changes therefore refresh the descriptor immediately.
     *
     * @param config current provider settings
     * @return validated descriptor
     */
    private ProviderDescriptor validatedDescriptor(PaymentProviderEntity config) {
        ProviderConfigKey key = ProviderConfigKey.from(config);
        CachedDescriptor cached = descriptors.compute(key, (ignored, previous) -> {
            long now = nanoTime.getAsLong();
            if (previous != null && now - previous.loadedAtNanos() < DESCRIPTOR_TTL_NANOS) {
                return previous;
            }
            ProviderDescriptor remote = proxy(config, ProviderRpcService.class).descriptor();
            if (remote == null || !config.getProviderCode().equals(ProviderCode.normalize(remote.providerCode()))) {
                throw new ProviderUnavailableException(config.getProviderCode(),
                        "Provider adapter descriptor does not match its configured provider code");
            }
            return new CachedDescriptor(remote, now);
        });
        descriptors.keySet().removeIf(other -> other.providerCode().equals(key.providerCode()) && !other.equals(key));
        return cached.descriptor();
    }

    /**
     * Reuses a proxy only when every effective RPC setting matches.
     *
     * @param config current provider settings
     * @param serviceInterface RPC interface
     * @return Dubbo proxy
     */
    private <T> T proxy(PaymentProviderEntity config, Class<T> serviceInterface) {
        ProxyKey key = new ProxyKey(ProviderConfigKey.from(config), serviceInterface);
        Object value = proxies.computeIfAbsent(key, ignored -> createReference(config, serviceInterface));
        return serviceInterface.cast(value);
    }

    /**
     * Loads an enabled provider and checks its timeout/retry policy.
     *
     * @param requestedCode provider code
     * @return enabled provider configuration
     */
    private PaymentProviderEntity requireConfig(String requestedCode) {
        String code = ProviderCode.normalize(requestedCode);
        PaymentProviderEntity config = providerRepository.findById(code).filter(PaymentProviderEntity::isEnabled)
                .orElseThrow(() -> new ProviderNotAvailableException(code));
        if (config.getRpcTimeoutMs() <= 0 || config.getRpcRetries() < 0) {
            throw new ProviderUnavailableException(code, "Provider RPC timeout/retry configuration is invalid");
        }
        return config;
    }

    /**
     * Parses the current enabled provider capabilities.
     *
     * @param p provider settings
     * @return enabled capabilities
     */
    private Set<ProviderCapability> capabilities(PaymentProviderEntity p) {
        try {
            Map<String, Boolean> values = objectMapper.readValue(p.getCapabilitiesJson(),
                    new TypeReference<Map<String, Boolean>>() {});
            EnumSet<ProviderCapability> result = EnumSet.noneOf(ProviderCapability.class);
            values.forEach((k, v) -> {
                if (Boolean.TRUE.equals(v)) {
                    try {
                        result.add(ProviderCapability.valueOf(k));
                    } catch (IllegalArgumentException ignored) {
                        // Unknown capabilities do not enable an operation.
                    }
                }
            });
            return Collections.unmodifiableSet(result);
        } catch (Exception e) {
            throw new ProviderUnavailableException(p.getProviderCode(),
                    "Provider capability configuration is invalid", e);
        }
    }

    /**
     * Creates a Dubbo reference with the configured timeout and retry count.
     *
     * @param config provider settings
     * @param serviceInterface RPC interface
     * @return cached proxy
     */
    private <T> T createReference(PaymentProviderEntity config, Class<T> serviceInterface) {
        ReferenceConfig<T> reference = new ReferenceConfig<>();
        reference.setInterface(serviceInterface);
        reference.setGroup(config.getDubboGroup());
        reference.setVersion(config.getServiceVersion());
        reference.setProtocol("tri");
        reference.setCheck(false);
        reference.setTimeout(config.getRpcTimeoutMs());
        reference.setRetries(config.getRpcRetries());
        return referenceCache.get(reference);
    }

    /**
     * Distinguishes Dubbo references by protocol, timeout and retries as well
     * as group, interface and version.
     *
     * @param reference Dubbo reference configuration
     * @return effective reference key
     */
    static String dubboCacheKey(ReferenceConfigBase<?> reference) {
        return String.join("|",
                String.valueOf(reference.getGroup()),
                String.valueOf(reference.getInterface()),
                String.valueOf(reference.getVersion()),
                String.valueOf(reference.getProtocol()),
                String.valueOf(reference.getTimeout()),
                String.valueOf(reference.getRetries()));
    }

    /**
     * Destroys this registry's dedicated Dubbo references at shutdown. Old
     * references remain available until shutdown to avoid interrupting RPCs
     * that were already handed an earlier proxy during reconfiguration.
     */
    @PreDestroy
    public void shutdown() {
        proxies.clear();
        descriptors.clear();
        referenceCache.destroyAll();
    }

    /** Identity of all effective provider RPC settings. */
    private record ProviderConfigKey(String providerCode, String group, String version, int timeoutMs, int retries) {
        /**
         * Captures an immutable configuration snapshot.
         *
         * @param config provider settings
         * @return configuration identity
         */
        static ProviderConfigKey from(PaymentProviderEntity config) {
            return new ProviderConfigKey(config.getProviderCode(), config.getDubboGroup(),
                    config.getServiceVersion(), config.getRpcTimeoutMs(), config.getRpcRetries());
        }
    }

    /** Identity of one RPC interface under a provider configuration. */
    private record ProxyKey(ProviderConfigKey config, Class<?> serviceInterface) {}

    /** Descriptor and monotonic timestamp of its last successful refresh. */
    private record CachedDescriptor(ProviderDescriptor descriptor, long loadedAtNanos) {}
}
