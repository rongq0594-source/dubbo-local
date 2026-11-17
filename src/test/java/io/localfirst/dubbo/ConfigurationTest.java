package io.localfirst.dubbo;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.io.InputStream;
import java.util.Collections;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @Test
    void doesNotInstallGlobalRegistryOrRouterHooks() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/META-INF/spring.factories")) {
            assertNotNull(input);
            properties.load(input);
        }
        assertFalse(properties.containsKey("org.springframework.boot.env.EnvironmentPostProcessor"));
        assertNull(getClass().getResource("/META-INF/dubbo/org.apache.dubbo.rpc.cluster.router.state.StateRouterFactory"));
    }

    @Test
    void disabledStarterHasNoReferencePostProcessorAndDoesNotChangeRegistry() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Collections.<String, Object>singletonMap("local-first.dubbo.enabled", "false")));
        context.register(LocalFirstAutoConfiguration.class);
        context.refresh();
        assertTrue(context.getBeansOfType(ReferencePostProcessor.class).isEmpty());
        assertNull(context.getEnvironment().getProperty("dubbo.registry.parameters.registry-type"));
        context.close();
    }
}
