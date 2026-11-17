package me.sevj6.funCore.connection;

import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which addresses players may use to reach the server: the hostname they typed in their server list (sent in the
 * connection's handshake), e.g. {@code play.ianlabs.xyz} and not the raw IP that server scanners try. Connections from
 * this machine itself (loopback, e.g. the container's own health check) are always allowed; that can't be faked from
 * outside, unlike the hostname, which the client chooses. Private addresses aren't exempt: Docker's proxy hands outside
 * IPv6 connections over from one.
 */
public final class AddressRules {

    private final Set<String> allowed = new LinkedHashSet<>();

    /** @param allowedAddresses hostnames players must use; none means any */
    public AddressRules(List<String> allowedAddresses) {
        for (String address : allowedAddresses) {
            String host = normalize(address);
            if (!host.isEmpty()) allowed.add(host);
        }
    }

    public Set<String> getAllowed() {
        return Set.copyOf(allowed);
    }

    /** Whether there's anything to check (an empty list allows every address). */
    public boolean isActive() {
        return !allowed.isEmpty();
    }

    /**
     * Whether a connection may go ahead.
     *
     * @param from     the client's address (whoever is connecting)
     * @param hostname the address they connected to, as sent in the handshake (port and all), or {@code null} if unknown
     */
    public boolean allows(@Nullable InetAddress from, @Nullable String hostname) {
        if (!isActive() || isLocal(from)) return true;
        return hostname != null && allowed.contains(normalize(hostname));
    }

    /** This machine (inside the container: its own health check and tools). */
    static boolean isLocal(@Nullable InetAddress address) {
        return address != null && (address.isLoopbackAddress() || address.isAnyLocalAddress());
    }

    /**
     * The bare hostname from what a client sent: without the port, Forge's {@code \0FML\0} marker, a trailing dot or
     * IPv6 brackets, in lower case. {@code "Play.IanLabs.xyz.:25565"} is {@code "play.ianlabs.xyz"}.
     */
    static String normalize(String raw) {
        String host = raw.strip();
        int marker = host.indexOf('\0');
        if (marker >= 0) host = host.substring(0, marker);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            host = close > 0 ? host.substring(1, close) : host.substring(1);
        } else if (host.indexOf(':') == host.lastIndexOf(':') && host.indexOf(':') >= 0) {
            host = host.substring(0, host.indexOf(':')); // host:port (a bare IPv6 address has several colons)
        }
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.toLowerCase(Locale.ROOT);
    }
}
