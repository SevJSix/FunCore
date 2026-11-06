package me.sevj6.funCore.economy;

import me.sevj6.funCore.util.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps every account in {@code plugins/FunCore/economy/balances.yml}. Amounts are stored in cents.
 *
 * <pre>
 * accounts:
 *   069a79f4-44e9-4726-a5be-fca90e38aaf5:
 *     name: Notch
 *     balance: 1250            # $12.50
 *     offline-payments:
 *       - {from: jeb_, amount: 500, time: 1762430000000}
 * </pre>
 */
public final class YamlEconomyStorage implements EconomyStorage {

    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final File directory;
    private final File file;

    public YamlEconomyStorage(File directory) {
        this.directory = directory;
        this.file = new File(directory, "balances.yml");
    }

    @Override
    public Map<UUID, Account> load() throws IOException {
        Map<UUID, Account> accounts = new HashMap<>();
        if (!file.isFile()) return accounts;

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException e) {
            throw new IOException("balances.yml is not valid YAML: " + e.getMessage(), e);
        }

        ConfigurationSection section = yaml.getConfigurationSection("accounts");
        if (section == null) return accounts;
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) continue;
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                throw new IOException("balances.yml has an invalid UUID: " + key);
            }

            List<Account.OfflinePayment> payments = new ArrayList<>();
            for (Map<?, ?> map : entry.getMapList("offline-payments")) {
                Object from = map.get("from");
                Object amount = map.get("amount");
                Object time = map.get("time");
                if (from != null && amount instanceof Number a) {
                    payments.add(new Account.OfflinePayment(from.toString(), a.longValue(),
                            time instanceof Number t ? t.longValue() : 0L));
                }
            }
            accounts.put(id, new Account(id, entry.getString("name", "unknown"), entry.getLong("balance"), payments));
        }
        return accounts;
    }

    @Override
    public void save(Collection<Account> accounts) throws IOException {
        File temp = new File(directory, file.getName() + ".tmp");
        toYaml(accounts).save(temp);
        Utils.moveReplacing(temp, file);
    }

    @Override
    public File backup(Collection<Account> accounts) throws IOException {
        File backup = new File(directory, "balances-backup-" + LocalDateTime.now().format(BACKUP_STAMP) + ".yml");
        toYaml(accounts).save(backup);
        return backup;
    }

    private static YamlConfiguration toYaml(Collection<Account> accounts) {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection section = yaml.createSection("accounts");
        for (Account account : accounts) {
            ConfigurationSection entry = section.createSection(account.id().toString());
            entry.set("name", account.name());
            entry.set("balance", account.balance());
            if (!account.offlinePayments().isEmpty()) {
                List<Map<String, Object>> payments = new ArrayList<>();
                for (Account.OfflinePayment payment : account.offlinePayments()) {
                    payments.add(Map.of("from", payment.fromName(), "amount", payment.amount(), "time", payment.time()));
                }
                entry.set("offline-payments", payments);
            }
        }
        return yaml;
    }
}
