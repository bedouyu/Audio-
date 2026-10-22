package com.bedouyu.audiorag.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoopbackBypassProxySelectorTest {
    private final ProxySelector delegate = mock(ProxySelector.class);
    private final LoopbackBypassProxySelector selector = new LoopbackBypassProxySelector(delegate);

    @Test void loopbackAddressesAndLocalDatabaseSocketsAlwaysConnectDirectly() {
        for (String target : List.of("http://localhost:18081/api/health", "http://LOCALHOST:9000/",
                "socket://127.0.0.1:3306", "http://127.0.0.0/", "http://127.24.12.9/",
                "http://127.255.255.255/", "http://[::1]:6333/", "http://[0:0:0:0:0:0:0:1]/"))
            assertThat(selector.select(URI.create(target))).as(target).containsExactly(Proxy.NO_PROXY);
        verifyNoInteractions(delegate);
    }

    @Test void externalDestinationsAndSimilarLookingNamesRetainDelegateProxies() {
        var proxies = List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.test", 8080)));
        when(delegate.select(any(URI.class))).thenReturn(proxies);
        for (String target : List.of("https://www.bing.com/news/search", "https://localhost.example/",
                "https://127.example.test/", "http://128.0.0.1/", "http://[::2]/", "urn:external:resource")) {
            URI uri = URI.create(target);
            assertThat(selector.select(uri)).as(target).isSameAs(proxies);
            verify(delegate).select(uri);
        }
    }

    @Test void connectionFailuresAreForwardedWithoutChangingTheirArguments() {
        URI uri = URI.create("https://www.bing.com/news/search");
        var address = InetSocketAddress.createUnresolved("proxy.test", 8080);
        var failure = new IOException("connection failed");
        selector.connectFailed(uri, address, failure);
        verify(delegate).connectFailed(uri, address, failure);
    }

    @Test void nullArgumentsFollowProxySelectorContract() {
        assertThatNullPointerException().isThrownBy(() -> new LoopbackBypassProxySelector(null));
        assertThatIllegalArgumentException().isThrownBy(() -> selector.select(null));
        URI uri = URI.create("https://example.test/");
        var address = InetSocketAddress.createUnresolved("proxy.test", 8080);
        var failure = new IOException();
        assertThatIllegalArgumentException().isThrownBy(() -> selector.connectFailed(null, address, failure));
        assertThatIllegalArgumentException().isThrownBy(() -> selector.connectFailed(uri, null, failure));
        assertThatIllegalArgumentException().isThrownBy(() -> selector.connectFailed(uri, address, null));
        verifyNoInteractions(delegate);
    }

    @Test void installationRequiresEnabledSystemProxyAndDoesNotWrapTwice() {
        ProxySelector previousSelector = ProxySelector.getDefault();
        String previousProperty = System.getProperty("java.net.useSystemProxies");
        try {
            ProxySelector.setDefault(delegate);
            System.clearProperty("java.net.useSystemProxies");
            LoopbackBypassProxySelector.installIfEnabled();
            assertThat(ProxySelector.getDefault()).isSameAs(delegate);
            System.setProperty("java.net.useSystemProxies", "true");
            LoopbackBypassProxySelector.installIfEnabled();
            ProxySelector installed = ProxySelector.getDefault();
            assertThat(installed).isInstanceOf(LoopbackBypassProxySelector.class);
            LoopbackBypassProxySelector.installIfEnabled();
            assertThat(ProxySelector.getDefault()).isSameAs(installed);
            ProxySelector.setDefault(null);
            LoopbackBypassProxySelector.installIfEnabled();
            assertThat(ProxySelector.getDefault()).isNull();
        } finally {
            ProxySelector.setDefault(previousSelector);
            if (previousProperty == null) System.clearProperty("java.net.useSystemProxies");
            else System.setProperty("java.net.useSystemProxies", previousProperty);
        }
    }
}
