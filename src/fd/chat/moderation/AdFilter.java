package fd.chat.moderation;

import fd.chat.config.YamlConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * 反广告：规则全部来自独立的 advertisements.yml，可单独热重载。
 *
 * 设计取向「先宽松」：
 *   - 每条规则都有独立开关
 *   - 有白名单，命中白名单的内容直接放行
 *   - 默认关闭了最容易误伤的「纯长数字」规则
 */
public final class AdFilter {

    public enum Action { BLOCK, REPLACE, WARN }

    private final Logger logger;
    private final List<Rule> rules = new ArrayList<>();
    private final List<String> whitelist = new ArrayList<>();
    private final List<String> nicknameBlacklist = new ArrayList<>();
    private Action action = Action.BLOCK;
    private String blockMessage = "<red>消息包含疑似广告内容，已被拦截。";
    private String notifyFormat = "<red>[反广告] <yellow>%player% <gray>触发 <white>%rule%";
    private String replacement = "***";
    private boolean enabled = true;

    private record Rule(String name, boolean enabled, Pattern pattern) {
    }

    public AdFilter(YamlConfig config, Logger logger) {
        this.logger = logger;
        reload(config);
    }

    public void reload(YamlConfig config) {
        this.rules.clear();
        this.whitelist.clear();
        this.nicknameBlacklist.clear();

        this.enabled = true; // 总开关在主配置，由调用方判断
        this.action = switch (config.str("action", "block").toLowerCase(Locale.ROOT)) {
            case "replace" -> Action.REPLACE;
            case "warn" -> Action.WARN;
            default -> Action.BLOCK;
        };
        this.blockMessage = config.str("block-message", this.blockMessage);
        this.notifyFormat = config.str("notify-format", this.notifyFormat);
        this.replacement = config.str("replacement", "***");

        int total = 0;
        int on = 0;
        for (var rule : config.mapList("rules")) {
            String name = String.valueOf(rule.getOrDefault("name", "未命名"));
            boolean ruleEnabled = asBool(rule.get("enabled"), true);
            String pattern = String.valueOf(rule.getOrDefault("pattern", ""));
            total++;
            if (pattern.isBlank()) {
                this.logger.warn("广告规则「{}」缺少 pattern，已跳过。", name);
                continue;
            }
            try {
                this.rules.add(new Rule(name, ruleEnabled, Pattern.compile(pattern)));
                if (ruleEnabled) {
                    on++;
                }
            } catch (Exception ex) {
                this.logger.warn("广告规则「{}」正则非法，已跳过：{}", name, pattern);
            }
        }

        this.whitelist.addAll(lower(config.strList("whitelist")));
        this.nicknameBlacklist.addAll(config.strList("nickname-blacklist"));

        this.logger.info("反广告规则已加载：{}/{} 条启用，白名单 {} 项。", on, total, this.whitelist.size());
    }

    private static boolean asBool(Object v, boolean def) {
        if (v instanceof Boolean b) {
            return b;
        }
        return v instanceof String s ? Boolean.parseBoolean(s.trim()) : def;
    }

    private static List<String> lower(List<String> in) {
        return in.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    public boolean enabled() {
        return this.enabled;
    }

    public Action action() {
        return this.action;
    }

    public String blockMessage() {
        return this.blockMessage;
    }

    public String notifyFormat() {
        return this.notifyFormat;
    }

    public String replacement() {
        return this.replacement;
    }

    public List<String> nicknameBlacklist() {
        return List.copyOf(this.nicknameBlacklist);
    }

    /** 检测结果。 */
    public record Verdict(boolean hit, String ruleName, String cleaned) {
        static Verdict pass(String content) {
            return new Verdict(false, null, content);
        }
    }

    /**
     * 检测一条消息。
     * @return 命中则 hit=true；REPLACE 模式下 cleaned 是替换后的文本
     */
    public Verdict check(String content) {
        if (content == null || content.isEmpty() || this.rules.isEmpty()) {
            return Verdict.pass(content);
        }

        String lower = content.toLowerCase(Locale.ROOT);
        for (String white : this.whitelist) {
            if (!white.isEmpty() && lower.contains(white)) {
                return Verdict.pass(content); // 命中白名单，整条放行
            }
        }

        String current = content;
        String hitRule = null;
        for (Rule rule : this.rules) {
            if (!rule.enabled()) {
                continue;
            }
            if (rule.pattern().matcher(current).find()) {
                hitRule = rule.name();
                if (this.action == Action.REPLACE) {
                    current = rule.pattern().matcher(current).replaceAll(java.util.regex.Matcher.quoteReplacement(this.replacement));
                } else {
                    break;
                }
            }
        }

        if (hitRule == null) {
            return Verdict.pass(content);
        }
        return new Verdict(true, hitRule, this.action == Action.REPLACE ? current : content);
    }

    /** 昵称是否命中广告词（用于昵称注册时的附加校验）。 */
    public boolean nicknameLooksLikeAd(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return false;
        }
        for (Rule rule : this.rules) {
            if (rule.enabled() && rule.pattern().matcher(nickname).find()) {
                return true;
            }
        }
        return false;
    }
}
