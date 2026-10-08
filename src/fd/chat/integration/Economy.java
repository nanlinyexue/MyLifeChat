package fd.chat.integration;

import fd.chat.config.YamlConfig;
import fd.chat.storage.Storage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;

/**
 * XConomy 余额读写（直连 MySQL）。
 *
 * 为什么不走 Vault：Velocity 侧没有经济 API，而 XConomy 本身就把余额存在 MySQL
 * （表 xconomy_&lt;suffix&gt;，字段 UID / player / balance）。直连的好处：
 *   - 不需要任何后端插件配合
 *   - 不需要 Redis
 *   - 扣款可以用 SQL 做成原子操作，并发下不会超扣
 */
public final class Economy {

    private final DataSource ds;
    private final String walletTable;
    private final String currencyName;
    private final long debitCooldownMs;
    private final Logger logger;
    private final boolean enabled;

    private final ConcurrentHashMap<UUID, AtomicLong> lastDebit = new ConcurrentHashMap<>();

    public Economy(YamlConfig config, Logger logger) {
        this.logger = logger;
        this.enabled = config.bool("economy.enabled", true);
        this.currencyName = config.str("economy.currency-name", "元");
        this.walletTable = sanitize(config.str("economy.wallet-table", "xconomy_sc"));
        this.debitCooldownMs = config.l("economy.debit-cooldown-ms", 2500);

        DataSource pool = null;
        if (this.enabled) {
            pool = Storage.pool(
                config.str("economy.host", "127.0.0.1"),
                config.i("economy.port", 3306),
                config.str("economy.database", "sc"),
                config.str("economy.username", "root"),
                config.str("economy.password", ""),
                config.i("mysql.pool-size", 4),
                "fdchat-econ",
                logger
            );
        }
        this.ds = pool;
    }

    public boolean enabled() {
        return this.enabled && this.ds != null;
    }

    public String currencyName() {
        return this.currencyName;
    }

    public String walletTable() {
        return this.walletTable;
    }

    public String format(double amount) {
        if (amount == Math.rint(amount) && !Double.isInfinite(amount)) {
            return String.format("%,d%s", (long) amount, this.currencyName);
        }
        return String.format("%.2f%s", amount, this.currencyName);
    }

    public double balance(UUID uuid) {
        if (!enabled()) {
            return 0;
        }
        String sql = "SELECT balance FROM `" + this.walletTable + "` WHERE UID = ?";
        try (Connection conn = this.ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0;
            }
        } catch (SQLException ex) {
            this.logger.warn("查询余额失败（表 {}）。", this.walletTable, ex);
            return 0;
        }
    }

    /**
     * 原子扣款。余额不足或并发时返回 false，调用方据此拒绝操作。
     */
    public boolean withdraw(UUID uuid, double amount) {
        if (!enabled()) {
            return false;
        }
        if (amount <= 0) {
            return true;
        }

        AtomicLong last = this.lastDebit.computeIfAbsent(uuid, k -> new AtomicLong(0));
        long now = System.currentTimeMillis();
        if (now - last.get() < this.debitCooldownMs) {
            return false; // 连点保护
        }
        last.set(now);

        String sql = "UPDATE `" + this.walletTable + "` SET balance = balance - ? "
            + "WHERE UID = ? AND balance >= ?";
        try (Connection conn = this.ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, amount);
            ps.setString(2, uuid.toString());
            ps.setDouble(3, amount);
            boolean ok = ps.executeUpdate() == 1;
            if (!ok) {
                last.set(0); // 余额不足允许立即重试
            }
            return ok;
        } catch (SQLException ex) {
            this.logger.warn("扣款失败。", ex);
            last.set(0);
            return false;
        }
    }

    /** 退款 / 奖励。 */
    public boolean deposit(UUID uuid, double amount) {
        if (!enabled() || amount <= 0) {
            return false;
        }
        String sql = "INSERT INTO `" + this.walletTable + "` (UID, player, balance, hidden) "
            + "VALUES (?, '', ?, 0) ON DUPLICATE KEY UPDATE balance = balance + VALUES(balance)";
        try (Connection conn = this.ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setDouble(2, amount);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            this.logger.warn("入账失败。", ex);
            return false;
        }
    }

    private static String sanitize(String table) {
        return table.replaceAll("[^A-Za-z0-9_]", "");
    }

    public void close() {
        if (this.ds instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 关闭期异常忽略
            }
        }
    }
}
