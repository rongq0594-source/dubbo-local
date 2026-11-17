package io.localfirst.dubbo;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 解析并缓存当前机器的网卡地址，用于判断 Dubbo 提供者是否与消费者同机。 */
final class LocalAddress {

    private static final Log LOGGER = LogFactory.getLog(LocalAddress.class);
    private static final Set<String> LOCAL_ADDRESSES = findLocalAddresses();
    private static final ConcurrentHashMap<String, Boolean> MATCH_CACHE = new ConcurrentHashMap<String, Boolean>();

    private LocalAddress() {
    }

    static boolean isLocal(String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        Boolean cached = MATCH_CACHE.get(host);
        if (cached != null) {
            return cached;
        }
        boolean local = resolvesToLocalAddress(host);
        MATCH_CACHE.putIfAbsent(host, local);
        return local;
    }

    private static boolean resolvesToLocalAddress(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!address.isLoopbackAddress() && LOCAL_ADDRESSES.contains(normalize(address.getHostAddress()))) {
                    return true;
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("解析 Dubbo 提供者地址失败, host=" + host, exception);
            return false;
        }
        return false;
    }

    private static Set<String> findLocalAddresses() {
        Set<String> addresses = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                Enumeration<InetAddress> interfaceAddresses = networkInterface.getInetAddresses();
                while (interfaceAddresses.hasMoreElements()) {
                    InetAddress address = interfaceAddresses.nextElement();
                    if (!address.isLoopbackAddress()) {
                        addresses.add(normalize(address.getHostAddress()));
                    }
                }
            }
        } catch (SocketException exception) {
            LOGGER.warn("读取本机网卡地址失败", exception);
            return addresses;
        }
        return addresses;
    }

    private static String normalize(String address) {
        int scopeIndex = address.indexOf('%');
        return scopeIndex < 0 ? address : address.substring(0, scopeIndex);
    }
}
