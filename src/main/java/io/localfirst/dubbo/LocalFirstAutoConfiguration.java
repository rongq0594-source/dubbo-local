package io.localfirst.dubbo;

import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为 Spring 中的 Dubbo 引用启用启动缓存。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({DubboReference.class, ReferenceConfig.class})
@ConditionalOnProperty(prefix = "local-first.dubbo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LocalFirstAutoConfiguration {

    @Bean
    public static ReferencePostProcessor referencePostProcessor() {
        return new ReferencePostProcessor();
    }
}
