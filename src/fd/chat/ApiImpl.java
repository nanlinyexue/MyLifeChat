package fd.chat;

import com.velocitypowered.api.command.CommandSource;
import fd.chat.api.FdChatApi;
import fd.chat.user.IdentityResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link FdChatApi} 的实现：把插件内部能力以稳定契约暴露给 AI 插件等外部扩展。
 */
final class ApiImpl implements FdChatApi {

    private final MyLifeChatPlugin plugin;
    private final Identity identityView;

    ApiImpl(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
        this.identityView = new Identity();
    }

    @Override
    public Identity identity() {
        return this.identityView;
    }

    @Override
    public Suggestions suggestions() {
        return this.plugin.suggestionRegistry();
    }

    @Override
    public void registerInterceptor(ChatInterceptor interceptor, int order) {
        this.plugin.interceptors().add(new InterceptorEntry(interceptor, order));
    }

    @Override
    public void registerRewriter(CommandRewriter rewriter, int order) {
        fd.chat.command.CommandRegistrar.REWRITERS.add(
            new fd.chat.command.CommandRegistrar.ApiImplRewriter(rewriter, order));
    }

    /** 拦截器条目。 */
    record InterceptorEntry(ChatInterceptor interceptor, int order) {
    }

    /** 重写器条目。 */
    record RewriterEntry(CommandRewriter rewriter, int order) {
    }

    private final class Identity implements FdChatApi.Identity {

        private IdentityResolver resolver() {
            return ApiImpl.this.plugin.identity();
        }

        @Override
        public Optional<String> resolveUsername(String input) {
            IdentityResolver.Resolution r = resolver().resolve(input);
            if (r instanceof IdentityResolver.Resolution.Found f) {
                return Optional.of(f.username());
            }
            return Optional.empty();
        }

        @Override
        public List<String> ambiguity(String input) {
            IdentityResolver.Resolution r = resolver().resolve(input);
            if (r instanceof IdentityResolver.Resolution.Ambiguous a) {
                return a.candidates();
            }
            return List.of();
        }

        @Override
        public Optional<String> nicknameOf(String username) {
            return resolver().resolve(username) instanceof IdentityResolver.Resolution.Found f
                ? ApiImpl.this.plugin.nicknames().byUuid(f.uuid())
                    .map(fd.chat.user.NicknameRepository.Entry::nickname)
                : Optional.empty();
        }

        @Override
        public String displayName(String username) {
            IdentityResolver.Resolution r = resolver().resolve(username);
            if (r instanceof IdentityResolver.Resolution.Found f) {
                return resolver().displayName(f.uuid(), f.username());
            }
            return username;
        }

        @Override
        public List<String> onlineDisplayNames() {
            return resolver().onlineNicknames();
        }
    }

    /** 联想注册表：AI 插件把自己的 provider 注册进来。 */
    static final class SuggestionRegistry implements FdChatApi.Suggestions {

        private final List<Named> providers = new CopyOnWriteArrayList<>();

        private record Named(String name, FdChatApi.Provider provider) {
        }

        @Override
        public void register(String name, FdChatApi.Provider provider) {
            this.providers.removeIf(p -> p.name().equals(name));
            this.providers.add(new Named(name, provider));
        }

        @Override
        public void unregister(String name) {
            this.providers.removeIf(p -> p.name().equals(name));
        }

        @Override
        public List<String> suggest(CommandSource source, String input, int limit) {
            List<String> out = new ArrayList<>();
            for (Named named : this.providers) {
                try {
                    List<String> result = named.provider().suggest(source, input, limit);
                    if (result != null && !result.isEmpty()) {
                        out.addAll(result);
                        if (out.size() >= limit) {
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    // 单个 provider 出错不影响其它来源
                }
            }
            return out.size() > limit ? out.subList(0, limit) : out;
        }

        boolean isEmpty() {
            return this.providers.isEmpty();
        }
    }
}
