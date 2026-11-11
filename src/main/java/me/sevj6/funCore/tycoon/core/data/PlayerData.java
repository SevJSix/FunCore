package me.sevj6.funCore.tycoon.core.data;

import me.sevj6.funCore.tycoon.core.sim.Cents;
import me.sevj6.funCore.tycoon.core.template.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One player's tycoon progress: their tycoon balance (its own money, separate from the server's economy), rebirths,
 * what they've bought, trophies and lifetime earnings. Owned ids are kept exactly as saved, even ones the template no
 * longer has (components get renamed while prototyping), so renaming something back brings it back.
 */
public final class PlayerData {

    /** Cents. */
    private long balance;
    private int rebirths;
    /** Cents earned in total, across every run. */
    private long lifetimeEarnings;
    /** Main-zone purchases (cleared on rebirth). */
    private final Set<String> ownedMain = new LinkedHashSet<>();
    /** Vault-zone purchases (kept through rebirths). */
    private final Set<String> ownedVault = new LinkedHashSet<>();
    /** Upgrade levels bought on ladder pads this run: the manual upgrade (pickaxe) and belt speed. */
    private int manualLevel, speedLevel, starLevel;
    /** When they last left the tycoon (epoch milliseconds, 0 = never), and what their plot earned by itself then. */
    private long lastSeen;
    private double awayRate;
    /** Whether the gate lasers are on (when the plot has them). On until the owner pulls the lever. */
    private boolean gateLocked = true;
    /** Unlocked trophies: id to when, in epoch milliseconds. */
    private final Map<String, Long> trophies = new LinkedHashMap<>();

    public long balance() {
        return balance;
    }

    public void setBalance(long cents) {
        balance = Math.max(0, Math.min(Cents.MAX, cents));
    }

    /** Adds income: to the balance and lifetime earnings, both saturating. */
    public void earn(long cents) {
        if (cents <= 0) return;
        balance = Cents.add(balance, cents);
        lifetimeEarnings = Cents.add(lifetimeEarnings, cents);
    }

    /** Takes {@code cents} if the balance has it, all or nothing. */
    public boolean spend(long cents) {
        if (cents < 0 || balance < cents) return false;
        balance -= cents;
        return true;
    }

    public int rebirths() {
        return rebirths;
    }

    public void setRebirths(int rebirths) {
        this.rebirths = Math.max(0, rebirths);
    }

    public long lifetimeEarnings() {
        return lifetimeEarnings;
    }

    void setLifetimeEarnings(long cents) {
        lifetimeEarnings = Math.max(0, Math.min(Cents.MAX, cents));
    }

    /** Every owned id as saved (both zones), including ones the template doesn't have. */
    public Set<String> owned() {
        Set<String> all = new LinkedHashSet<>(ownedMain);
        all.addAll(ownedVault);
        return Collections.unmodifiableSet(all);
    }

    public Set<String> ownedMain() {
        return Collections.unmodifiableSet(ownedMain);
    }

    public Set<String> ownedVault() {
        return Collections.unmodifiableSet(ownedVault);
    }

    /** Records a purchase, in the list for its zone. */
    public void own(String id, Component.Zone zone) {
        (zone == Component.Zone.VAULT ? ownedVault : ownedMain).add(id);
    }

    void ownMain(String id) {
        ownedMain.add(id);
    }

    void ownVault(String id) {
        ownedVault.add(id);
    }

    public int manualLevel() {
        return manualLevel;
    }

    public void setManualLevel(int level) {
        manualLevel = Math.max(0, level);
    }

    public int speedLevel() {
        return speedLevel;
    }

    public void setSpeedLevel(int level) {
        speedLevel = Math.max(0, level);
    }

    public int starLevel() {
        return starLevel;
    }

    public void setStarLevel(int level) {
        starLevel = Math.max(0, level);
    }

    public long lastSeen() {
        return lastSeen;
    }

    /** Cents a second their plot earned on its own (droppers, upgraders, earning cosmetics) when they left. */
    public double awayRate() {
        return awayRate;
    }

    /** Remembers when they left and what their plot was earning by itself. */
    public void leftAt(long when, double centsPerSecond) {
        lastSeen = when;
        awayRate = Math.max(0, centsPerSecond);
    }

    public boolean gateLocked() {
        return gateLocked;
    }

    public void setGateLocked(boolean locked) {
        gateLocked = locked;
    }

    public Map<String, Long> trophies() {
        return Collections.unmodifiableMap(trophies);
    }

    void unlockTrophy(String id, long at) {
        trophies.put(id, at);
    }

    /** A separate copy (for test mode to go back to). */
    public PlayerData copy() {
        PlayerData copy = new PlayerData();
        copy.balance = balance;
        copy.rebirths = rebirths;
        copy.lifetimeEarnings = lifetimeEarnings;
        copy.manualLevel = manualLevel;
        copy.speedLevel = speedLevel;
        copy.starLevel = starLevel;
        copy.lastSeen = lastSeen;
        copy.awayRate = awayRate;
        copy.gateLocked = gateLocked;
        copy.ownedMain.addAll(ownedMain);
        copy.ownedVault.addAll(ownedVault);
        copy.trophies.putAll(trophies);
        return copy;
    }
}
