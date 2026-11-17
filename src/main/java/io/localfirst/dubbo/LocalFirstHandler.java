package io.localfirst.dubbo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/** 请求只读取本机缓存；已发出的调用不自动重放到远程。 */
final class LocalFirstHandler implements InvocationHandler {

    private final Object original;
    private volatile Target local;

    LocalFirstHandler(Object original) {
        this.original = original;
    }

    void setLocal(Target local) {
        this.local = local;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            return "LocalFirst[" + original.getClass().getName() + "]";
        }
        Target target = local;
        Object delegate = target != null && target.available.getAsBoolean() ? target.proxy : original;
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException exception) {
            throw exception.getTargetException();
        }
    }

    static final class Target {
        final Object proxy;
        final BooleanSupplier available;

        Target(Object proxy, BooleanSupplier available) {
            this.proxy = proxy;
            this.available = available;
        }
    }
}
