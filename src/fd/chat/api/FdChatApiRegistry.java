package fd.chat.api;

import java.util.Optional;

/**
 * 静态注册表：解决插件加载顺序问题。
 *
 * AI 插件在自己的 onProxyInitialize 里：
 * <pre>
 *   FdChatApiRegistry.whenAvailable(api -&gt; {
 *       api.suggestions().register("fdai", myProvider);
 *   });
 * </pre>
 * 若 fdchat 尚未加载，回调会在它加载时执行一次。
 */
public final class FdChatApiRegistry {

    private static volatile FdChatApi instance;
    private static volatile java.util.function.Consumer<FdChatApi> pending;

    private FdChatApiRegistry() {
    }

    public static Optional<FdChatApi> get() {
        return Optional.ofNullable(instance);
    }

    public static boolean available() {
        return instance != null;
    }

    /** 由 fdchat 调用。 */
    public static synchronized void register(FdChatApi api) {
        instance = api;
        var cb = pending;
        pending = null;
        if (cb != null) {
            cb.accept(api);
        }
    }

    /** 由 fdchat 在关闭时调用。 */
    public static synchronized void unregister() {
        instance = null;
    }

    /** 当 fdchat 可用时执行一次。 */
    public static synchronized void whenAvailable(java.util.function.Consumer<FdChatApi> callback) {
        FdChatApi api = instance;
        if (api != null) {
            callback.accept(api);
        } else {
            pending = callback;
        }
    }
}
