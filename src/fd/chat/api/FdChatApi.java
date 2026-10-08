package fd.chat.api;

import com.velocitypowered.api.command.CommandSource;
import java.util.List;
import java.util.Optional;

/**
 * fdchat 对外契约。AI 插件（或任何插件）通过 {@link FdChatApiRegistry} 获取。
 *
 * 约定：
 *  - 所有方法都可能在聊天链路上被调用，必须快速返回。
 *  - AI/网络调用请自行异步化，绝不要阻塞这里。
 *  - 接口不暴露 fdchat 内部类型，AI 插件可以独立编译、独立发布。
 */
public interface FdChatApi {

    /** 昵称解析（AI 插件做「用中文名指代玩家」时用）。 */
    Identity identity();

    /** 联想服务：AI 注册进来，fdchat 在指令失败或 ?? 前缀时征集建议。 */
    Suggestions suggestions();

    /** 注册聊天拦截器；order 越小越先执行。 */
    void registerInterceptor(ChatInterceptor interceptor, int order);

    /** 注册指令重写器；order 越小越先执行。 */
    void registerRewriter(CommandRewriter rewriter, int order);

    // ------------------------------------------------------------------

    interface Identity {
        /** 任意输入 -> 确定的账号名。歧义或找不到返回 empty。 */
        Optional<String> resolveUsername(String input);

        /** 歧义候选；无歧义返回空列表。 */
        List<String> ambiguity(String input);

        Optional<String> nicknameOf(String username);

        String displayName(String username);

        List<String> onlineDisplayNames();
    }

    interface Suggestions {
        void register(String name, Provider provider);

        void unregister(String name);

        /** 主动征集建议，按注册顺序取第一个非空结果。 */
        List<String> suggest(CommandSource source, String input, int limit);
    }

    interface Provider {
        List<String> suggest(CommandSource source, String input, int limit);
    }

    /**
     * 聊天拦截器。在消息渲染投递之前调用。
     * 返回非 null 的 {@link Verdict} 表示要干预。
     */
    interface ChatInterceptor {
        Verdict intercept(Context context);
    }

    interface Context {
        CommandSource sender();

        String senderUsername();

        /** 已剥离频道前缀的正文。 */
        String content();

        String channelKey();

        String serverName();
    }

    /** 拦截结论。 */
    record Verdict(boolean block, String blockReason, String replacement) {
        public static Verdict pass() {
            return new Verdict(false, null, null);
        }

        public static Verdict block(String reason) {
            return new Verdict(true, reason, null);
        }

        public static Verdict replace(String text) {
            return new Verdict(false, null, text);
        }
    }

    /** 指令重写器：返回 empty 表示不干预。 */
    interface CommandRewriter {
        Optional<String> rewrite(CommandSource source, String command);
    }
}
