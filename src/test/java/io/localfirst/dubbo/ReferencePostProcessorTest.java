package io.localfirst.dubbo;

import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.reference.ReferenceBeanManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReferencePostProcessorTest {
    interface Query { String query(); }
    static class Client {
        @DubboReference(check = false)
        Query query;
    }

    @Test
    void warmsAtReadyAndDoesNotLookUpRegistryDuringRequests() {
        AtomicInteger resolutions = new AtomicInteger();
        LocalCache cache = new LocalCache() {
            @Override
            synchronized LocalFirstHandler.Target resolve(ReferenceConfig<?> reference) {
                resolutions.incrementAndGet();
                return new LocalFirstHandler.Target((Query) () -> "local", () -> true);
            }
        };
        Query original = () -> "remote";
        ReferenceBean<Query> bean = new ReferenceBean<Query>() {
            @Override
            public Query getObject() { return original; }
            @Override
            public ReferenceConfig getReferenceConfig() { return null; }
        };
        ReferenceBeanManager manager = new ReferenceBeanManager() {
            @Override
            public Collection<ReferenceBean> getReferences() { return Collections.<ReferenceBean>singletonList(bean); }
        };
        GenericApplicationContext context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("referenceBeanManager", manager);
        context.refresh();
        ReferencePostProcessor processor = new ReferencePostProcessor(cache);
        processor.setApplicationContext(context);
        Client client = new Client();
        client.query = original;
        processor.postProcessAfterInitialization(client, "client");
        assertEquals("remote", client.query.query());
        assertEquals(0, resolutions.get());
        processor.onApplicationEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
        assertEquals(1, resolutions.get());
        assertEquals("local", client.query.query());
        assertEquals("local", client.query.query());
        assertEquals(1, resolutions.get());
        processor.destroy();
        context.close();
    }
}
