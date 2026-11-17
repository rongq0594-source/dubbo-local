package io.localfirst.dubbo;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.reference.ReferenceBeanManager;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** 收集 Spring 中的 Dubbo 引用，在启动完成后填充本机代理缓存。 */
public class ReferencePostProcessor implements BeanPostProcessor, Ordered, ApplicationContextAware,
        ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Log LOGGER = LogFactory.getLog(ReferencePostProcessor.class);
    private final Map<Object, LocalFirstHandler> handlers = new IdentityHashMap<Object, LocalFirstHandler>();
    private final Map<Object, Boolean> beans = new IdentityHashMap<Object, Boolean>();
    private final LocalCache cache;
    private ApplicationContext context;
    private boolean ready;

    public ReferencePostProcessor() {
        this(new LocalCache());
    }

    ReferencePostProcessor(LocalCache cache) {
        this.cache = cache;
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }

    @Override
    public void setApplicationContext(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public synchronized Object postProcessAfterInitialization(Object bean, String name) {
        Object target = AopProxyUtils.getSingletonTarget(bean);
        final Object actual = target == null ? bean : target;
        if (beans.put(actual, Boolean.TRUE) != null) {
            return bean;
        }
        ReflectionUtils.doWithFields(actual.getClass(), field -> {
            if (field.getAnnotation(DubboReference.class) == null || Modifier.isStatic(field.getModifiers())) {
                return;
            }
            if (Modifier.isFinal(field.getModifiers()) || !field.getType().isInterface()) {
                LOGGER.warn("跳过不支持包装的 Dubbo 字段, field=" + field.getName());
                return;
            }
            ReflectionUtils.makeAccessible(field);
            Object original = ReflectionUtils.getField(field, actual);
            if (original == null || (Proxy.isProxyClass(original.getClass())
                    && Proxy.getInvocationHandler(original) instanceof LocalFirstHandler)) {
                return;
            }
            LocalFirstHandler handler = handlers.get(original);
            if (handler == null) {
                handler = new LocalFirstHandler(original);
                handlers.put(original, handler);
                if (ready) {
                    initialize(original, handler);
                }
            }
            Object proxy = Proxy.newProxyInstance(field.getType().getClassLoader(),
                    new Class<?>[]{field.getType()}, handler);
            ReflectionUtils.setField(field, actual, proxy);
        });
        return bean;
    }

    @Override
    public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        if (event.getApplicationContext() != context || ready) {
            return;
        }
        ready = true;
        for (Map.Entry<Object, LocalFirstHandler> entry : new ArrayList<Map.Entry<Object, LocalFirstHandler>>(handlers.entrySet())) {
            initialize(entry.getKey(), entry.getValue());
        }
        LOGGER.info("本地引用缓存初始化完成, referenceCount=" + handlers.size());
    }

    private void initialize(Object original, LocalFirstHandler handler) {
        try {
            // 按原代理身份匹配，可区分同接口的不同 group、version 和注册中心。
            for (ReferenceBeanManager manager : context.getBeansOfType(ReferenceBeanManager.class).values()) {
                for (ReferenceBean<?> bean : manager.getReferences()) {
                    if (bean.getObject() == original) {
                        ReferenceConfig<?> reference = bean.getReferenceConfig();
                        handler.setLocal(cache.resolve(reference));
                        return;
                    }
                }
            }
            LOGGER.warn("未找到原 Dubbo ReferenceBean，保留原引用, proxyType=" + original.getClass().getName());
        } catch (RuntimeException exception) {
            LOGGER.warn("初始化本机引用失败，保留原 Dubbo 引用", exception);
        }
    }

    @Override
    public synchronized void destroy() {
        cache.close();
        handlers.clear();
        beans.clear();
    }
}
