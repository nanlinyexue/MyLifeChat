package fd.chat;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import fd.chat.api.FdChatApi;
import fd.chat.api.FdChatApiRegistry;
import fd.chat.bridge.BackendBridge;
import fd.chat.channel.ChannelManager;
import fd.chat.command.CommandRegistrar;
import fd.chat.command.LocalSuggester;
import fd.chat.config.YamlConfig;
import fd.chat.integration.Economy;
import fd.chat.integration.LuckPermsBridge;
import fd.chat.moderation.AdFilter;
import fd.chat.moderation.SpamFilter;
import fd.chat.render.MentionCompleter;
import fd.chat.render.Renderer;
import fd.chat.storage.Storage;
import fd.chat.user.IdentityResolver;
import fd.chat.user.NicknamePrompts;
import fd.chat.user.NicknameRepository;
import fd.chat.user.UserManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * FloatDream 聊天系统。
 *
 * 完全自研：不依赖 CarbonChat / 任何后端聊天插件 / Redis / SignedVelocity。
 * 运行期依赖只有 MySQL 驱动与 HikariCP。
 */
@Plugin(
    id = "fdchat",
    name = "FloatDreamChat",
    version = "1.0.0",
    description = "FloatDream 自研聊天系统：频道 / 中文名 / 提及 / 反广告 / 反刷屏",
    authors = {"FloatDream"}
)
public final class MyLifeChatPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private YamlConfig config;
    private YamlConfig adsConfig;
    private YamlConfig spamConfig;

    private Storage storage;
    private NicknameRepository nicknames;
    private NicknamePrompts nicknamePrompts;
    private UserManager users;
    private IdentityResolver identity;
    private Economy economy;
    private LuckPermsBridge luckPerms;
    private ChannelManager channels;
    private Renderer renderer;
    private MentionCompleter mentionCompleter;
    private BackendBridge backendBridge;
    /** 已签名会话的玩家（仅用于日志/统计，避免刷屏） */
    private final java.util.Set<java.util.UUID> signedWarned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private fd.chat.moderation.StrikeManager strikes;
    private AdFilter adFilter;
    private SpamFilter spamFilter;
    private ChatDispatcher dispatcher;
    private LocalSuggester localSuggester;

    private final List<ApiImpl.InterceptorEntry> interceptors = new ArrayList<>();
    private final ApiImpl.SuggestionRegistry suggestions = new ApiImpl.SuggestionRegistry();
    private ApiImpl api;

    @Inject
    public MyLifeChatPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            this.config = YamlConfig.load(this.dataDirectory, "config.yml", this.logger);
            this.adsConfig = YamlConfig.load(this.dataDirectory, "advertisements.yml", this.logger);
            this.spamConfig = YamlConfig.load(this.dataDirectory, "spam.yml", this.logger);

            this.storage = Storage.open(this.config, this.logger);
            this.nicknames = new NicknameRepository(this.storage, this.logger);
            this.nicknamePrompts = new NicknamePrompts(this.storage);
            this.users = new UserManager(this.storage, this.logger);
            this.economy = new Economy(this.config, this.logger);
            this.luckPerms = new LuckPermsBridge(this.proxy, this.logger);
            this.channels = new ChannelManager(this.config, this.logger);
            this.renderer = new Renderer(
                this.config.str("mention.prefix", "@"),
                this.config.str("mention.highlight-color", "yellow"),
                this.config.str("mention.hover", "")
            );
            this.adFilter = new AdFilter(this.adsConfig, this.logger);
            this.spamFilter = new SpamFilter(this.spamConfig, this.logger);
            this.identity = new IdentityResolver(
                this.proxy,
                this.nicknames,
                this.config.bool("identity.allow-prefix-match", true),
                this.config.i("identity.min-prefix-length", 2)
            );
            // 把「名字 -> 展示目标」的解析器交给渲染器：
            //   @ 显示中文名；没中文名回退账号名；悬停看账号名；点击私聊
            this.renderer.resolver(name -> {
                var resolution = this.identity.resolve(name);
                if (resolution instanceof fd.chat.user.IdentityResolver.Resolution.Found found) {
                    String nickname = this.nicknames.byUuid(found.uuid())
                        .map(NicknameRepository.Entry::nickname)
                        .filter(n -> n != null && !n.isBlank())
                        .orElse(null);
                    String display = nickname != null ? nickname : found.username();
                    return new fd.chat.render.Renderer.MentionTarget(display, found.username(), nickname);
                }
                return new fd.chat.render.Renderer.MentionTarget(name, name, null);
            });

            this.strikes = new fd.chat.moderation.StrikeManager(this);
            this.dispatcher = new ChatDispatcher(this);
            this.mentionCompleter = new MentionCompleter(this);
            this.proxy.getEventManager().register(this, this.mentionCompleter);

            // 后端桥：@补全靠 Paper 的 AsyncTabCompleteEvent，音效靠 Paper 的 playSound
            this.backendBridge = new BackendBridge(this);
            this.backendBridge.register();
            this.proxy.getEventManager().register(this, this.backendBridge);

            this.api = new ApiImpl(this);
            FdChatApiRegistry.register(this.api);

            // 定期把展示元数据推给后端（中文名、频道、位置都可能变）
            long refreshSec = Math.max(1, this.config.l("signed-chat.display-refresh-seconds", 2));
            this.proxy.getScheduler()
                .buildTask(this, () -> {
                    if (this.backendBridge != null) {
                        this.backendBridge.pushDisplayAll();
                    }
                })
                .repeat(refreshSec, java.util.concurrent.TimeUnit.SECONDS)
                .schedule();

            this.localSuggester = new LocalSuggester(this);
            this.localSuggester.refresh();

            // 本地兜底联想：永远排在最后（order 最大），AI 插件注册的会先生效
            this.suggestions.register("__local",
                (source, input, limit) -> this.localSuggester.suggestCommand(input));

            new CommandRegistrar(this).register();

            // 已在线的玩家（/velocity reload 场景）
            for (Player player : this.proxy.getAllPlayers()) {
                this.users.load(player.getUniqueId(), player.getUsername());
            }

            this.logger.info("fdchat 已启用：昵称 {} 条，钱包 {}，默认频道 {}。",
                this.nicknames.size(),
                this.economy.enabled() ? this.economy.walletTable() : "未启用",
                this.channels.defaultChannel().key());
        } catch (Exception ex) {
            this.logger.error("fdchat 启动失败。", ex);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        FdChatApiRegistry.unregister();
        if (this.backendBridge != null) {
            this.backendBridge.unregister();
        }
        if (this.economy != null) {
            this.economy.close();
        }
        if (this.storage != null) {
            this.storage.close();
        }
    }

    // ---------------- 事件 ----------------

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        this.users.load(player.getUniqueId(), player.getUsername());
    }

    /** 玩家连上子服后，把展示元数据推给该后端（已签名消息的格式靠它）。 */
    @Subscribe
    public void onServerPostConnect(com.velocitypowered.api.event.player.ServerPostConnectEvent event) {
        if (this.backendBridge == null) {
            return;
        }
        Player player = event.getPlayer();
        // ★ 关键：换服后必须【立即】把展示元数据推给新后端。
        //   原来只等 2 秒推一次，导致玩家换服后 5 秒内的发言没有格式
        //   （实测：玩家 18:26:39 进入 zsj，18:26:49 发言仍然没有前缀）。
        //   这里做「立即 + 多次重推」，把数据窗口压到最小。
        this.backendBridge.pushDisplay(player);
        for (long delayMs : new long[]{500, 1500, 3000, 6000}) {
            this.proxy.getScheduler()
                .buildTask(this, () -> this.backendBridge.pushDisplay(player))
                .delay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .schedule();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        this.users.unload(id);
        this.spamFilter.forget(id);
        if (this.nicknamePrompts != null) {
            this.nicknamePrompts.forget(id);
        }
    }

    /**
     * 接管聊天。
     *
     * ★ Velocity 的硬约束（反编译 SessionChatHandler / KeyedChatHandler 实证）：
     *   对【已签名】入站包，代理侧既不能取消也不能改写，两者都会直接踢人：
     *     - cancelled（ChatResult.denied）→ invalidCancel() → disconnect
     *     - changed  （ChatResult.message）→ invalidChange() → disconnect
     *
     *   两个 handler 的判定方式不同，这是踩坑的关键：
     *     - KeyedChatHandler（< 1.19.3）：用 player.getIdentifiedKey() 判断，
     *       密钥为 null 时会安全降级，取消/改写都不踢人。
     *     - SessionChatHandler（>= 1.19.3）：**完全不看密钥**，只看聊天包里的
     *       signed 字段（客户端自己填的）。正版客户端恒为 true → 必然踢人。
     *
     *   而 HyperZoneLogin 走 OutPre 流程时用 NettyReflectionHelper.createConnectedPlayer()
     *   构建玩家，没有传入 chat session，导致 getIdentifiedKey() 恒为 null —— 代理
     *   根本无法得知入站包是否签名。此时若按老逻辑判定为「未签名，可接管」，
     *   就会对正版玩家的签名包调用 denied()，把他踢下线。
     *
     * 采用的策略：
     *   - getIdentifiedKey() != null：确认包必定是未签名，代理安全接管，
     *     取消后自行跨服投递，格式完全可控。
     *   - getIdentifiedKey() == null：签名状态不可知，原样放行交给后端装饰，
     *     代理侧只做违规判定与三振计次。此分支下无法阻止原消息广播。
     */
    @Subscribe
    public void onChat(PlayerChatEvent event) {
        if (this.dispatcher == null) {
            return;
        }

        Player player = event.getPlayer();
        boolean hasKey = player.getIdentifiedKey() != null;

        // 只有【确认拿得到客户端密钥】才敢让代理接管。
        // 拿不到密钥时无法判断入站包是否带签名，而 1.19.3+ 的 SessionChatHandler
        // 只看包里的 signed 字段：一旦我们 cancel（denied）或改写（message），
        // 正版客户端就会被 invalidCancel/invalidChange 直接踢下线
        // —— 见 KeyedChatHandler.invalidCancel/invalidChange，两者都会 disconnect。
        boolean canControl = hasKey;

        if (this.config.bool("settings.debug", false)) {
            this.logger.info("[Path] {} identifiedKey={} control={}", player.getUsername(), hasKey, canControl);
        }

        if (!canControl) {
            // 无法判断签名状态：唯一安全的做法是原样放行给后端装饰，
            // 代理侧只做违规判定与三振计次，不取消、不改写（避免踢人）。
            String violation = this.dispatcher.precheck(player, event.getMessage());
            if (violation != null) {
                fd.chat.moderation.StrikeManager.Verdict verdict = this.strikes.strike(player, violation);
                player.sendMessage(this.renderer.parse(this.strikes.warnMessage(verdict)));
                this.logger.info("[Strike] {} {}/{} violation ({}) [pass-through: cannot block]",
                    player.getUsername(), verdict.strikes(), verdict.limit(), violation);
                if (verdict.kick()) {
                    player.disconnect(this.renderer.parse(this.config.str("moderation.strikes.kick-message",
                        "<red>你因多次发布违规内容已被移出服务器。")));
                }
            }
            return;
        }

        // 拿得到密钥：可以安全接管（取消后自行跨服投递，格式完全可控）
        event.setResult(PlayerChatEvent.ChatResult.denied());
        this.dispatcher.dispatch(player, event.getMessage());
    }

    // ---------------- 热重载 ----------------

    public void reload(boolean ads, boolean spam) {
        this.config = this.config.reload(this.dataDirectory, this.logger);
        if (ads) {
            this.adsConfig = this.adsConfig.reload(this.dataDirectory, this.logger);
            this.adFilter.reload(this.adsConfig);
        }
        if (spam) {
            this.spamConfig = this.spamConfig.reload(this.dataDirectory, this.logger);
            this.spamFilter.reload(this.spamConfig);
        }
        this.channels.reload(this.config);

        // 经济模块重建（钱包表/开关可能变了）
        try {
            if (this.economy != null) {
                this.economy.close();
            }
            this.economy = new Economy(this.config, this.logger);
        } catch (Exception ex) {
            this.logger.warn("重载经济模块失败。", ex);
        }

        // 渲染器与联想器依赖配置，一并刷新
        try {
            this.renderer = new Renderer(
                this.config.str("mention.prefix", "@"),
                this.config.str("mention.highlight-color", "yellow"),
                this.config.str("mention.hover", "")
            );
            this.localSuggester.refresh();
        } catch (Exception ex) {
            this.logger.warn("刷新渲染器失败。", ex);
        }

        if (this.backendBridge != null) {
            this.backendBridge.pushDisplayAll();
        }

        this.logger.info("配置已重载（ads={}, spam={}）。", ads, spam);
    }

    public void reloadAll() {
        this.reload(true, true);
    }

    // ---------------- getter ----------------

    public ProxyServer proxy() {
        return this.proxy;
    }

    public Logger logger() {
        return this.logger;
    }

    public YamlConfig config() {
        return this.config;
    }

    public Storage storage() {
        return this.storage;
    }

    public NicknameRepository nicknames() {
        return this.nicknames;
    }

    public UserManager users() {
        return this.users;
    }

    public NicknamePrompts nicknamePrompts() {
        return this.nicknamePrompts;
    }

    public IdentityResolver identity() {
        return this.identity;
    }

    public Economy economy() {
        return this.economy;
    }

    public ChannelManager channels() {
        return this.channels;
    }

    public Renderer renderer() {
        return this.renderer;
    }

    public AdFilter adFilter() {
        return this.adFilter;
    }

    public SpamFilter spamFilter() {
        return this.spamFilter;
    }

    public List<ApiImpl.InterceptorEntry> interceptors() {
        return this.interceptors;
    }

    public ApiImpl.SuggestionRegistry suggestionRegistry() {
        return this.suggestions;
    }

    public fd.chat.moderation.StrikeManager strikes() {
        return this.strikes;
    }

    public BackendBridge backendBridge() {
        return this.backendBridge;
    }

    public LocalSuggester localSuggester() {
        return this.localSuggester;
    }

    public LuckPermsBridge luckPerms() {
        return this.luckPerms;
    }

    /** 已按 order 排序的拦截器（读多写少，排序结果缓存）。 */
    public List<ApiImpl.InterceptorEntry> sortedInterceptors() {
        synchronized (this.interceptors) {
            return this.interceptors.stream()
                .sorted(Comparator.comparingInt(ApiImpl.InterceptorEntry::order))
                .toList();
        }
    }

    /** 供拦截器收集建议时使用。 */
    public List<String> collectSuggestions(CommandSource source, String input, int limit) {
        return this.suggestions.suggest(source, input, limit);
    }

    public Optional<FdChatApi> api() {
        return Optional.ofNullable(this.api);
    }
}
