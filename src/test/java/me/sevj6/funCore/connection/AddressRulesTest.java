package me.sevj6.funCore.connection;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressRulesTest {

    private static final AddressRules RULES = new AddressRules(List.of("play.ianlabs.xyz"));

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal); // a literal, so no lookup
    }

    @Test
    void onlyTheServersAddressWorksFromOutside() throws UnknownHostException {
        InetAddress outside = ip("74.67.226.208");
        assertTrue(RULES.allows(outside, "play.ianlabs.xyz:25565"));
        assertTrue(RULES.allows(outside, "Play.IanLabs.xyz."), "any case, a trailing dot");
        assertTrue(RULES.allows(outside, "play.ianlabs.xyz\0FML3\0"), "Forge clients");
        assertFalse(RULES.allows(outside, "158.69.233.198:25565"), "the raw IP");
        assertFalse(RULES.allows(outside, "mcadmin.ianlabs.xyz"), "another name for the same machine");
        assertFalse(RULES.allows(outside, null), "unknown (an old client's ping)");
        assertFalse(RULES.allows(outside, ""));
    }

    @Test
    void thisMachineAlwaysWorks() throws UnknownHostException {
        assertTrue(RULES.allows(ip("127.0.0.1"), "localhost"), "the container's own health check");
        assertTrue(RULES.allows(ip("::1"), null));
        assertFalse(RULES.allows(ip("172.18.0.1"), "158.69.233.198"), "Docker's proxy (outside IPv6 connections) isn't exempt");
        assertFalse(RULES.allows(ip("158.69.233.198"), "158.69.233.198"), "the server's public IP isn't local");
    }

    @Test
    void anEmptyListAllowsAnything() throws UnknownHostException {
        AddressRules open = new AddressRules(List.of());
        assertFalse(open.isActive());
        assertTrue(open.allows(ip("74.67.226.208"), "158.69.233.198"));
        assertFalse(new AddressRules(List.of("  ", "")).isActive(), "blank entries don't count");
    }

    @Test
    void normalizesWhatClientsSend() {
        assertEquals("play.ianlabs.xyz", AddressRules.normalize(" Play.IanLabs.xyz.:25565 "));
        assertEquals("158.69.233.198", AddressRules.normalize("158.69.233.198:25565"));
        assertEquals("2001:db8::1", AddressRules.normalize("[2001:db8::1]:25565"));
        assertEquals("2001:db8::1", AddressRules.normalize("2001:db8::1"), "a bare IPv6 address keeps its colons");
        assertEquals("play.ianlabs.xyz", AddressRules.normalize("play.ianlabs.xyz\0FML3\0"));
    }
}
