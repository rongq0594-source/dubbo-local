package io.localfirst.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.metadata.MetadataInfo;
import org.apache.dubbo.registry.client.DefaultServiceInstance;
import org.apache.dubbo.registry.client.ServiceDiscovery;
import org.apache.dubbo.registry.client.ServiceInstance;
import org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalCacheTest {
    static final String APP = "test-provider";
    static final String HOST = "192.0.2.10";
    FrameworkModel framework;
    ApplicationModel app;
    ModuleModel module;
    RegistryConfig registry;
    FakeCache cache;

    public interface Query { String query(); }

    @BeforeEach
    void setup() {
        framework = new FrameworkModel();
        app = framework.newApplication();
        module = app.newModule();
        registry = new RegistryConfig(app, "nacos://registry.example:8848");
        registry.setId("original");
        Map<String, String> params = new HashMap<String, String>();
        params.put("namespace", "test-namespace");
        params.put("registry-type", "interface");
        registry.setParameters(params);
        cache = new FakeCache();
    }

    @AfterEach
    void cleanup() {
        cache.close();
        framework.destroy();
    }

    ReferenceConfig<Query> reference() {
        ReferenceConfig<Query> ref = new ReferenceConfig<Query>(module);
        ref.setInterface(Query.class);
        ref.setGroup("group");
        ref.setVersion("1");
        ref.setTimeout(2300);
        ref.setRegistries(Collections.singletonList(registry));
        return ref;
    }

    DefaultServiceInstance instance(String host) {
        DefaultServiceInstance result = new DefaultServiceInstance(APP, host, 8080, app);
        result.setHealthy(true);
        result.setEnabled(true);
        result.getMetadata().put("dubbo.metadata.revision", "revision1");
        ServiceInstanceMetadataUtils.setEndpoints(result, Collections.singletonMap("tri", 20888));
        return result;
    }

    MetadataInfo info(String group, String version, String protocol, int port) {
        MetadataInfo result = new MetadataInfo(APP);
        result.addService(new URL(protocol, HOST, port, Query.class.getName())
                .addParameter("interface", Query.class.getName())
                .addParameter("group", group).addParameter("version", version));
        return result;
    }

    @Test
    void readsMappingAndCachesLocalEndpointWithoutChangingRegistry() {
        cache.providers = Arrays.<ServiceInstance>asList(instance("198.51.100.30"), instance(HOST));
        cache.info = info("group", "1", "tri", 12345);
        ReferenceConfig<Query> ref = reference();

        LocalFirstHandler.Target first = cache.resolve(ref);
        assertNotNull(first);
        assertSame(first, cache.resolve(ref));
        assertEquals("tri", cache.selected.getProtocol());
        assertEquals(20888, cache.selected.getPort());
        assertEquals(HOST, cache.selected.getHost());
        assertEquals("test-namespace", cache.registryUrl.getParameter("namespace"));
        assertEquals("interface", registry.getParameters().get("registry-type"));
        assertEquals(1, cache.mappingCalls);
        assertEquals(1, cache.instanceCalls);
        assertEquals(1, cache.metadataCalls);
        assertEquals(1, cache.connectCalls);
        assertNull(ref.getUrl());

        LocalFirstHandler handler = new LocalFirstHandler((Query) () -> "remote");
        handler.setLocal(first);
        Query proxy = (Query) Proxy.newProxyInstance(Query.class.getClassLoader(),
                new Class<?>[]{Query.class}, handler);
        for (int i = 0; i < 10; i++) {
            assertEquals("local", proxy.query());
        }
        assertEquals(1, cache.instanceCalls);
    }

    @Test
    void providedByAvoidsMappingAndSharedAppInstancesAreLoadedOnce() {
        cache.providers = Collections.<ServiceInstance>singletonList(instance(HOST));
        cache.info = info("group", "1", "tri", 20888);
        ReferenceConfig<Query> first = reference();
        ReferenceConfig<Query> second = reference();
        first.setProvidedBy(APP);
        second.setProvidedBy(APP);
        assertNotNull(cache.resolve(first));
        assertNotNull(cache.resolve(second));
        assertEquals(0, cache.mappingCalls);
        assertEquals(1, cache.instanceCalls);
        assertEquals(1, cache.metadataCalls);
    }

    @Test
    void noLocalOrUnhealthyInstanceLeavesOriginalReference() {
        DefaultServiceInstance unhealthy = instance(HOST);
        unhealthy.setHealthy(false);
        cache.providers = Arrays.<ServiceInstance>asList(unhealthy, instance("198.51.100.30"));
        cache.info = info("group", "1", "tri", 20888);
        assertNull(cache.resolve(reference()));
        assertEquals(0, cache.connectCalls);
    }

    @Test
    void rejectsWrongGroupVersionProtocolAndMissingMapping() {
        cache.providers = Collections.<ServiceInstance>singletonList(instance(HOST));
        cache.info = info("wrong", "1", "tri", 20888);
        assertNull(cache.resolve(reference()));
        cache.close();
        cache.info = info("group", "other", "tri", 20888);
        assertNull(cache.resolve(reference()));
        cache.close();
        cache.info = info("group", "1", "tri", 20888);
        ReferenceConfig<Query> dubboOnly = reference();
        dubboOnly.setProtocol("dubbo");
        assertNull(cache.resolve(dubboOnly));
        cache.close();
        cache.appNames = Collections.emptySet();
        assertNull(cache.resolve(reference()));
        assertEquals(0, cache.connectCalls);
    }

    @Test
    void explicitUrlIsNotOverridden() {
        ReferenceConfig<Query> ref = reference();
        ref.setUrl("dubbo://127.0.0.1:20880");
        assertNull(cache.resolve(ref));
        assertEquals(0, cache.mappingCalls);
        assertEquals(0, cache.instanceCalls);
    }

    @Test
    void registryFailureDoesNotChangeOriginal() {
        cache.failure = new IllegalStateException("registry unavailable");
        assertNull(cache.resolve(reference()));
        assertEquals("interface", registry.getParameters().get("registry-type"));
    }

    @Test
    void endpointUsesDubboMetadataRatherThanApplicationPort() {
        DefaultServiceInstance provider = instance(HOST);
        provider.setEndpoints(Collections.singletonList(new DefaultServiceInstance.Endpoint(20999, "dubbo")));
        URL query = LocalCache.consumerUrl(reference());
        List<URL> endpoints = LocalCache.endpoints(query, provider, info("group", "1", "dubbo", 20000));
        assertEquals(1, endpoints.size());
        assertEquals("dubbo", endpoints.get(0).getProtocol());
        assertEquals(20999, endpoints.get(0).getPort());
        provider.setEndpoints(null);
        endpoints = LocalCache.endpoints(query, provider, info("group", "1", "dubbo", 20000));
        assertEquals(20000, endpoints.get(0).getPort());
    }

    @Test
    void mergeRemovesProviderTransportSettingsWithoutChangingSource() {
        URL endpoint = new URL("tri", HOST, 20888, Query.class.getName())
                .addParameter("transporter", "noHostNetty")
                .addParameter("default.transporter", "providerOnly")
                .addParameter("threadpool", "providerPool")
                .addParameter("serialization", "fastjson2");
        URL query = LocalCache.consumerUrl(reference());

        URL merged = LocalCache.merge(endpoint, query);

        assertNull(merged.getParameter("transporter"));
        assertNull(merged.getParameter("default.transporter"));
        assertNull(merged.getParameter("threadpool"));
        assertEquals("fastjson2", merged.getParameter("serialization"));
        assertEquals("2300", merged.getParameter("timeout"));
        assertEquals("tri", merged.getProtocol());
        assertEquals(20888, merged.getPort());
        assertEquals("noHostNetty", endpoint.getParameter("transporter"));
        assertNull(query.getParameter("transporter"));
    }

    @Test
    void mergeRetainsExplicitConsumerTransportSettings() {
        URL endpoint = new URL("tri", HOST, 20888, Query.class.getName())
                .addParameter("transporter", "providerOnly");
        URL query = LocalCache.consumerUrl(reference())
                .addParameter("transporter", "netty4").addParameter("client", "netty4");

        URL merged = LocalCache.merge(endpoint, query);

        assertEquals("netty4", merged.getParameter("transporter"));
        assertEquals("netty4", merged.getParameter("client"));
    }

    class FakeCache extends LocalCache {
        List<ServiceInstance> providers = Collections.emptyList();
        Set<String> appNames = Collections.singleton(APP);
        MetadataInfo info;
        RuntimeException failure;
        int mappingCalls, instanceCalls, metadataCalls, connectCalls;
        URL registryUrl, selected;

        FakeCache() { super(HOST::equals); }

        @Override
        Set<String> lookup(URL query) {
            mappingCalls++;
            if (failure != null) { throw failure; }
            return appNames;
        }

        @Override
        ServiceDiscovery discovery(URL url) {
            registryUrl = url;
            return discovery;
        }

        @Override
        LocalFirstHandler.Target connect(ReferenceConfig<?> reference, URL endpoint, URL query) {
            selected = endpoint;
            connectCalls++;
            return new LocalFirstHandler.Target((Query) () -> "local", () -> true);
        }

        final ServiceDiscovery discovery = (ServiceDiscovery) Proxy.newProxyInstance(
                ServiceDiscovery.class.getClassLoader(), new Class<?>[]{ServiceDiscovery.class},
                (proxy, method, args) -> {
                    if ("getInstances".equals(method.getName())) { instanceCalls++; return providers; }
                    if ("getRemoteMetadata".equals(method.getName())) { metadataCalls++; return info; }
                    if ("getUrl".equals(method.getName())) {
                        return new URL("nacos", "registry.example", 8848).setScopeModel(module);
                    }
                    if ("toString".equals(method.getName())) { return "FakeDiscovery"; }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
