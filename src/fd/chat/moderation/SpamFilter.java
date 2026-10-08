package fd.chat.moderation;

import fd.chat.config.YamlConfig;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * 反刷屏。每个玩家一个滑动窗口，规则全部来自 spam.yml。
 *
 * 检测维度：
 *   1. 最小发送间隔
 *   2. 时间窗内条数
 *   3. 完全重复 / 归一化后重复
 *   4. 大写字母占比
 *   5. 单字符连续重复
 *
 * 取向宽松：默认只拦明显的机器式刷屏。
 */
public final class SpamFilter {

    private final Logger logger;

    private long minIntervalMs;
    private int windowSeconds;
    private int maxInWindow;
    private int duplicateThreshold;
    private int duplicateWindowSeconds;
    private boolean normalize;
    private double capsRatio;
    private int capsMinLength;
    private int repeatCharThreshold;
    private String action;
    private long muteSeconds;
    private String blockMessage;
    private int warnThreshold;
    private String warnMessage;

    private final Map<UUID, Tracker> trackers = new ConcurrentHashMap<>();

    private static final class Tracker {
        long lastMessageAt;
        final Deque<Long> timestamps = new ArrayDeque<>();
        final Deque<Entry> history = new ArrayDeque<>();
        int strikes;
    }

    private record Entry(long at, String normalized) {
    }

    public SpamFilter(YamlConfig config, Logger logger) {
        this.logger = logger;
        reload(config);
    }

    public void reload(YamlConfig config) {
        this.minIntervalMs = config.l("min-interval-ms", 700);
        this.windowSeconds = config.i("window-seconds", 5);
        this.maxInWindow = config.i("max-messages-in-window", 5);
        this.duplicateThreshold = config.i("duplicate-threshold", 3);
        this.duplicateWindowSeconds = config.i("duplicate-window-seconds", 15);
        this.normalize = config.bool("normalize-before-compare", true);
        this.capsRatio = config.d("caps-ratio-threshold", 0.8);
        this.capsMinLength = config.i("caps-min-length", 8);
        this.repeatCharThreshold = config.i("repeat-char-threshold", 8);
        this.action = config.str("action", "block").toLowerCase(Locale.ROOT);
        this.muteSeconds = config.l("mute-seconds", 60);
        this.blockMessage = config.str("block-message", "<red>发言太快了，请稍后再试。");
        this.warnThreshold = config.i("warn-threshold", 3);
        this.warnMessage = config.str("warn-message", "<red>请勿刷屏。");
        this.logger.info("反刷屏已加载：间隔 {}ms，窗口 {}/{}s，重复 {}/{}s，动作 {}。",
            this.minIntervalMs, this.maxInWindow, this.windowSeconds,
            this.duplicateThreshold, this.duplicateWindowSeconds, this.action);
    }

    public String blockMessage() {
        return this.blockMessage;
    }

    public String warnMessage() {
        return this.warnMessage;
    }

    public int warnThreshold() {
        return this.warnThreshold;
    }

    public boolean shouldMute() {
        return "mute".equals(this.action);
    }

    public long muteSeconds() {
        return this.muteSeconds;
    }

    public void forget(UUID uuid) {
        this.trackers.remove(uuid);
    }

    /** 判定结果。 */
    public record Verdict(boolean blocked, String reason, boolean shouldMute) {
        static Verdict pass() {
            return new Verdict(false, null, false);
        }
    }

    private static String normalizeText(String s) {
        return s.toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{Punct}\\s\\u3000-\\u303f\\uff00-\\uffef]", "");
    }

    public Verdict check(UUID uuid, String content) {
        long now = System.currentTimeMillis();
        Tracker t = this.trackers.computeIfAbsent(uuid, k -> new Tracker());
        String normalized = this.normalize ? normalizeText(content) : content.toLowerCase(Locale.ROOT);

        synchronized (t) {
            // 1) 最小间隔
            if (this.minIntervalMs > 0 && t.lastMessageAt > 0
                && now - t.lastMessageAt < this.minIntervalMs) {
                return strike(t, "发送过快");
            }

            // 2) 窗口条数
            if (this.windowSeconds > 0) {
                long cutoff = now - this.windowSeconds * 1000L;
                while (!t.timestamps.isEmpty() && t.timestamps.peekFirst() < cutoff) {
                    t.timestamps.pollFirst();
                }
                if (t.timestamps.size() >= this.maxInWindow) {
                    return strike(t, "窗口内消息过多");
                }
            }

            // 3) 重复消息
            if (this.duplicateThreshold > 0) {
                long cutoff = now - this.duplicateWindowSeconds * 1000L;
                while (!t.history.isEmpty() && t.history.peekFirst().at() < cutoff) {
                    t.history.pollFirst();
                }
                long same = t.history.stream().filter(e -> e.normalized().equals(normalized)).count();
                if (same >= this.duplicateThreshold - 1) { // 算上本条
                    return strike(t, "重复消息");
                }
            }

            // 4) 大写占比
            if (this.capsRatio > 0 && content.length() >= this.capsMinLength) {
                int letters = 0;
                int upper = 0;
                for (int i = 0; i < content.length(); i++) {
                    char c = content.charAt(i);
                    if (c >= 'a' && c <= 'z') {
                        letters++;
                    } else if (c >= 'A' && c <= 'Z') {
                        letters++;
                        upper++;
                    }
                }
                if (letters >= this.capsMinLength && (double) upper / letters >= this.capsRatio) {
                    return strike(t, "大写过多");
                }
            }

            // 5) 单字符连续重复
            if (this.repeatCharThreshold > 0 && hasRepeatedRun(content, this.repeatCharThreshold)) {
                return strike(t, "字符重复");
            }

            // 通过：记录
            t.lastMessageAt = now;
            t.timestamps.addLast(now);
            t.history.addLast(new Entry(now, normalized));
            if (t.strikes > 0) {
                t.strikes--; // 正常发言逐步消除计数
            }
            return Verdict.pass();
        }
    }

    private static boolean hasRepeatedRun(String s, int threshold) {
        if (s == null || s.length() < threshold) {
            return false;
        }
        int run = 1;
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == s.charAt(i - 1) && !Character.isWhitespace(s.charAt(i))) {
                if (++run >= threshold) {
                    return true;
                }
            } else {
                run = 1;
            }
        }
        return false;
    }

    private Verdict strike(Tracker t, String reason) {
        t.strikes++;
        this.logger.debug("反刷屏命中：{}（累计 {} 次）", reason, t.strikes);
        return new Verdict(true, reason, shouldMute() && t.strikes >= this.warnThreshold);
    }

    /** 当前累计的违规次数（用于决定提示文案）。 */
    public int strikes(UUID uuid) {
        Tracker t = this.trackers.get(uuid);
        return t == null ? 0 : t.strikes;
    }
}
