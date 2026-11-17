package io.localfirst.dubbo;

import org.apache.dubbo.config.*;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class SpringTest {
    static final AtomicInteger RESOLVES = new AtomicInteger();
    static final AtomicBoolean AVAILABLE = new AtomicBoolean(true);

    public interface Echo { String echo(String value); }
    public static class Client {
        @DubboReference(url = "${test.provider.url}", check = false)
        Echo echo;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableDubbo(scanBasePackages = "io.localfirst.empty")
    public static class Config {
        @Bean
        public Client client() { return new Client(); }

        @Bean
        public static ReferencePostProcessor referencePostProcessor() {
            return new ReferencePostProcessor(new LocalCache() {
                @Override
                synchronized LocalFirstHandler.Target resolve(ReferenceConfig<?> reference) {
                    RESOLVES.incrementAndGet();
                    assertEquals(Echo.class.getName(), reference.getInterface());
                    return new LocalFirstHandler.Target((Echo) value -> "cached:" + value, AVAILABLE::get);
                }
            });
        }
    }

    @Test
    void realDubboInjectionWarmsAtReadyAndRetainsOriginalProxy() {
        RESOLVES.set(0);
        AVAILABLE.set(true);
        FrameworkModel framework = new FrameworkModel();
        ModuleModel module = framework.newApplication().newModule();
        ApplicationConfig provider = new ApplicationConfig(module.getApplicationModel());
        provider.setName("spring-cache-provider");
        provider.setQosEnable(false);
        module.getConfigManager().getApplicationConfigManager().setApplication(provider);
        ServiceConfig<Echo> service = new ServiceConfig<Echo>(module);
        ConfigurableApplicationContext context = null;
        try {
            service.setInterface(Echo.class);
            service.setRef((Echo) value -> "original:" + value);
            service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
            ProtocolConfig protocol = new ProtocolConfig("dubbo", -1);
            protocol.setHost("127.0.0.1");
            service.setProtocol(protocol);
            service.export();
            String endpoint = service.getExportedUrls().stream().filter(url -> "dubbo".equals(url.getProtocol()))
                    .findFirst().get().toIdentityString();
            SpringApplication application = new SpringApplication(Config.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setBannerMode(Banner.Mode.OFF);
            Map<String, Object> props = new HashMap<String, Object>();
            props.put("test.provider.url", endpoint);
            props.put("dubbo.application.name", "spring-cache-consumer");
            props.put("dubbo.application.qos-enable", false);
            props.put("dubbo.registry.address", RegistryConfig.NO_AVAILABLE);
            application.setDefaultProperties(props);
            context = application.run();
            Client client = context.getBean(Client.class);
            assertEquals(1, RESOLVES.get());
            assertEquals("cached:hello", client.echo.echo("hello"));
            assertEquals("cached:again", client.echo.echo("again"));
            assertEquals(1, RESOLVES.get());
            AVAILABLE.set(false);
            assertEquals("original:fallback", client.echo.echo("fallback"));
        } finally {
            if (context != null) { context.close(); }
            service.unexport();
            framework.destroy();
        }
    }
}
