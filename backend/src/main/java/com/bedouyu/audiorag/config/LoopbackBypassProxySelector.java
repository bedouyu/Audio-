package com.bedouyu.audiorag.config;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/** Keeps local services direct while retaining the system proxy for external requests. */
public final class LoopbackBypassProxySelector extends ProxySelector {
    private final ProxySelector delegate;

    public LoopbackBypassProxySelector(ProxySelector delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public static synchronized void installIfEnabled() {
        if (!Boolean.getBoolean("java.net.useSystemProxies")) return;
        ProxySelector current = ProxySelector.getDefault();
        if (current != null && !(current instanceof LoopbackBypassProxySelector))
            ProxySelector.setDefault(new LoopbackBypassProxySelector(current));
    }

    @Override public List<Proxy> select(URI uri) {
        if (uri == null) throw new IllegalArgumentException("uri must not be null");
        return loopback(uri.getHost()) ? List.of(Proxy.NO_PROXY) : delegate.select(uri);
    }

    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {
        if (uri == null || address == null || failure == null)
            throw new IllegalArgumentException("uri, address and failure must not be null");
        delegate.connectFailed(uri, address, failure);
    }

    private boolean loopback(String host) {
        if (host == null) return false;
        if ("localhost".equalsIgnoreCase(host)) return true;
        String literal = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        if ("::1".equals(literal) || "0:0:0:0:0:0:0:1".equals(literal)) return true;
        String[] octets = literal.split("\\.", -1);
        if (octets.length != 4 || !"127".equals(octets[0])) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) return false;
            for (int index = 0; index < octet.length(); index++)
                if (octet.charAt(index) < '0' || octet.charAt(index) > '9') return false;
            if (Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }
}
