package fd.chat.channel;

/**
 * 频道定义。全部由 config.yml 的 channels.* 生成，不写死。
 *
 * @param key            频道标识（global / server / guild / local / private）
 * @param enabled        是否启用
 * @param prefix         触发前缀，空串表示「无前缀（默认频道）」
 * @param tag            MiniMessage 标签，如 &lt;yellow&gt;[全服]&lt;/yellow&gt;
 * @param cooldownMs     说话冷却（毫秒），0 表示不限
 * @param crossServer    是否跨子服
 * @param radius         本地频道半径（格），&lt;=0 表示不限
 * @param toll           单条收费金额，0 表示免费
 * @param free           是否免费（全局频道专用语义）
 * @param disabledNote   未启用时的提示
 * @param paidTag        付费喊话时使用的标签（为空则用 tag）
 * @param displayName    频道名（对应旧 chat_FD 的 channelname，如 [世界]）
 */
public record Channel(
    String key,
    boolean enabled,
    String prefix,
    String tag,
    long cooldownMs,
    boolean crossServer,
    int radius,
    double toll,
    boolean free,
    String disabledNote,
    String paidTag,
    String displayName
) {

    /** 实际用于显示的标签：付费喊话用更醒目的 paidTag。 */
    public String displayTag(boolean paid) {
        if (paid && this.paidTag != null && !this.paidTag.isBlank()) {
            return this.paidTag;
        }
        return this.tag;
    }

    public static final String GLOBAL = "global";
    public static final String SERVER = "server";
    public static final String GUILD = "guild";
    public static final String LOCAL = "local";
    public static final String PRIVATE = "private";

    /** 该频道是否收费。 */
    public boolean tolled() {
        return this.toll > 0 && !this.free;
    }

    /** 是否只发给同一子服的玩家。 */
    public boolean serverScoped() {
        return !this.crossServer || this.key.equals(SERVER) || this.key.equals(LOCAL);
    }
}
