package io.localfirst.dubbo;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.metadata.MetadataInfo;
import org.apache.dubbo.metadata.ServiceNameMapping;
import org.apache.dubbo.registry.client.DefaultServiceInstance.Endpoint;
import org.apache.dubbo.registry.client.ServiceDiscovery;
import org.apache.dubbo.registry.client.ServiceDiscoveryFactory;
import org.apache.dubbo.registry.client.ServiceInstance;
import org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.model.ModuleModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;

/** 订阅映射和实例变化，更新本机代理缓存；不修改注册中心配置。 */
class LocalCache {

    private static final Log LOGGER = LogFactory.getLog(LocalCache.class);
    private final Predicate<String> local;
    private final Map<ReferenceConfig<?>, LocalFirstHandler.Target> targets =
            new IdentityHashMap<ReferenceConfig<?>, LocalFirstHandler.Target>();
    private final Map<URL, ServiceDiscovery> discoveries = new HashMap<URL, ServiceDiscovery>();
    private final Map<String, Set<String>> mappings = new HashMap<String, Set<String>>();
    private final Map<URL, Map<String, List<ServiceInstance>>> instances =
            new HashMap<URL, Map<String, List<ServiceInstance>>>();
    private final Map<ServiceDiscovery, Map<String, MetadataInfo>> metadata =
            new IdentityHashMap<ServiceDiscovery, Map<String, MetadataInfo>>();
    private final Map<ReferenceConfig<?>, Selection> selections =
            new IdentityHashMap<ReferenceConfig<?>, Selection>();
    private final Map<ReferenceConfig<?>, List<Consumer<LocalFirstHandler.Target>>> callbacks =
            new IdentityHashMap<ReferenceConfig<?>, List<Consumer<LocalFirstHandler.Target>>>();
    private final Map<ReferenceConfig<?>, String> states = new IdentityHashMap<ReferenceConfig<?>, String>();
    private RegistryWatch watcher;
    private boolean closed;
    private final Map<Invoker<?>, LocalFirstHandler.Target> liveTargets =
            new ConcurrentHashMap<Invoker<?>, LocalFirstHandler.Target>();

    RegistryWatch newWatcher() {
        return new RegistryWatch(this);
    }

    synchronized void watch(ReferenceConfig<?> reference, Consumer<LocalFirstHandler.Target> callback) {
        if (closed) {
            return;
        }
        if (watcher == null) {
            watcher = newWatcher();
        }
        callbacks.computeIfAbsent(reference, key -> new ArrayList<Consumer<LocalFirstHandler.Target>>()).add(callback);
        callback.accept(resolve(reference));
    }

    synchronized void mappingChanged(String key, Set<String> apps) {
        if (closed) { return; }
        mappings.put(key, apps == null ? Collections.<String>emptySet() : new LinkedHashSet<String>(apps));
        refresh();
    }

    synchronized void instancesChanged(URL registry, String app, List<ServiceInstance> values) {
        if (closed) { return; }
        instances.computeIfAbsent(registry, key -> new HashMap<String, List<ServiceInstance>>())
                .put(app, values == null ? Collections.<ServiceInstance>emptyList()
                        : new ArrayList<ServiceInstance>(values));
        refresh();
    }

    synchronized void reconcile() {
        if (closed) { return; }
        mappings.clear();
        instances.clear();
        // 失败的元数据不缓存，让实例先上线、元数据后发布的场景能在核对时恢复。
        refresh();
    }

    private void refresh() {
        for (Map.Entry<ReferenceConfig<?>, List<Consumer<LocalFirstHandler.Target>>> entry : callbacks.entrySet()) {
            ReferenceConfig<?> reference = entry.getKey();
            LocalFirstHandler.Target previous = targets.remove(reference);
            try {
                LocalFirstHandler.Target next = resolve(reference);
                if (next != previous) {
                    for (Consumer<LocalFirstHandler.Target> callback : entry.getValue()) {
                        callback.accept(next);
                    }
                }
                if (next != previous && previous != null) {
                    previous.retire();
                }
                if (next == null) {
                    selections.remove(reference);
                }
            } catch (RuntimeException exception) {
                // 未确认实例下线时保留已知代理，下次事件或周期核对再试。
                targets.put(reference, previous);
                LOGGER.warn("刷新本机引用失败, service=" + reference.getInterface(), exception);
            }
        }
        pruneMetadata();
    }

    private void pruneMetadata() {
        Map<ServiceDiscovery, Set<String>> current = new IdentityHashMap<ServiceDiscovery, Set<String>>();
        for (Map.Entry<URL, ServiceDiscovery> entry : discoveries.entrySet()) {
            Map<String, List<ServiceInstance>> byApp = instances.get(entry.getKey());
            if (byApp == null) { continue; }
            Set<String> revisions = current.computeIfAbsent(entry.getValue(), key -> new LinkedHashSet<String>());
            for (List<ServiceInstance> values : byApp.values()) {
                for (ServiceInstance instance : values) {
                    revisions.add(instance.getServiceName() + "|"
                            + ServiceInstanceMetadataUtils.getExportedServicesRevision(instance));
                }
            }
        }
        for (Map.Entry<ServiceDiscovery, Set<String>> entry : current.entrySet()) {
            Map<String, MetadataInfo> cached = metadata.get(entry.getKey());
            if (cached != null) {
                cached.keySet().retainAll(entry.getValue());
            }
        }
    }

    LocalCache() {
        this(LocalAddress::isLocal);
    }

    LocalCache(Predicate<String> local) {
        this.local = local;
    }

    synchronized LocalFirstHandler.Target resolve(ReferenceConfig<?> reference) {
        if (closed) { return null; }
        if (targets.containsKey(reference)) {
            return targets.get(reference);
        }
        // 显式直连和 injvm 表达调用方的选址约束，不在此处覆盖。
        if ((reference.getUrl() != null && !reference.getUrl().trim().isEmpty())
                || Boolean.TRUE.equals(reference.isInjvm()) || Boolean.TRUE.equals(reference.isGeneric())) {
            logState(reference, "explicit", "保留显式 Dubbo 引用, service=" + reference.getInterface());
            targets.put(reference, null);
            return null;
        }
        URL query = consumerUrl(reference);
        boolean failed = false;
        for (RegistryConfig registry : registries(reference)) {
            if (Boolean.FALSE.equals(registry.isSubscribe()) || RegistryConfig.NO_AVAILABLE.equals(registry.getAddress())) {
                continue;
            }
            URL registryUrl = registryUrl(registry, reference.getScopeModel());
            try {
                Set<String> apps = apps(reference, query, registryUrl);
                LOGGER.debug("本机引用扫描, service=" + query.getServiceKey() + ", registry=" + registryUrl.getAddress()
                        + ", namespace=" + registryUrl.getParameter("namespace", "") + ", applications=" + apps);
                if (apps.isEmpty()) {
                    continue;
                }
                ServiceDiscovery discovery = discovery(registryUrl);
                for (String app : apps) {
                    List<ServiceInstance> providers = instances(discovery, registryUrl, app);
                    for (ServiceInstance instance : providers) {
                        if (!instance.isEnabled() || !instance.isHealthy() || !local.test(instance.getHost())) {
                            continue;
                        }
                        MetadataInfo info = metadata(discovery, instance);
                        if (info == null || info == MetadataInfo.EMPTY) {
                            LOGGER.warn("本机实例缺少接口元数据, application=" + app + ", host=" + instance.getHost());
                            continue;
                        }
                        List<URL> endpoints = endpoints(query, instance, info);
                        for (URL endpoint : endpoints) {
                            String key = registryUrl.toFullString() + "|" + app + "|"
                                    + ServiceInstanceMetadataUtils.getExportedServicesRevision(instance)
                                    + "|" + endpoint.toFullString();
                            Selection existing = selections.get(reference);
                            if (existing != null && existing.key.equals(key)) {
                                targets.put(reference, existing.target);
                                return existing.target;
                            }
                            LocalFirstHandler.Target target = connect(reference, endpoint, query);
                            selections.put(reference, new Selection(key, target));
                            targets.put(reference, target);
                            logState(reference, key, "本机引用已缓存, service=" + query.getServiceKey() + ", application=" + app
                                    + ", endpoint=" + endpoint.getProtocol() + "://" + endpoint.getAddress());
                            return target;
                        }
                    }
                }
            } catch (RuntimeException exception) {
                failed = true;
                LOGGER.warn("本机引用扫描失败，保留原引用, service=" + query.getServiceKey()
                        + ", registry=" + registryUrl.getAddress(), exception);
            }
        }
        if (failed && selections.containsKey(reference)) {
            LocalFirstHandler.Target previous = selections.get(reference).target;
            targets.put(reference, previous);
            return previous;
        }
        targets.put(reference, null);
        logState(reference, "", "本机引用未命中，使用原 Dubbo 引用, service=" + query.getServiceKey());
        return null;
    }

    private Set<String> apps(ReferenceConfig<?> reference, URL query, URL registryUrl) {
        if (reference.getProvidedBy() != null && !reference.getProvidedBy().trim().isEmpty()) {
            Set<String> result = new LinkedHashSet<String>();
            for (String app : reference.getProvidedBy().split(",")) {
                if (!app.trim().isEmpty()) {
                    result.add(app.trim());
                }
            }
            return result;
        }
        String key = reference.getScopeModel().getInternalId() + "|" + registryUrl.toFullString()
                + "|" + reference.getInterface();
        Set<String> apps = mappings.get(key);
        URL mappingUrl = query.addParameter("registry", registryUrl.getProtocol())
                .addParameterIfAbsent("REGISTRY_CLUSTER", registryUrl.getParameter("id", "default"));
        if (watcher != null) {
            watcher.mapping(key, registryUrl, mappingUrl);
        }
        if (apps == null) {
            apps = lookup(mappingUrl);
            apps = apps == null ? Collections.<String>emptySet() : new LinkedHashSet<String>(apps);
            mappings.put(key, apps);
        }
        return apps;
    }

    Set<String> lookup(URL query) {
        return mapping(query).getRemoteMapping(query);
    }

    ServiceNameMapping mapping(URL query) {
        return ServiceNameMapping.getDefaultExtension(query.getScopeModel());
    }

    ServiceDiscovery discovery(URL registryUrl) {
        ServiceDiscovery discovery = discoveries.get(registryUrl);
        if (discovery == null) {
            // 复用 Dubbo 作用域内的发现客户端，只查询，不注册或修改实例。
            URL url = registryUrl.removeParameter("registry-type")
                    .addParameter("interface", ServiceDiscovery.class.getName());
            discovery = ServiceDiscoveryFactory.getExtension(url).getServiceDiscovery(url);
            discoveries.put(registryUrl, discovery);
        }
        return discovery;
    }

    private List<ServiceInstance> instances(ServiceDiscovery discovery, URL registryUrl, String app) {
        Map<String, List<ServiceInstance>> byApp = instances.get(registryUrl);
        if (byApp == null) {
            byApp = new HashMap<String, List<ServiceInstance>>();
            instances.put(registryUrl, byApp);
        }
        if (watcher != null) {
            watcher.instances(registryUrl, app, discovery);
        }
        if (!byApp.containsKey(app)) {
            List<ServiceInstance> values = discovery.getInstances(app);
            byApp.put(app, values == null ? Collections.<ServiceInstance>emptyList() : values);
        }
        return byApp.get(app);
    }

    MetadataInfo metadata(ServiceDiscovery discovery, ServiceInstance instance) {
        String revision = ServiceInstanceMetadataUtils.getExportedServicesRevision(instance);
        if (revision == null || revision.isEmpty()) {
            return null;
        }
        Map<String, MetadataInfo> byRevision = metadata.get(discovery);
        if (byRevision == null) {
            byRevision = new HashMap<String, MetadataInfo>();
            metadata.put(discovery, byRevision);
        }
        String key = instance.getServiceName() + "|" + revision;
        if (!byRevision.containsKey(key)) {
            instance.setApplicationModel(discovery.getUrl().getOrDefaultApplicationModel());
            MetadataInfo value = discovery.getRemoteMetadata(revision, Collections.singletonList(instance));
            if (value != null && value != MetadataInfo.EMPTY) {
                byRevision.put(key, value);
            }
        }
        return byRevision.get(key);
    }

    static List<URL> endpoints(URL query, ServiceInstance instance, MetadataInfo info) {
        List<URL> urls = new ArrayList<URL>();
        for (MetadataInfo.ServiceInfo service : info.getServices().values()) {
            if (!query.getServiceInterface().equals(service.getName())
                    || !value(query.getParameter("group")).equals(value(service.getGroup()))
                    || !value(query.getParameter("version")).equals(value(service.getVersion()))) {
                continue;
            }
            String protocol = service.getProtocol();
            String filter = query.getParameter("protocol");
            if (filter != null && !filter.isEmpty() && !Arrays.asList(filter.split(",")).contains(protocol)) {
                continue;
            }
            if (protocol == null || protocol.isEmpty()) {
                continue;
            }
            Endpoint endpoint = ServiceInstanceMetadataUtils.getEndpoint(instance, protocol);
            int port = endpoint == null ? service.getPort() : endpoint.getPort();
            if (port <= 0) {
                continue;
            }
            String path = service.getPath() == null || service.getPath().isEmpty()
                    ? service.getName() : service.getPath();
            urls.add(new URL(protocol, instance.getHost(), port, path, service.getAllParams())
                    .addParameter("interface", service.getName()));
        }
        Collections.sort(urls, (left, right) -> left.toFullString().compareTo(right.toFullString()));
        return urls;
    }

    LocalFirstHandler.Target connect(ReferenceConfig<?> reference, URL endpoint, URL query) {
        ModuleModel module = reference.getScopeModel();
        URL url = merge(endpoint, query).addParameter("side", "consumer")
                .setScopeModel(module).setServiceModel(null);
        ConsumerModel ownedConsumer = null;
        for (ConsumerModel consumer : module.getServiceRepository().getReferredServices()) {
            if (consumer.getReferenceConfig() == reference) {
                url = url.setServiceModel(consumer);
                break;
            }
        }
        if (url.getServiceModel() == null) {
            ConsumerModel consumer = module.registerInternalConsumer(reference.getInterfaceClass(), url);
            ownedConsumer = consumer;
            url = url.setServiceModel(consumer);
        }
        Invoker<?> invoker;
        try {
            invoker = module.getExtensionLoader(Protocol.class).getAdaptiveExtension()
                    .refer(reference.getInterfaceClass(), url);
        } catch (RuntimeException exception) {
            if (ownedConsumer != null && !module.isDestroyed()) {
                module.getServiceRepository().unregisterConsumer(ownedConsumer);
            }
            throw exception;
        }
        try {
            Object proxy = module.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension().getProxy(invoker);
            final ConsumerModel owned = ownedConsumer;
            final URL clientUrl = url;
            LocalFirstHandler.Target target = new LocalFirstHandler.Target(proxy, invoker::isAvailable, () -> {
                try {
                    invoker.destroy();
                } catch (RuntimeException exception) {
                    LOGGER.warn("销毁本机引用失败, service=" + reference.getInterface(), exception);
                } finally {
                    liveTargets.remove(invoker);
                    if (owned != null && !module.isDestroyed()) {
                        module.getServiceRepository().unregisterConsumer(owned);
                    }
                }
            }, method -> clientUrl.getMethodParameter(method.getName(), "async", false));
            liveTargets.put(invoker, target);
            return target;
        } catch (RuntimeException exception) {
            invoker.destroy();
            if (ownedConsumer != null && !module.isDestroyed()) {
                module.getServiceRepository().unregisterConsumer(ownedConsumer);
            }
            throw exception;
        }
    }

    static URL merge(URL endpoint, URL query) {
        // 沿用 Dubbo 标准合并，移除提供端 transporter/线程池参数并保留消费端显式配置。
        ClusterUtils merger = new ClusterUtils();
        merger.setApplicationModel(query.getOrDefaultApplicationModel());
        return merger.mergeUrl(endpoint, query.getParameters());
    }

    static URL consumerUrl(ReferenceConfig<?> reference) {
        Map<String, String> params = new HashMap<String, String>();
        if (reference.getConsumer() != null) {
            AbstractConfig.appendParameters(params, reference.getConsumer());
        }
        AbstractConfig.appendParameters(params, reference);
        if (reference.getMethods() != null) {
            for (MethodConfig method : reference.getMethods()) {
                AbstractConfig.appendParameters(params, method, method.getName());
            }
        }
        params.put("interface", reference.getInterface());
        return new URL("consumer", "127.0.0.1", 0, reference.getInterface(), params)
                .setScopeModel(reference.getScopeModel());
    }

    static URL registryUrl(RegistryConfig registry, ModuleModel module) {
        Map<String, String> params = new HashMap<String, String>();
        AbstractConfig.appendParameters(params, registry);
        String address = registry.getAddress();
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalStateException("Dubbo 注册中心地址为空");
        }
        if (!address.contains("://")) {
            if (registry.getProtocol() == null) {
                throw new IllegalStateException("Dubbo 注册中心未指定协议");
            }
            address = registry.getProtocol() + "://" + address;
        }
        if (registry.getId() != null) {
            params.put("id", registry.getId());
        }
        return URL.valueOf(address).addParametersIfAbsent(params).setScopeModel(module);
    }

    static List<RegistryConfig> registries(ReferenceConfig<?> reference) {
        if (reference.getRegistries() != null && !reference.getRegistries().isEmpty()) {
            return reference.getRegistries();
        }
        if (reference.getRegistryIds() != null && !reference.getRegistryIds().isEmpty()) {
            List<RegistryConfig> result = new ArrayList<RegistryConfig>();
            for (String id : reference.getRegistryIds().split(",")) {
                result.add(reference.getScopeModel().getConfigManager().getRegistry(id.trim())
                        .orElseThrow(() -> new IllegalStateException("找不到 Dubbo 注册中心: " + id)));
            }
            return result;
        }
        return reference.getScopeModel().getConfigManager().getDefaultRegistries();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    synchronized void close() {
        if (closed) { return; }
        closed = true;
        if (watcher != null) {
            watcher.close();
        }
        for (List<Consumer<LocalFirstHandler.Target>> listeners : callbacks.values()) {
            for (Consumer<LocalFirstHandler.Target> callback : listeners) {
                callback.accept(null);
            }
        }
        callbacks.clear();
        for (Selection selection : selections.values()) {
            selection.target.retire();
        }
        for (LocalFirstHandler.Target target : liveTargets.values()) {
            target.retire();
        }
        selections.clear();
        states.clear();
        targets.clear();
        // 发现客户端由 Dubbo 作用域管理，不能关闭应用共享的客户端。
        discoveries.clear();
        mappings.clear();
        instances.clear();
        metadata.clear();
    }

    private void logState(ReferenceConfig<?> reference, String state, String message) {
        if (!state.equals(states.put(reference, state))) {
            LOGGER.info(message);
        }
    }

    private static final class Selection {
        final String key;
        final LocalFirstHandler.Target target;

        Selection(String key, LocalFirstHandler.Target target) {
            this.key = key;
            this.target = target;
        }
    }
}
