package me.sevj6.funCore.economy;

import me.sevj6.funCore.FunCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The global cash economy. Every amount is in cents (see {@link Cash}).
 *
 * <p>Earning and selling are {@link #deposit}, buying is {@link #withdraw} (which fails if the player can't afford
 * it), and player-to-player payments are {@link #transfer}. Every change needs a short {@code reason} (e.g.
 * {@code "pay"}, {@code "minigame reward"}) for the transaction log.
 *
 * <p>All balances live in memory and every method must be called on the main thread, which makes each operation
 * instant and all-or-nothing. Changes are written to disk on a background thread about a second later, and again on
 * shutdown.
 */
public final class Economy {

    private static final long SAVE_DELAY_TICKS = 20L;

    private final FunCore plugin;
    private final EconomyStorage storage;
    private final TransactionLog log;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "FunCore-Economy-Writer"));
    private final Map<UUID, Account> accounts = new HashMap<>();
    private final long startingBalance;

    private boolean saveScheduled;
    /** Set if balances.yml couldn't be read, so we never overwrite the real data with an empty file. */
    private boolean savingBlocked;
    private boolean shutDown;

    public Economy(FunCore plugin, long startingBalance) {
        this.plugin = plugin;
        this.startingBalance = startingBalance;
        File directory = new File(plugin.getDataFolder(), "economy");
        if (!directory.isDirectory() && !directory.mkdirs()) plugin.getSLF4JLogger().error("Could not create {}", directory);
        this.storage = new YamlEconomyStorage(directory);
        this.log = new TransactionLog(new File(directory, "transactions.log").toPath());
    }

    /** Loads every account from disk. Call once on startup. */
    public void load() {
        try {
            accounts.putAll(storage.load());
            plugin.getSLF4JLogger().info("Loaded {} economy account(s)", accounts.size());
        } catch (IOException e) {
            savingBlocked = true;
            plugin.getSLF4JLogger().error("FAILED TO LOAD BALANCES. Economy changes will NOT be saved until this is fixed "
                    + "and the server is restarted, so balances.yml isn't overwritten.", e);
        }
    }

    /** Writes everything to disk and stops the background writer. Call on plugin disable. */
    public void shutdown() {
        shutDown = true;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) plugin.getSLF4JLogger().warn("Economy writer did not finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        saveNow(List.copyOf(accounts.values()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------------------------------------------------

    /** Creates the player's account if they don't have one yet (with the starting balance), and keeps their name current. */
    public Account ensureAccount(Player player) {
        Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            account = new Account(player.getUniqueId(), player.getName(), startingBalance, List.of());
            accounts.put(account.id(), account);
            if (startingBalance > 0) record("CREATE", account.name(), startingBalance, "starting balance", balanceOf(account));
            markDirty();
        } else if (!account.name().equals(player.getName())) {
            account = account.withName(player.getName());
            accounts.put(account.id(), account);
            markDirty();
        }
        return account;
    }

    public @Nullable Account getAccount(UUID id) {
        return accounts.get(id);
    }

    /**
     * Finds an account by player name, ignoring case. Online players are matched first; otherwise the last known
     * name of every account is checked.
     */
    public @Nullable Account findAccount(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && accounts.containsKey(online.getUniqueId())) return accounts.get(online.getUniqueId());
        for (Account account : accounts.values()) {
            if (account.name().equalsIgnoreCase(name)) return account;
        }
        return null;
    }

    public Collection<Account> getAccounts() {
        return List.copyOf(accounts.values());
    }

    /** Every account, richest first (ties broken by name). */
    public List<Account> getTopAccounts() {
        List<Account> sorted = new ArrayList<>(accounts.values());
        sorted.sort(Comparator.comparingLong(Account::balance).reversed().thenComparing(Account::name, String.CASE_INSENSITIVE_ORDER));
        return sorted;
    }

    public long getStartingBalance() {
        return startingBalance;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Balances
    // ------------------------------------------------------------------------------------------------------------

    /** The player's balance in cents, or 0 if they have no account. */
    public long getBalance(UUID player) {
        Account account = accounts.get(player);
        return account == null ? 0 : account.balance();
    }

    public boolean has(UUID player, long amount) {
        return getBalance(player) >= amount;
    }

    /** Gives a player cash (earning, selling, rewards). */
    public TransactionResult deposit(UUID player, long amount, String reason) {
        if (!validAmount(amount)) return TransactionResult.INVALID_AMOUNT;
        Account account = accounts.get(player);
        if (account == null) return TransactionResult.NO_ACCOUNT;
        if (account.balance() > Cash.MAX - amount) return TransactionResult.BALANCE_LIMIT;

        account = update(account.withBalance(account.balance() + amount));
        record("DEPOSIT", account.name(), amount, reason, balanceOf(account));
        return TransactionResult.SUCCESS;
    }

    /** Takes cash from a player (buying). Fails, changing nothing, if they can't afford it. */
    public TransactionResult withdraw(UUID player, long amount, String reason) {
        if (!validAmount(amount)) return TransactionResult.INVALID_AMOUNT;
        Account account = accounts.get(player);
        if (account == null) return TransactionResult.NO_ACCOUNT;
        if (account.balance() < amount) return TransactionResult.INSUFFICIENT_FUNDS;

        account = update(account.withBalance(account.balance() - amount));
        record("WITHDRAW", account.name(), amount, reason, balanceOf(account));
        return TransactionResult.SUCCESS;
    }

    /**
     * Moves cash from one player to another, all or nothing. If the receiver is offline, they're told about it the
     * next time they join.
     */
    public TransactionResult transfer(UUID from, UUID to, long amount, String reason) {
        if (!validAmount(amount)) return TransactionResult.INVALID_AMOUNT;
        if (from.equals(to)) return TransactionResult.SAME_ACCOUNT;
        Account sender = accounts.get(from);
        Account receiver = accounts.get(to);
        if (sender == null || receiver == null) return TransactionResult.NO_ACCOUNT;
        if (sender.balance() < amount) return TransactionResult.INSUFFICIENT_FUNDS;
        if (receiver.balance() > Cash.MAX - amount) return TransactionResult.BALANCE_LIMIT;

        sender = update(sender.withBalance(sender.balance() - amount));
        receiver = receiver.withBalance(receiver.balance() + amount);
        if (Bukkit.getPlayer(to) == null) {
            receiver = receiver.withOfflinePayment(new Account.OfflinePayment(sender.name(), amount, System.currentTimeMillis()));
        }
        receiver = update(receiver);
        record("TRANSFER", sender.name() + " -> " + receiver.name(), amount, reason,
                balanceOf(sender) + ", " + balanceOf(receiver));
        return TransactionResult.SUCCESS;
    }

    /** Sets a balance outright (admin use). */
    public TransactionResult setBalance(UUID player, long amount, String reason) {
        if (amount < 0 || amount > Cash.MAX) return TransactionResult.INVALID_AMOUNT;
        Account account = accounts.get(player);
        if (account == null) return TransactionResult.NO_ACCOUNT;

        account = update(account.withBalance(amount));
        record("SET", account.name(), amount, reason, balanceOf(account));
        return TransactionResult.SUCCESS;
    }

    /**
     * Hard reset: backs up every account, then puts every balance back to the starting balance and clears pending
     * offline payment messages.
     *
     * @return the backup file
     * @throws IOException if the backup couldn't be written, in which case nothing was reset
     */
    public File resetAll(String reason) throws IOException {
        File backup = storage.backup(List.copyOf(accounts.values()));
        accounts.replaceAll((id, account) -> account.withBalance(startingBalance).withoutOfflinePayments());
        record("RESET_ALL", accounts.size() + " accounts", startingBalance, reason, "backup: " + backup.getName());
        markDirty();
        return backup;
    }

    /** Removes and returns the payments a player received while offline. */
    public List<Account.OfflinePayment> takeOfflinePayments(UUID player) {
        Account account = accounts.get(player);
        if (account == null || account.offlinePayments().isEmpty()) return List.of();
        update(account.withoutOfflinePayments());
        return account.offlinePayments();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------------------------

    private static boolean validAmount(long amount) {
        return amount > 0 && amount <= Cash.MAX;
    }

    private Account update(Account account) {
        accounts.put(account.id(), account);
        markDirty();
        return account;
    }

    private static String balanceOf(Account account) {
        return account.name() + ": " + Cash.format(account.balance());
    }

    private void record(String type, String who, long amount, String reason, String balances) {
        String line = TransactionLog.line(type, who, amount, reason, balances);
        runOnWriter(() -> {
            try {
                log.write(line);
            } catch (IOException e) {
                plugin.getSLF4JLogger().warn("Could not write to the transaction log", e);
            }
        });
    }

    /** Saves about a second from now, batching every change made in the meantime into one write. */
    private void markDirty() {
        if (saveScheduled || shutDown) return; // on shutdown, shutdown() does the final save
        saveScheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            saveScheduled = false;
            List<Account> snapshot = List.copyOf(accounts.values());
            runOnWriter(() -> saveNow(snapshot));
        }, SAVE_DELAY_TICKS);
    }

    private void saveNow(List<Account> snapshot) {
        if (savingBlocked) return;
        try {
            storage.save(snapshot);
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("Failed to save balances", e);
        }
    }

    /** Runs on the background writer, or right here if it has already shut down. */
    private void runOnWriter(Runnable task) {
        if (writer.isShutdown()) {
            task.run();
        } else {
            writer.execute(task);
        }
    }
}
