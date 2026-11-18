package io.localfirst.dubbo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.apache.dubbo.rpc.RpcContext;

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
        boolean acquired = target != null && target.acquire();
        Object delegate = acquired ? target.proxy : original;
        boolean asynchronous = false;
        try {
            Object result = method.invoke(delegate, args);
            CompletionStage<?> completion = result instanceof CompletionStage ? (CompletionStage<?>) result : null;
            if (acquired && completion == null && target.async.test(method)) {
                completion = RpcContext.getServiceContext().getCompletableFuture();
            }
            if (acquired && completion != null) {
                // 异步结果完成前仍保留连接，避免刷新缓存中断在途调用。
                completion.whenComplete((value, failure) -> target.release());
                asynchronous = true;
            }
            return result;
        } catch (InvocationTargetException exception) {
            throw exception.getTargetException();
        } finally {
            if (acquired && !asynchronous) {
                target.release();
            }
        }
    }

    static final class Target {
        final Object proxy;
        final BooleanSupplier available;
        private final Runnable cleanup;
        private final Predicate<Method> async;
        private int calls;
        private boolean retired;
        private boolean destroyed;

        Target(Object proxy, BooleanSupplier available) {
            this(proxy, available, () -> { });
        }

        Target(Object proxy, BooleanSupplier available, Runnable cleanup) {
            this(proxy, available, cleanup, method -> false);
        }

        Target(Object proxy, BooleanSupplier available, Runnable cleanup, Predicate<Method> async) {
            this.proxy = proxy;
            this.available = available;
            this.cleanup = cleanup;
            this.async = async;
        }

        synchronized boolean acquire() {
            if (retired || !available.getAsBoolean()) {
                return false;
            }
            calls++;
            return true;
        }

        synchronized void release() {
            calls--;
            dispose();
        }

        synchronized void retire() {
            retired = true;
            dispose();
        }

        private void dispose() {
            if (retired && calls == 0 && !destroyed) {
                destroyed = true;
                cleanup.run();
            }
        }
    }
}
