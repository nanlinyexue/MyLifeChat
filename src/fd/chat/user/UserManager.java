package fd.chat.user;

import fd.chat.storage.Storage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * 玩家状态：禁言 / 默认频道 / 提及音效 / 私聊开关 / 监视 / 忽略列表。
 *
 * 在线期间走内存，变更时写回数据库（写少读多，聊天链路上不能打库）。
 */
public final class UserManager {

    private final Storage storage;
    private final Logger logger;

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignores = new ConcurrentHashMap<>();

    /** 内存态。 */
    public static final class State {
        public volatile long mutedUntil;
        public volatile String muteReason;
        public volatile String channel = "auto";
        public volatile boolean mentionSound = true;
        public volatile boolean dmEnabled = true;
        public volatile boolean spy;
    }

    public UserManager(Storage storage, Logger logger) {
        this.storage = storage;
        this.logger = logger;
    }

    /** 玩家进服时从库里载入。 */
    public State load(UUID uuid, String username) {
        State state = new State();
        String sql = "SELECT muted_until, mute_reason, channel, mention_sound, dm_enabled, spy FROM `"
            + this.storage.table("player") + "` WHERE uuid = ?";
        Boolean found = this.storage.query(sql, rs -> {
            try {
                if (!rs.next()) {
                    return false;
                }
                state.mutedUntil = rs.getLong("muted_until");
                state.muteReason = rs.getString("mute_reason");
                state.channel = rs.getString("channel");
                state.mentionSound = rs.getInt("mention_sound") != 0;
                state.dmEnabled = rs.getInt("dm_enabled") != 0;
                state.spy = rs.getInt("spy") != 0;
                return true;
            } catch (SQLException ex) {
                return false;
            }
        }, uuid.toString());

        if (found == null || !found) {
            ensureRow(uuid, username);
        }

        this.states.put(uuid, state);
        loadIgnores(uuid);
        return state;
    }

    private void loadIgnores(UUID uuid) {
        Set<UUID> set = new HashSet<>(this.storage.stringList(
            "SELECT ignored FROM `" + this.storage.table("ignore") + "` WHERE uuid = ?", uuid.toString())
            .stream()
            .map(s -> {
                try {
                    return UUID.fromString(s);
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            })
            .filter(java.util.Objects::nonNull)
            .toList());
        this.ignores.put(uuid, set);
    }

    public void unload(UUID uuid) {
        this.states.remove(uuid);
        this.ignores.remove(uuid);
    }

    public State state(UUID uuid) {
        return this.states.computeIfAbsent(uuid, k -> new State());
    }

    public Optional<State> peek(UUID uuid) {
        return Optional.ofNullable(this.states.get(uuid));
    }

    private void ensureRow(UUID uuid, String username) {
        String sql = "INSERT IGNORE INTO `" + this.storage.table("player") + "` (uuid, username) VALUES (?, ?)";
        this.storage.update(sql, uuid.toString(), username);
    }

    private void persist(UUID uuid, String username, String column, Object value) {
        String sql = "INSERT INTO `" + this.storage.table("player") + "` (uuid, username, " + column + ") "
            + "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE " + column + " = VALUES(" + column + "), "
            + "username = VALUES(username)";
        this.storage.update(sql, uuid.toString(), username, value);
    }

    // ---------------- 禁言 ----------------

    public boolean isMuted(UUID uuid) {
        State s = this.states.get(uuid);
        return s != null && s.mutedUntil > System.currentTimeMillis();
    }

    public long mutedRemainingSeconds(UUID uuid) {
        State s = this.states.get(uuid);
        if (s == null) {
            return 0;
        }
        long remain = s.mutedUntil - System.currentTimeMillis();
        return remain <= 0 ? 0 : (remain + 999) / 1000;
    }

    public String muteReason(UUID uuid) {
        State s = this.states.get(uuid);
        return s == null ? null : s.muteReason;
    }

    /** @param seconds 0 或负数表示永久 */
    public void mute(UUID uuid, String username, long seconds, String reason) {
        State s = state(uuid);
        s.mutedUntil = seconds <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + seconds * 1000L;
        s.muteReason = reason;
        persist(uuid, username, "muted_until", s.mutedUntil);
        persist(uuid, username, "mute_reason", reason);
    }

    public void unmute(UUID uuid, String username) {
        State s = state(uuid);
        s.mutedUntil = 0;
        s.muteReason = null;
        persist(uuid, username, "muted_until", 0L);
        persist(uuid, username, "mute_reason", null);
    }

    // ---------------- 频道 ----------------

    public String channel(UUID uuid) {
        State s = this.states.get(uuid);
        return s == null ? "global" : s.channel;
    }

    public void channel(UUID uuid, String username, String channel) {
        State s = state(uuid);
        s.channel = channel;
        persist(uuid, username, "channel", channel);
    }

    // ---------------- 开关 ----------------

    public boolean mentionSound(UUID uuid) {
        State s = this.states.get(uuid);
        return s == null || s.mentionSound;
    }

    public void mentionSound(UUID uuid, String username, boolean value) {
        State s = state(uuid);
        s.mentionSound = value;
        persist(uuid, username, "mention_sound", value ? 1 : 0);
    }

    public boolean dmEnabled(UUID uuid) {
        State s = this.states.get(uuid);
        return s == null || s.dmEnabled;
    }

    public void dmEnabled(UUID uuid, String username, boolean value) {
        State s = state(uuid);
        s.dmEnabled = value;
        persist(uuid, username, "dm_enabled", value ? 1 : 0);
    }

    public boolean spying(UUID uuid) {
        State s = this.states.get(uuid);
        return s != null && s.spy;
    }

    public boolean toggleSpy(UUID uuid, String username) {
        State s = state(uuid);
        s.spy = !s.spy;
        persist(uuid, username, "spy", s.spy ? 1 : 0);
        return s.spy;
    }

    // ---------------- 忽略 ----------------

    public boolean ignoring(UUID uuid, UUID target) {
        Set<UUID> set = this.ignores.get(uuid);
        return set != null && set.contains(target);
    }

    public void ignore(UUID uuid, UUID target) {
        this.ignores.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(target);
        String sql = "INSERT IGNORE INTO `" + this.storage.table("ignore") + "` (uuid, ignored) VALUES (?, ?)";
        this.storage.update(sql, uuid.toString(), target.toString());
    }

    public void unignore(UUID uuid, UUID target) {
        Set<UUID> set = this.ignores.get(uuid);
        if (set != null) {
            set.remove(target);
        }
        String sql = "DELETE FROM `" + this.storage.table("ignore") + "` WHERE uuid = ? AND ignored = ?";
        this.storage.update(sql, uuid.toString(), target.toString());
    }

    public Set<UUID> ignored(UUID uuid) {
        return this.ignores.getOrDefault(uuid, Set.of());
    }
}
