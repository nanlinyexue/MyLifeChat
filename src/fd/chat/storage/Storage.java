package fd.chat.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fd.chat.config.YamlConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;

/**
 * MySQL 存储层：连接池 + 建表迁移 + 通用查询封装。
 *
 * 表（前缀可配，默认 fdchat_）：
 *   nickname          昵称主表，nickname 上有 UNIQUE 索引 —— 唯一性由数据库强保证
 *   nickname_history  改名历史（可审计、可追溯）
 *   player            玩家状态（禁言 / 默认频道 / 提及开关 / 私聊开关）
 *   ignore            忽略关系
 */
public final class Storage implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final String prefix;
    private final Logger logger;

    private Storage(HikariDataSource ds, String prefix, Logger logger) {
        this.dataSource = ds;
        this.prefix = prefix;
        this.logger = logger;
    }

    public static Storage open(YamlConfig config, Logger logger) {
        registerDriver(logger);

        String host = config.str("mysql.host", "127.0.0.1");
        int port = config.i("mysql.port", 3306);
        String db = config.str("mysql.database", "floatdream");
        String user = config.str("mysql.username", "root");
        String pass = config.str("mysql.password", "");

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbc(host, port, db));
        hc.setUsername(user);
        hc.setPassword(pass);
        hc.setMaximumPoolSize(config.i("mysql.pool-size", 4));
        hc.setMinimumIdle(1);
        hc.setPoolName("fdchat");
        hc.setConnectionTimeout(8000);
        // 连不上时不要卡住代理启动
        hc.setInitializationFailTimeout(-1);

        Storage storage = new Storage(new HikariDataSource(hc), config.str("mysql.table-prefix", "fdchat_"), logger);
        storage.migrate();
        return storage;
    }

    /**
     * 显式注册 JDBC 驱动。
     * Velocity 的插件类加载器是隔离的，java.sql.DriverManager 的 ServiceLoader
     * 自动发现机制在插件 jar 内不生效，必须手动 Class.forName。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean DRIVER_READY =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void registerDriver(Logger logger) {
        if (DRIVER_READY.get()) {
            return;
        }
        String[] candidates = {
            "com.mysql.cj.jdbc.Driver",
            "com.mysql.jdbc.Driver",
            "org.mariadb.jdbc.Driver"
        };
        for (String name : candidates) {
            try {
                Class.forName(name);
                DRIVER_READY.set(true);
                logger.info("JDBC 驱动已注册：{}", name);
                return;
            } catch (ClassNotFoundException ignored) {
                // 尝试下一个
            }
        }
        logger.error("未找到可用的 MySQL JDBC 驱动，数据库功能不可用。");
    }

    static String jdbc(String host, int port, String db) {
        return "jdbc:mysql://" + host + ":" + port + "/" + db
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
            + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    }

    /** 为经济模块单独开一个池（通常指向另一个 database）。 */
    public static DataSource pool(String host, int port, String db, String user, String pass,
                                  int size, String poolName, Logger logger) {
        registerDriver(logger);
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbc(host, port, db));
        hc.setUsername(user);
        hc.setPassword(pass);
        hc.setMaximumPoolSize(size);
        hc.setMinimumIdle(1);
        hc.setPoolName(poolName);
        hc.setConnectionTimeout(8000);
        hc.setInitializationFailTimeout(-1);
        return new HikariDataSource(hc);
    }

    public String table(String suffix) {
        return this.prefix + suffix;
    }

    public String prefix() {
        return this.prefix;
    }

    private void migrate() {
        String nick = table("nickname");
        String hist = table("nickname_history");
        String player = table("player");
        String ignore = table("ignore");

        try (Connection conn = this.dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + nick + "` ("
                + "`uuid` VARCHAR(36) NOT NULL,"
                + "`username` VARCHAR(64) NOT NULL,"
                + "`nickname` VARCHAR(32) NOT NULL,"
                + "`updated_at` BIGINT NOT NULL,"
                + "`change_count` INT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (`uuid`),"
                + "UNIQUE KEY `uk_nickname` (`nickname`),"
                + "KEY `idx_username` (`username`)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + hist + "` ("
                + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
                + "`uuid` VARCHAR(36) NOT NULL,"
                + "`username` VARCHAR(64) NOT NULL,"
                + "`old_nickname` VARCHAR(32) DEFAULT NULL,"
                + "`new_nickname` VARCHAR(32) NOT NULL,"
                + "`cost` DOUBLE NOT NULL DEFAULT 0,"
                + "`changed_at` BIGINT NOT NULL,"
                + "PRIMARY KEY (`id`),"
                + "KEY `idx_uuid` (`uuid`)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + player + "` ("
                + "`uuid` VARCHAR(36) NOT NULL,"
                + "`username` VARCHAR(64) NOT NULL,"
                + "`muted_until` BIGINT NOT NULL DEFAULT 0,"
                + "`mute_reason` VARCHAR(255) DEFAULT NULL,"
                + "`channel` VARCHAR(32) NOT NULL DEFAULT 'global',"
                + "`mention_sound` TINYINT NOT NULL DEFAULT 1,"
                + "`dm_enabled` TINYINT NOT NULL DEFAULT 1,"
                + "`spy` TINYINT NOT NULL DEFAULT 0,"
                + "`nickname_prompted` TINYINT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (`uuid`)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + ignore + "` ("
                + "`uuid` VARCHAR(36) NOT NULL,"
                + "`ignored` VARCHAR(36) NOT NULL,"
                + "PRIMARY KEY (`uuid`, `ignored`)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // 增量补列：老表升级（重复执行无副作用）
            addColumnIfMissing(st, player, "nickname_prompted",
                "ALTER TABLE `" + player + "` ADD COLUMN `nickname_prompted` TINYINT NOT NULL DEFAULT 0");

            this.logger.info("数据表就绪（前缀 {}）。", this.prefix);
        } catch (SQLException ex) {
            this.logger.error("建表失败；聊天功能将不可用，请检查 MySQL 配置。", ex);
        }
    }

    /** 幂等补列：列已存在时 MySQL 会报 1060，忽略即可。 */
    private void addColumnIfMissing(Statement st, String table, String column, String ddl) {
        try (java.sql.ResultSet rs = st.executeQuery(
            "SELECT COUNT(*) FROM information_schema.columns "
            + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' AND column_name = '" + column + "'")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        } catch (SQLException ex) {
            this.logger.debug("检查列 {}.{} 失败，尝试直接添加。", table, column, ex);
        }
        try {
            st.executeUpdate(ddl);
            this.logger.info("已为 {} 添加列 {}。", table, column);
        } catch (SQLException ex) {
            this.logger.debug("添加列 {}.{} 跳过（可能已存在）。", table, column);
        }
    }

    // ---------------- 通用 JDBC 封装 ----------------

    public <T> T query(String sql, Function<ResultSet, T> mapper, Object... args) {
        try (Connection conn = this.dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return mapper.apply(rs);
            }
        } catch (SQLException ex) {
            this.logger.warn("查询失败：{}", sql, ex);
            return null;
        }
    }

    public int update(String sql, Object... args) {
        try (Connection conn = this.dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException ex) {
            this.logger.warn("写入失败：{}", sql, ex);
            return -1;
        }
    }

    /** 事务执行。 */
    public boolean transaction(SqlWork work) {
        try (Connection conn = this.dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                work.run(conn);
                conn.commit();
                return true;
            } catch (Exception ex) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // 回滚失败无需额外处理
                }
                this.logger.warn("事务失败，已回滚。", ex);
                return false;
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // 归还连接时 Hikari 会重置
                }
            }
        } catch (SQLException ex) {
            this.logger.warn("获取连接失败。", ex);
            return false;
        }
    }

    public Optional<String> firstString(String sql, Object... args) {
        String v = query(sql, rs -> {
            try {
                return rs.next() ? rs.getString(1) : null;
            } catch (SQLException ex) {
                return null;
            }
        }, args);
        return Optional.ofNullable(v);
    }

    public List<String> stringList(String sql, Object... args) {
        List<String> out = query(sql, rs -> {
            List<String> list = new ArrayList<>();
            try {
                while (rs.next()) {
                    String v = rs.getString(1);
                    if (v != null) {
                        list.add(v);
                    }
                }
            } catch (SQLException ignored) {
                // 返回已收集部分
            }
            return list;
        }, args);
        return out == null ? List.of() : out;
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    @FunctionalInterface
    public interface SqlWork {
        void run(Connection conn) throws SQLException;
    }

    @Override
    public void close() {
        try {
            this.dataSource.close();
        } catch (Exception ignored) {
            // 关闭期异常忽略
        }
    }
}
