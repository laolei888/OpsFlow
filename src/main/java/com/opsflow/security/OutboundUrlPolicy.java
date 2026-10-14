package com.opsflow.security;

import com.opsflow.common.BusinessException;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class OutboundUrlPolicy {
    private final OpsFlowSecurityProperties properties;

    public OutboundUrlPolicy(OpsFlowSecurityProperties properties) {
        this.properties = properties;
    }

    public URI validate(String value) {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Webhook URL 格式无效");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http")
                && properties.getOutboundRequests().isAllowHttp())) {
            throw new BusinessException("Webhook URL 必须使用 HTTPS");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new BusinessException("Webhook URL 格式无效");
        }
        String host;
        try {
            host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Webhook 域名格式无效");
        }
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
            rejectPrivateUnlessAllowed(host);
            return uri;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!isPublic(address)) rejectPrivateUnlessAllowed(host);
            }
        } catch (UnknownHostException ex) {
            throw new BusinessException("Webhook 域名无法解析");
        }
        return uri;
    }

    private void rejectPrivateUnlessAllowed(String host) {
        if (properties.getOutboundRequests().isAllowPrivateNetworks() || allowedHosts().contains(host)) return;
        throw new BusinessException("Webhook 不允许访问本机、内网或保留地址");
    }

    private boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            return (bytes[0] & 0xfe) != 0xfc;
        }
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if (first == 0 || first >= 224) return false;
        if (first == 100 && second >= 64 && second <= 127) return false;
        if (first == 192 && second == 0) return false;
        if (first == 198 && (second == 18 || second == 19)) return false;
        return !(first == 198 && second == 51) && !(first == 203 && second == 0);
    }

    private Set<String> allowedHosts() {
        String configured = properties.getOutboundRequests().getAllowedHosts();
        if (configured == null || configured.isBlank()) return Set.of();
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
