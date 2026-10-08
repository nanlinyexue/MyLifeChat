package fd.chat.user;

import fd.chat.storage.Storage;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中文名提示状态：做到「只提示一次」。
 *
 * 两个层次：
 *   - 内存集合：本局已提示过的人，避免重复提示
 *   - 数据库标记（fdchat_player.nickname_prompted）：跨登录持久化，
 *     真正做到「一生只提示一次」，不再骚扰老玩家
 *
 * 已设置中文名的人不会再被提示（由调用方先判断 hasNickname）。
 */
public final class NicknamePrompts {

    private final Storage storage;
    private final Set<UUID> sessionMarked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> persistedMarked = ConcurrentHashMap.newKeySet();

    public NicknamePrompts(Storage storage) {
        this.storage = storage;
        loadPersisted();
    }

    private void loadPersisted() {
        String sql = "SELECT uuid FROM `" + this.storage.table("player") + "` WHERE nickname_prompted = 1";
        for (String s : this.storage.stringList(sql)) {
            try {
                this.persistedMarked.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // 脏数据跳过
            }
        }
    }

    /** 是否需要提示（已提示过则返回 false）。 */
    public boolean shouldPrompt(UUID uuid) {
        return !this.sessionMarked.contains(uuid) && !this.persistedMarked.contains(uuid);
    }

    public boolean contains(UUID uuid) {
        return this.sessionMarked.contains(uuid) || this.persistedMarked.contains(uuid);
    }

    /**
     * 记录「已提示」，同时写库以便跨登录生效。
     *
     * ★ 分两步而不是一条 INSERT：
     *   fdchat_player.username 是 NOT NULL 且没有默认值，而这里只关心 nickname_prompted。
     *   早先直接 `INSERT (uuid, nickname_prompted)` 会撞上
     *   "Field 'username' doesn't have a default value"，每个玩家每会话首次发言都抛一次
     *   SQLException —— 这条 SQL 位于聊天主路径上，会造成可感知的发言卡顿。
     *   改为「先确保行存在（补齐 username），再更新目标列」，与 UserManager 的写法保持一致。
     *
     * @param username 玩家当前账号名，用于满足 username NOT NULL 约束
     */
    public void mark(UUID uuid, String username) {
        this.sessionMarked.add(uuid);
        if (!this.persistedMarked.add(uuid)) {
            return;
        }
        String table = this.storage.table("player");
        this.storage.update(
            "INSERT IGNORE INTO `" + table + "` (uuid, username) VALUES (?, ?)",
            uuid.toString(), username);
        this.storage.update(
            "UPDATE `" + table + "` SET nickname_prompted = 1 WHERE uuid = ?",
            uuid.toString());
    }

    /** 玩家登出时清理内存标记（持久化标记保留）。 */
    public void forget(UUID uuid) {
        this.sessionMarked.remove(uuid);
    }

    /** 设置好中文名后调用，避免以后再提示。 */
    public void markDone(UUID uuid, String username) {
        mark(uuid, username);
    }
}
