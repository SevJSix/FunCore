package me.sevj6.funCore.economy;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Where balances are kept between restarts. Behind an interface so it can be swapped out (e.g. for MySQL) if FunCore
 * ever runs on several servers.
 */
public interface EconomyStorage {

    /** Reads every account. Called once on startup, on the main thread. */
    Map<UUID, Account> load() throws IOException;

    /** Writes every account. May be called off the main thread; {@code accounts} is an immutable snapshot. */
    void save(Collection<Account> accounts) throws IOException;

    /** Writes a copy of the accounts to a separate backup and returns where it went. */
    File backup(Collection<Account> accounts) throws IOException;
}
