package fd.chat.moderation;

import fd.chat.MyLifeChatPlugin;
import com.velocitypowered.api.proxy.Player;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 违规打击系统：计次 + 警告 + 三振踢出。
 *
 * ★ 关于「拦截手段」的实证结论（2026-10-07，反编译 Velocity 4.2.1 源码）：
 *   Velocity 对【已签名】入站聊天包，代理侧既不能取消也不能改写，两者都会 disconnect：
 *     - cancelled（ChatResult.denied）→ KeyedChatHandler.invalidCancel() → 踢人
 *     - changed  （ChatResult.message）→ KeyedChatHandler.invalidChange() → 踢人
 *
 *   两个 handler 的判定方式不同：
 *     - KeyedChatHandler（< 1.19.3）：用 getIdentifiedKey() 判断，密钥为 null 时安全降级；
 *     - SessionChatHandler（>= 1.19.3）：完全不看密钥，只看包里的 signed 字段，
 *       正版客户端恒为 true，因此必然踢人。
 *
 *   早期版本误以为「把消息改写成空内容会被 Velocity 当成新的未签名系统消息，从而绕开
 *   签名校验」—— 该假设错误，改写同样触发 invalidChange 踢人。现在本类只负责
 *   【计次、生成警告文案、判定是否踢出】，是否能够真正拦截消息由调用方按签名状态决定。
 *
 * 三振出局：
 *   第 1、2 次违规 → 私聊警告
 *   第 3 次违规   → 直接踢出
 *
 * 计数在每个会话内累计，可在配置里设置衰减时间。
 */
public final class StrikeManager {

    private final MyLifeChatPlugin plugin;
    /** uuid -> 违规记录 */
    private final Map<UUID, Record> records = new ConcurrentHashMap<>();

    public StrikeManager(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Record {
        int strikes;
        long lastAt;
    }

    /** 判定结论。 */
    public record Verdict(boolean blocked, int strikes, int limit, boolean kick, String reason) {
        public boolean warned() {
            return this.blocked && !this.kick;
        }
    }

    /**
     * 记一次违规并给出处置结论。
     *
     * @param reason 违规原因（写进警告里，便于玩家理解）
     */
    public Verdict strike(Player player, String reason) {
        UUID id = player.getUniqueId();
        int limit = Math.max(1, this.plugin.config().i("moderation.strikes.limit", 3));
        long decayMs = this.plugin.config().l("moderation.strikes.decay-seconds", 600) * 1000L;

        Record record = this.records.computeIfAbsent(id, k -> new Record());
        long now = System.currentTimeMillis();
        synchronized (record) {
            // 超过衰减时间则清零重新计数
            if (decayMs > 0 && now - record.lastAt > decayMs) {
                record.strikes = 0;
            }
            record.strikes++;
            record.lastAt = now;

            boolean kick = record.strikes >= limit;
            return new Verdict(true, record.strikes, limit, kick, reason);
        }
    }

    /** 当前违规次数（用于提示）。 */
    public int strikes(UUID uuid) {
        Record r = this.records.get(uuid);
        return r == null ? 0 : r.strikes;
    }

    public void clear(UUID uuid) {
        this.records.remove(uuid);
    }

    /** 正常发言可轻微抵消计数（避免偶发误判累积）。 */
    public void forgive(UUID uuid) {
        Record r = this.records.get(uuid);
        if (r == null) {
            return;
        }
        synchronized (r) {
            if (r.strikes > 0) {
                r.strikes--;
            }
        }
    }

    /** 生成警告文案。 */
    public String warnMessage(Verdict verdict) {
        int remain = Math.max(0, verdict.limit() - verdict.strikes());
        String base = this.plugin.config().str("moderation.strikes.warn-message",
            "<red>你的消息被拦截：<yellow>%reason%</yellow>\n"
            + "<gray>这是第 <yellow>%strikes%</yellow> 次违规，"
            + "再有 <yellow>%remain%</yellow> 次将被踢出服务器。");
        return base.replace("%reason%", verdict.reason() == null ? "违规内容" : verdict.reason())
            .replace("%strikes%", String.valueOf(verdict.strikes()))
            .replace("%limit%", String.valueOf(verdict.limit()))
            .replace("%remain%", String.valueOf(remain));
    }
}
