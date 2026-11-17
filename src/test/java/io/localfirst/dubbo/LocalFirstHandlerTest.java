package io.localfirst.dubbo;

import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class LocalFirstHandlerTest {
    interface Query { String query(); }

    private Query proxy(LocalFirstHandler handler) {
        return (Query) Proxy.newProxyInstance(Query.class.getClassLoader(), new Class<?>[]{Query.class}, handler);
    }

    @Test
    void usesOriginalWhenCacheIsEmpty() {
        AtomicInteger calls = new AtomicInteger();
        Query original = () -> { calls.incrementAndGet(); return "remote"; };
        Query proxy = proxy(new LocalFirstHandler(original));
        assertEquals("remote", proxy.query());
        assertEquals(1, calls.get());
    }

    @Test
    void readsCacheAndFallsBackBeforeInvocationWhenUnavailable() {
        AtomicInteger remote = new AtomicInteger();
        AtomicBoolean available = new AtomicBoolean(true);
        Query original = () -> { remote.incrementAndGet(); return "remote"; };
        LocalFirstHandler handler = new LocalFirstHandler(original);
        handler.setLocal(new LocalFirstHandler.Target((Query) () -> "local", available::get));
        Query proxy = proxy(handler);
        assertEquals("local", proxy.query());
        assertEquals(0, remote.get());
        available.set(false);
        assertEquals("remote", proxy.query());
        assertEquals(1, remote.get());
    }

    @Test
    void doesNotReplayNetworkTimeoutOrBusinessFailure() {
        AtomicInteger remote = new AtomicInteger();
        Query original = () -> { remote.incrementAndGet(); return "remote"; };
        LocalFirstHandler handler = new LocalFirstHandler(original);
        RpcException timeout = new RpcException(RpcException.TIMEOUT_EXCEPTION, "timeout");
        handler.setLocal(new LocalFirstHandler.Target((Query) () -> { throw timeout; }, () -> true));
        Query proxy = proxy(handler);
        assertSame(timeout, assertThrows(RpcException.class, proxy::query));
        IllegalStateException business = new IllegalStateException("business");
        handler.setLocal(new LocalFirstHandler.Target((Query) () -> { throw business; }, () -> true));
        assertSame(business, assertThrows(IllegalStateException.class, proxy::query));
        assertEquals(0, remote.get());
    }

    @Test
    void preservesObjectIdentityContract() {
        Query proxy = proxy(new LocalFirstHandler((Query) () -> "remote"));
        assertTrue(proxy.equals(proxy));
        assertFalse(proxy.equals(new Object()));
        assertEquals(System.identityHashCode(proxy), proxy.hashCode());
        assertNotNull(proxy.toString());
    }
}
