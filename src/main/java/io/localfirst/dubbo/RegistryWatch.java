package io.localfirst.dubbo;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.metadata.MappingChangedEvent;
import org.apache.dubbo.metadata.MappingListener;
import org.apache.dubbo.metadata.ServiceNameMapping;
import org.apache.dubbo.registry.client.ServiceDiscovery;
import org.apache.dubbo.registry.client.ServiceInstance;
import org.apache.dubbo.registry.client.event.ServiceInstancesChangedEvent;
import org.apache.dubbo.registry.client.event.listener.ServiceInstancesChangedListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 监听注册中心推送，后台串行刷新；周期核对用于补齐首次映射和延迟发布的元数据。 */
class RegistryWatch {

    private static final Log LOGGER = LogFactory.getLog(RegistryWatch.class);
    private final LocalCache cache;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "dubbo-local-watch");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Runnable> mappingStops = new HashMap<String, Runnable>();
    private final Map<URL, Map<String, InstanceListener>> instanceListeners =
            new HashMap<URL, Map<String, InstanceListener>>();
    private volatile boolean closed;

    RegistryWatch(LocalCache cache) {
        this(cache, 5000);
    }

    RegistryWatch(LocalCache cache, long intervalMillis) {
        this.cache = cache;
        if (intervalMillis > 0) {
            executor.scheduleWithFixedDelay(() -> run(cache::reconcile), intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    void mapping(String key, URL registry, URL query) {
        if (mappingStops.containsKey(key) || closed) {
            return;
        }
        ServiceNameMapping mapping = cache.mapping(query);
        MappingListener listener = new MappingListener() {
            private volatile boolean stopped;

            @Override
            public void onEvent(MappingChangedEvent event) {
                Set<String> apps = event.getApps() == null ? Collections.<String>emptySet()
                        : new LinkedHashSet<String>(event.getApps());
                submit(() -> {
                    if (!stopped) {
                        cache.mappingChanged(key, apps);
                    }
                });
            }

            @Override
            public void stop() {
                stopped = true;
            }
        };
        // 先记录停止动作，避免订阅时同步回调造成重复订阅。
        mappingStops.put(key, () -> {
            listener.stop();
            mapping.stopListen(query, listener);
        });
        try {
            mapping.getAndListen(registry, query, listener);
        } catch (RuntimeException exception) {
            Runnable stop = mappingStops.remove(key);
            try {
                stop.run();
            } catch (RuntimeException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    void instances(URL registry, String app, ServiceDiscovery discovery) {
        Map<String, InstanceListener> listeners = instanceListeners.computeIfAbsent(registry,
                key -> new HashMap<String, InstanceListener>());
        if (listeners.containsKey(app) || closed) {
            return;
        }
        InstanceListener listener = new InstanceListener(app, discovery, registry);
        listeners.put(app, listener);
        try {
            discovery.addServiceInstancesChangedListener(listener);
        } catch (RuntimeException exception) {
            listeners.remove(app);
            listener.destroy();
            throw exception;
        }
    }

    private void submit(Runnable task) {
        if (closed) { return; }
        try {
            executor.execute(() -> run(task));
        } catch (RejectedExecutionException exception) {
            if (!closed) { throw exception; }
        }
    }

    private void run(Runnable task) {
        if (closed) { return; }
        try {
            task.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("注册中心监听刷新失败，等待后续通知或周期核对", exception);
        }
    }

    void close() {
        closed = true;
        executor.shutdownNow();
        for (Runnable stop : mappingStops.values()) {
            try {
                stop.run();
            } catch (RuntimeException exception) {
                LOGGER.warn("取消接口映射监听失败", exception);
            }
        }
        mappingStops.clear();
        for (Map<String, InstanceListener> listeners : instanceListeners.values()) {
            for (InstanceListener listener : listeners.values()) {
                try {
                    listener.destroy();
                } catch (RuntimeException exception) {
                    LOGGER.warn("取消实例监听失败", exception);
                }
            }
        }
        instanceListeners.clear();
    }

    private final class InstanceListener extends ServiceInstancesChangedListener {
        private final URL registry;
        private final String app;

        InstanceListener(String app, ServiceDiscovery discovery, URL registry) {
            super(Collections.singleton(app), discovery);
            this.registry = registry;
            this.app = app;
        }

        @Override
        public void onEvent(ServiceInstancesChangedEvent event) {
            List<ServiceInstance> values = event.getServiceInstances() == null
                    ? Collections.<ServiceInstance>emptyList()
                    : new ArrayList<ServiceInstance>(event.getServiceInstances());
            submit(() -> {
                if (!isDestroyed()) {
                    cache.instancesChanged(registry, app, values);
                }
            });
        }

        // Dubbo 也在同一客户端注册监听器，不能按应用名把两者判成同一个。
        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
