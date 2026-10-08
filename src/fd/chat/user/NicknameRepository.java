package fd.chat.user;

import fd.chat.storage.Storage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * 昵称仓库：昵称的唯一性、持久化与内存索引。
 *
 * 唯一性的保证方式（两层）：
 *   1. 数据库 uk_nickname 唯一索引 —— 最终防线，并发下也不会重复
 *   2. 内存索引 + 原子占位  —— 常规路径，避免每次都打数据库
 *
 * 昵称是「全网唯一」的：所有子服共用这张表。
 */
public final class NicknameRepository {

    private final Storage storage;
    private final Logger logger;

    /** 小写昵称 -> 记录 */
    private final Map<String, Entry> byNickname = new ConcurrentHashMap<>();
    /** UUID -> 记录 */
    private final Map<UUID, Entry> byUuid = new ConcurrentHashMap<>();

    public record Entry(UUID uuid, String username, String nickname, long updatedAt, int changeCount) {
    }

    public NicknameRepository(Storage storage, Logger logger) {
        this.storage = storage;
        this.logger = logger;
        loadAll();
    }

    public void loadAll() {
        this.byNickname.clear();
        this.byUuid.clear();
        String sql = "SELECT uuid, username, nickname, updated_at, change_count FROM `"
            + this.storage.table("nickname") + "`";
        Integer count = this.storage.query(sql, rs -> {
            int n = 0;
            try {
                while (rs.next()) {
                    Entry entry = new Entry(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("username"),
                        rs.getString("nickname"),
                        rs.getLong("updated_at"),
                        rs.getInt("change_count")
                    );
                    cache(entry);
                    n++;
                }
            } catch (SQLException | IllegalArgumentException ex) {
                this.logger.warn("加载昵称记录时跳过一行。", ex);
            }
            return n;
        });
        this.logger.info("已加载 {} 条昵称记录。", count == null ? 0 : count);
    }

    private void cache(Entry entry) {
        this.byUuid.put(entry.uuid(), entry);
        if (entry.nickname() != null && !entry.nickname().isBlank()) {
            this.byNickname.put(key(entry.nickname()), entry);
        }
    }

    private static String key(String nickname) {
        return nickname.toLowerCase(Locale.ROOT);
    }

    // ---------------- 查询 ----------------

    public Optional<Entry> byUuid(UUID uuid) {
        return Optional.ofNullable(this.byUuid.get(uuid));
    }

    public Optional<Entry> byNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.byNickname.get(key(nickname)));
    }

    public Optional<Entry> byUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        for (Entry e : this.byUuid.values()) {
            if (e.username().equalsIgnoreCase(username)) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** 昵称是否被「别人」占用。 */
    public boolean isTakenByOther(String nickname, UUID self) {
        Entry e = this.byNickname.get(key(nickname));
        return e != null && !e.uuid().equals(self);
    }

    public Collection<Entry> all() {
        return this.byUuid.values();
    }

    public int size() {
        return this.byUuid.size();
    }

    // ---------------- 写入 ----------------

    /**
     * 设置/更换昵称。
     *
     * @return 结果；{@link Result#OK} 表示成功
     */
    public Result set(UUID uuid, String username, String nickname, double cost, boolean chargeChange) {
        String target = nickname == null ? "" : nickname.trim();
        if (target.isEmpty()) {
            return Result.INVALID;
        }

        Entry existing = this.byUuid.get(uuid);
        String oldNick = existing == null ? null : existing.nickname();

        // 同名（含大小写差异）视为无变化
        if (oldNick != null && oldNick.equalsIgnoreCase(target)) {
            return Result.UNCHANGED;
        }

        if (isTakenByOther(target, uuid)) {
            return Result.TAKEN;
        }

        long now = System.currentTimeMillis();
        int newCount = (existing == null ? 0 : existing.changeCount()) + (chargeChange ? 1 : 0);

        // 先用原子占位防止并发抢注（DB 唯一索引仍是最终防线）
        Entry placeholder = new Entry(uuid, username, target, now, newCount);
        Entry prevByNick = this.byNickname.putIfAbsent(key(target), placeholder);
        if (prevByNick != null && !prevByNick.uuid().equals(uuid)) {
            return Result.TAKEN;
        }

        boolean ok = this.storage.transaction(conn -> {
            upsert(conn, uuid, username, target, now, newCount);
            insertHistory(conn, uuid, username, oldNick, target, cost, now);
        });

        if (!ok) {
            // 回滚内存状态
            this.byNickname.remove(key(target), placeholder);
            if (oldNick != null) {
                this.byNickname.put(key(oldNick), existing);
            }
            return Result.STORAGE_ERROR;
        }

        if (oldNick != null && !oldNick.equalsIgnoreCase(target)) {
            this.byNickname.remove(key(oldNick));
        }
        cache(placeholder);
        return Result.OK;
    }

    private void upsert(Connection conn, UUID uuid, String username, String nickname,
                        long updatedAt, int changeCount) throws SQLException {
        String sql = "INSERT INTO `" + this.storage.table("nickname") + "` "
            + "(uuid, username, nickname, updated_at, change_count) VALUES (?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE username = VALUES(username), nickname = VALUES(nickname), "
            + "updated_at = VALUES(updated_at), change_count = VALUES(change_count)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setString(3, nickname);
            ps.setLong(4, updatedAt);
            ps.setInt(5, changeCount);
            ps.executeUpdate();
        }
    }

    private void insertHistory(Connection conn, UUID uuid, String username,
                               String oldNick, String newNick, double cost, long at) throws SQLException {
        String sql = "INSERT INTO `" + this.storage.table("nickname_history") + "` "
            + "(uuid, username, old_nickname, new_nickname, cost, changed_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setString(3, oldNick);
            ps.setString(4, newNick);
            ps.setDouble(5, cost);
            ps.setLong(6, at);
            ps.executeUpdate();
        }
    }

    /** 离线模式 UUID 生成（与 Velocity 的 offline UUID 规则一致）。 */
    public static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sanitize(String table) {
        return table.replaceAll("[^A-Za-z0-9_]", "");
    }


    public enum Result {
        OK, TAKEN, INVALID, UNCHANGED, STORAGE_ERROR
    }
}
