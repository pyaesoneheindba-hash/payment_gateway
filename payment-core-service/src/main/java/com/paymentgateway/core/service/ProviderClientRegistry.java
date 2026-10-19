package com.paymentgateway.core.service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.dubbo.common.config.ReferenceCache;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.utils.SimpleReferenceCache;
import org.springframework.stereotype.Component;

import com.paymentgateway.common.ProviderCapability;
import com.paymentgateway.common.ProviderCode;
import com.paymentgateway.contract.payment.ProviderSummary;
import com.paymentgateway.contract.error.ProviderCapabilityException;
import com.paymentgateway.contract.error.ProviderNotAvailableException;
import com.paymentgateway.contract.error.ProviderUnavailableException;
import com.paymentgateway.contract.provider.ProviderDescriptor;
import com.paymentgateway.contract.provider.ProviderRpcService;
import com.paymentgateway.core.persistence.entity.PaymentProviderEntity;
import com.paymentgateway.core.persistence.repo.PaymentProviderRepository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * DB-driven Dubbo provider registry. No bank-specific Dubbo references live in payment-core.
 * To add a provider, deploy an adapter that exports the standard provider contract(s), then
 * insert/update payment_provider with provider_code, dubbo_group and capabilities.
 */
@Component
public class ProviderClientRegistry {
    private final PaymentProviderRepository providerRepository;
    private final ObjectMapper objectMapper;
    private final ReferenceCache referenceCache = SimpleReferenceCache.getCache();
    private final Map<String,Object> proxies = new ConcurrentHashMap<>();
    private final Map<String,ProviderDescriptor> descriptors = new ConcurrentHashMap<>();

    /**
     * Creates a new {@code ProviderClientRegistry} instance.
     *
     * @param providerRepository the provider repository value
     * @param objectMapper the object mapper value
     */
    public ProviderClientRegistry(PaymentProviderRepository providerRepository,ObjectMapper objectMapper){
        this.providerRepository=providerRepository; this.objectMapper=objectMapper;
    }

    /**
     * Retrieves and validates base.
     *
     * @param requestedCode the requested code value
     * @return the resulting {@code ProviderRpcService} value
     */
    public ProviderRpcService requireBase(String requestedCode) {
        return require(requestedCode,null,ProviderRpcService.class);
    }

    /**
     * Executes the require operation.
     *
     * @param requestedCode the requested code value
     * @param capability the capability value
     * @param serviceInterface the service interface value
     * @return the resulting {@code T} value
     */
    public <T> T require(String requestedCode, ProviderCapability capability, Class<T> serviceInterface) {
        PaymentProviderEntity config=requireConfig(requestedCode);
        if(capability!=null && !capabilities(config).contains(capability)) {
            throw new ProviderCapabilityException(config.getProviderCode(),capability);
        }
        ProviderDescriptor descriptor=validatedDescriptor(config);
        if(capability!=null && (descriptor.capabilities()==null || !descriptor.capabilities().contains(capability))) {
            throw new ProviderUnavailableException(config.getProviderCode(), "Provider adapter capability configuration is inconsistent");
        }
        String key=config.getProviderCode()+"|"+serviceInterface.getName()+"|"+config.getDubboGroup()+"|"+config.getServiceVersion();
        Object proxy=proxies.computeIfAbsent(key,k->createReference(config,serviceInterface));
        return serviceInterface.cast(proxy);
    }

    /**
     * Executes the descriptor operation.
     *
     * @param requestedCode the requested code value
     * @return the resulting {@code ProviderDescriptor} value
     */
    public ProviderDescriptor descriptor(String requestedCode) { return validatedDescriptor(requireConfig(requestedCode)); }

    /**
     * Executes the list operation.
     *
     * @return the matching values
     */
    public List<ProviderSummary> list() {
        return providerRepository.findAll().stream()
                .map(p->new ProviderSummary(p.getProviderCode(),p.getDisplayName(),p.getDubboGroup(),p.isEnabled(),capabilities(p)))
                .sorted((a,b)->a.providerCode().compareTo(b.providerCode()))
                .toList();
    }


    /**
     * Executes the validated descriptor operation.
     *
     * @param config the config value
     * @return the resulting {@code ProviderDescriptor} value
     */
    private ProviderDescriptor validatedDescriptor(PaymentProviderEntity config){
        return descriptors.computeIfAbsent(config.getProviderCode(),code->{
            ProviderRpcService base=proxy(config,ProviderRpcService.class);
            ProviderDescriptor remote=base.descriptor();
            if(remote==null || !config.getProviderCode().equals(ProviderCode.normalize(remote.providerCode()))) {
                throw new ProviderUnavailableException(config.getProviderCode(), "Provider adapter descriptor does not match its configured provider code");
            }
            return remote;
        });
    }

    /**
     * Executes the proxy operation.
     *
     * @param config the config value
     * @param serviceInterface the service interface value
     * @return the resulting {@code T} value
     */
    private <T> T proxy(PaymentProviderEntity config,Class<T> serviceInterface){
        String key=config.getProviderCode()+"|"+serviceInterface.getName()+"|"+config.getDubboGroup()+"|"+config.getServiceVersion();
        Object value=proxies.computeIfAbsent(key,k->createReference(config,serviceInterface));
        return serviceInterface.cast(value);
    }

    /**
     * Retrieves and validates config.
     *
     * @param requestedCode the requested code value
     * @return the resulting {@code PaymentProviderEntity} value
     */
    private PaymentProviderEntity requireConfig(String requestedCode){
        String code=ProviderCode.normalize(requestedCode);
        return providerRepository.findById(code).filter(PaymentProviderEntity::isEnabled)
                .orElseThrow(()->new ProviderNotAvailableException(code));
    }

    /**
     * Executes the capabilities operation.
     *
     * @param p the payment entity
     * @return the matching values
     */
    private Set<ProviderCapability> capabilities(PaymentProviderEntity p){
        try{
            Map<String,Boolean> values=objectMapper.readValue(p.getCapabilitiesJson(),new TypeReference<Map<String,Boolean>>(){});
            EnumSet<ProviderCapability> result=EnumSet.noneOf(ProviderCapability.class);
            values.forEach((k,v)->{if(Boolean.TRUE.equals(v)){try{result.add(ProviderCapability.valueOf(k));}catch(IllegalArgumentException ignored){}}});
            return Collections.unmodifiableSet(result);
        }catch(Exception e){throw new ProviderUnavailableException(p.getProviderCode(), "Provider capability configuration is invalid", e);}
    }

    /**
     * Creates reference.
     *
     * @param config the config value
     * @param serviceInterface the service interface value
     * @return the resulting {@code T} value
     */
    private <T> T createReference(PaymentProviderEntity config,Class<T> serviceInterface){
        ReferenceConfig<T> reference=new ReferenceConfig<>();
        reference.setInterface(serviceInterface);
        reference.setGroup(config.getDubboGroup());
        reference.setVersion(config.getServiceVersion());
        reference.setProtocol("tri");
        reference.setCheck(false);
        reference.setTimeout(config.getRpcTimeoutMs());
        reference.setRetries(config.getRpcRetries());
        return referenceCache.get(reference);
    }
}
