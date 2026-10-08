package cn.floatdream.chatbridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.AsyncChatDecorateEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.player.PlayerChatTabCompleteEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MyLifeChat 后端桥。
 *
 * 存在的唯一原因：两件事在 Velocity 侧做不到，必须在 Paper 侧做。
 *
 *   1) 聊天框 @ 补全
 *      Velocity 的 TabCompleteEvent 只在「已注册的代理指令」且客户端 &lt; 1.13 时才触发
 *      （见 ClientPlaySessionHandler.handleTabCompleteResponse：非 1.13 以下直接转发给客户端）。
 *      Paper 的 AsyncTabCompleteEvent 没有这个限制，能直接改补全列表。
 *
 *   2) 提及音效
 *      Velocity 的 ConnectedPlayer.playSound 对 1.19.3 以下客户端直接 return
 *      （源码里就写着 lessThan(MINECRAFT_1_19_3)），所以在代理侧播音效等于没播。
 *      Paper 的 Player.playSound 没有版本限制。
 *
 * 名字来源：向代理请求全服在线名单（mylifechat:names），代理回 mlifechat:namelist。
 * 这样 @ 补全能跨子服。
 */
public final class MyLifeChatBridge extends JavaPlugin implements Listener, PluginMessageListener {

    /** 后端 -> 代理：请求全服在线名单 */
    private static final String CH_REQUEST = "mylifechat:names";
    /** 代理 -> 后端：名单（逗号分隔） */
    private static final String CH_NAMELIST = "mylifechat:namelist";
    /** 其他插件 -> 本插件：让某人播音效 */
    private static final String CH_SOUND = "mylifechat:sound";
    /** 代理 -> 后端：玩家展示元数据（频道名/中文名/位置/格式），用于已签名消息的显示 */
    private static final String CH_DISPLAY = "mylifechat:display";
    /** 后端 -> 代理：装饰后的聊天内容，交由代理广播（控制台 + 全服） */
    private static final String CH_CHAT_OUT = "mylifechat:chatout";

    /** 全服在线名单（由代理回填） */
    private final Set<String> globalNames = ConcurrentHashMap.newKeySet();

    /**
     * 玩家展示元数据（由代理下发）。
     *
     * 已签名消息的显示格式只能在后端（签名前 / 广播前）决定，
     * 所以代理把「频道名、中文名、位置串、格式模板」同步到这里。
     */
    private static final class DisplayMeta {
        String channelName = "";
        String nickname = "";
        String gamemode = "";
        String position = "";
        String format = "";
        /** 已经解析好的前缀 MiniMessage 串（由代理从 LuckPerms 取好） */
        String prefix = "";
    }

    private final Map<UUID, DisplayMeta> displayMeta = new ConcurrentHashMap<>();

    /**
     * 刚装饰过的消息（玩家 -> 装饰后的组件）。
     *
     * 为什么需要：1.19+ 的玩家聊天由服务端广播，装饰（AsyncChatDecorateEvent）只改
     * 「显示内容」，原版广播依然会把**未装饰**的原文再发一遍，玩家会看到两条。
     * 所以装饰后要把结果暂存，随后在 AsyncChatEvent 里取消原版广播、由我们自己发那一条。
     */
    private final Map<UUID, Component> pendingDecorated = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // 注册通道
        getServer().getMessenger().registerOutgoingPluginChannel(this, CH_REQUEST);
        getServer().getMessenger().registerIncomingPluginChannel(this, CH_NAMELIST, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, CH_SOUND, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, CH_DISPLAY, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CH_CHAT_OUT);

        getServer().getPluginManager().registerEvents(this, this);

        pollNames();
        // 定时刷新（跨服名单会变）
        long interval = Math.max(20L, getConfig().getLong("names-refresh-ticks", 100L));
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::pollNames, interval, interval);

        getLogger().info("MyLifeChatBridge enabled (@tab + mention sound).");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }

    /** 向代理索取全服名单：借任意在线玩家的连接发送。 */
    private void pollNames() {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            globalNames.clear();
            return;
        }
        Player carrier = Bukkit.getOnlinePlayers().iterator().next();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new DataOutputStream(out).writeUTF(carrier.getName());
            carrier.sendPluginMessage(this, CH_REQUEST, out.toByteArray());
        } catch (Exception ex) {
            getLogger().warning("Failed to request name list: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  1) @ 补全
    //
    //  关键：聊天框补全必须用 PlayerChatTabCompleteEvent，
    //  不是 Paper 的 AsyncTabCompleteEvent（那个在聊天框场景不触发）。
    //  生产上在用的 AT 插件用的就是这个事件，实测可用。
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL)
    public void onChatTabComplete(PlayerChatTabCompleteEvent event) {
        // ★ 该方法用到的 PlayerChatTabCompleteEvent 在部分服务端版本上已被标记弃用/移除。
        //   这里整体兜底：补全失败只是少个便利功能，绝不能让它抛异常影响聊天主流程。
        try {
            handleChatTabComplete(event);
        } catch (Throwable ex) {
            if (!tabErrorLogged) {
                tabErrorLogged = true;
                getLogger().warning("[TabComplete] @ 补全失败（已停用后续日志）：" + ex);
            }
        }
    }

    private volatile boolean tabErrorLogged;

    private void handleChatTabComplete(PlayerChatTabCompleteEvent event) {
        if (!getConfig().getBoolean("tab-complete.enabled", true)) {
            return;
        }
        String message = event.getChatMessage();
        if (message == null || message.isEmpty()) {
            return;
        }

        String prefixToken = getConfig().getString("tab-complete.prefix", "@");
        int at = message.lastIndexOf(prefixToken);
        if (at < 0) {
            return;                       // 没在 @ 某人
        }
        // @ 必须在行首或紧跟空白，避免把邮箱 a@b 当提及
        if (at > 0 && !Character.isWhitespace(message.charAt(at - 1))) {
            return;
        }
        String typed = message.substring(at + prefixToken.length());
        if (typed.indexOf(' ') >= 0) {
            return;                       // 名字已写完
        }

        // 本服 + 全服（代理回填）候选
        Set<String> candidates = new LinkedHashSet<>();
        boolean withName = getConfig().getBoolean("tab-complete.usernames", true);
        if (withName) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (matches(p.getName(), typed)) {
                    candidates.add(p.getName());
                }
            }
            for (String name : globalNames) {
                if (matches(name, typed)) {
                    candidates.add(name);
                }
            }
        }

        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[TabComplete] " + event.getPlayer().getName()
                + " msg='" + message + "' 前缀='" + typed + "' 候选=" + candidates.size());
        }

        if (candidates.isEmpty()) {
            return;                       // 没命中就不动原有补全
        }

        // 生成补全结果：与 AT 插件同样的做法 —— 替换最后一个 @token
        int limit = getConfig().getInt("tab-complete.limit", 40);
        List<String> fit = new ArrayList<>();
        int base = Math.max(0, at);
        String head = message.substring(0, base);
        for (String name : candidates) {
            fit.add(head + prefixToken + name);
            if (fit.size() >= limit) {
                break;
            }
        }

        event.getTabCompletions().clear();
        event.getTabCompletions().addAll(fit);

        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[TabComplete] returned " + fit.size() + " entries, e.g.: "
                + (fit.isEmpty() ? "-" : fit.get(0)));
        }
    }

    private static boolean matches(String name, String typed) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (typed == null || typed.isEmpty()) {
            return true;
        }
        return name.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------
    //  2) 插件消息
    // ------------------------------------------------------------------

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        try {
            if (CH_NAMELIST.equals(channel)) {
                handleNameList(message);
            } else if (CH_SOUND.equals(channel)) {
                handleSound(message);
            } else if (CH_DISPLAY.equals(channel)) {
                handleDisplay(message);
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to handle plugin message (" + channel + "): " + ex.getMessage());
        }
    }

    private void handleNameList(byte[] message) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
        String csv = in.readUTF();
        Set<String> parsed = ConcurrentHashMap.newKeySet();
        for (String part : csv.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                parsed.add(name);
            }
        }
        // 只在人数变化时记录，避免每 5 秒刷屏
        int before = globalNames.size();
        globalNames.clear();
        globalNames.addAll(parsed);
        if (getConfig().getBoolean("debug", false) && before != parsed.size()) {
            getLogger().info("[NameList] global online " + parsed.size() + "");
        }
    }

    /** 让指定玩家播放音效；格式：UTF(playerName) + UTF(soundKey) + float volume + float pitch */
    private void handleSound(byte[] message) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
        String targetName = in.readUTF();
        String soundKey = in.readUTF();
        float volume = in.readFloat();
        float pitch = in.readFloat();

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            return;
        }
        try {
            // 用 key 形式播放（1.13+ 的命名空间音效）
            target.playSound(target.getLocation(), soundKey, volume, pitch);
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("[Sound] played " + targetName + " for " + soundKey);
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to play sound " + soundKey + ": " + ex.getMessage());
        }
    }

    /** 接收代理下发的展示元数据。 */
    private void handleDisplay(byte[] message) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
        String uuidStr = in.readUTF();
        DisplayMeta meta = new DisplayMeta();
        meta.channelName = in.readUTF();
        meta.nickname = in.readUTF();
        meta.gamemode = in.readUTF();
        meta.position = in.readUTF();
        meta.format = in.readUTF();
        meta.prefix = in.readUTF();
        try {
            displayMeta.put(UUID.fromString(uuidStr), meta);
        } catch (IllegalArgumentException ignored) {
            // uuid 非法则忽略
        }
    }

    // ------------------------------------------------------------------
    //  3) 已签名消息的显示格式
    //
    //  正版玩家（1.19.1+）的聊天带签名，Velocity 侧既不能取消也不能改写，
    //  所以「显示成什么样」只能在 Paper 侧决定。
    //  AsyncChatDecorateEvent 在【签名之前】触发，改它不会破坏签名，玩家不会被踢。
    // ------------------------------------------------------------------

    /**
     * 兼容读取 AsyncChatDecorateEvent#isPreview()。
     *
     * ★ 为什么必须反射：
     *   该方法在 Paper 1.19.2 API 里存在，但在 Paper 26.2 的 API 中【已被移除】。
     *   bridge 是用 1.19.2 API 编译的（为了同时兼容仍停留在 1.19.2 的后端），
     *   直接调用 isPreview() 在 26.2 服务端上会抛
     *     java.lang.NoSuchMethodError: AsyncChatDecorateEvent.isPreview()
     *   而它又是本方法的第一个判断 —— 导致 onChatDecorate 每次都中断，
     *   聊天格式化整体失效（表现为原版 <玩家名>: 消息），同时每条消息都产生一次异常开销。
     *
     *   这里按方法是否存在来判定：不存在（26.2）视为非预览；
     *   存在（1.19.2）则取真实值。
     */
    private static volatile java.lang.reflect.Method previewMethod;
    private static volatile boolean previewMethodResolved;

    private static boolean isPreview(AsyncChatDecorateEvent event) {
        if (!previewMethodResolved) {
            synchronized (MyLifeChatBridge.class) {
                if (!previewMethodResolved) {
                    try {
                        previewMethod = AsyncChatDecorateEvent.class.getMethod("isPreview");
                    } catch (NoSuchMethodException ignored) {
                        previewMethod = null;   // 26.2+：方法已移除
                    }
                    previewMethodResolved = true;
                }
            }
        }
        if (previewMethod == null) {
            return false;
        }
        try {
            return (Boolean) previewMethod.invoke(event);
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChatDecorate(AsyncChatDecorateEvent event) {
        // ★ 整体兜底：装饰失败会让玩家看到原版格式，但绝不能让异常打断聊天链路。
        //   （历史事故：26.2 上 isPreview() 被移除导致 NoSuchMethodError，格式整体失效。）
        try {
            handleChatDecorate(event);
        } catch (Throwable ex) {
            if (!decorateErrorLogged) {
                decorateErrorLogged = true;
                getLogger().warning("[Decorate] 聊天格式化失败（已停用后续日志）：" + ex);
            }
        }
    }

    private volatile boolean decorateErrorLogged;

    private void handleChatDecorate(AsyncChatDecorateEvent event) {
        if (!getConfig().getBoolean("chat-format.enabled", true) || isPreview(event)) {
            return;
        }
        Player player = event.player();
        if (player == null) {
            return;
        }
        DisplayMeta meta = displayMeta.get(player.getUniqueId());
        if (meta == null || meta.format == null || meta.format.isEmpty()) {
            return;   // 没有元数据就保持后端原生格式
        }

        Component original = event.result();
        String plain = PlainTextComponentSerializer.plainText().serialize(original);

        // 代理下发的模板里正文占位符可能是 <message> 或 <fdmsg>；
        // 先换成一个不会与 MiniMessage 冲突的哨兵，最后再替换成玩家原始消息。
        // 注意：不能把正文直接塞进 MiniMessage 解析，否则会破坏玩家消息自身的样式/事件。
        String sentinel = "\uE000FDMSG\uE001";
        String template = meta.format.replace("<fdmsg>", sentinel).replace("<message>", sentinel);
        TagResolver resolver = TagResolver.builder()
            .resolver(TagResolver.resolver("channelname",
                Tag.inserting(MiniMessage.miniMessage().deserialize(meta.channelName))))
            .resolver(TagResolver.resolver("gamemode",
                Tag.inserting(MiniMessage.miniMessage().deserialize(meta.gamemode))))
            .resolver(TagResolver.resolver("position",
                Tag.inserting(MiniMessage.miniMessage().deserialize(meta.position))))
            .resolver(TagResolver.resolver("prefix",
                Tag.inserting(meta.prefix == null || meta.prefix.isEmpty()
                    ? Component.empty()
                    : MiniMessage.miniMessage().deserialize(meta.prefix))))
            .resolver(TagResolver.resolver("suffix", Tag.inserting(Component.empty())))
            .resolver(TagResolver.resolver("time",
                Tag.inserting(Component.text(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))))))
            .resolver(TagResolver.resolver("nickname", Tag.inserting(Component.text(meta.nickname))))
            .resolver(TagResolver.resolver("username", Tag.inserting(Component.text(player.getName()))))
            .resolver(TagResolver.resolver("channel", Tag.inserting(Component.text("global"))))
            .build();

        try {
            Component decorated = MiniMessage.miniMessage().deserialize(template, resolver);
            // 把占位符换成玩家原始消息（保留其原有样式）
            decorated = decorated.replaceText(b -> b.matchLiteral(sentinel).replacement(original));
            event.result(decorated);
            pendingDecorated.put(player.getUniqueId(), decorated);
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("[Format] " + player.getName() + " -> "
                    + PlainTextComponentSerializer.plainText().serialize(decorated));
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to apply chat format: " + ex.getMessage());
        }
    }

    /**
     * 丢弃被代理拦截的消息。
     *
     * 代理无法取消签名消息（会踢人），所以把违规消息改写成一个哨兵值；
     * 这里识别哨兵并取消广播 —— 效果等同禁言。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatDropBlocked(AsyncChatEvent event) {
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        String sentinel = getConfig().getString("chat-format.blocked-sentinel", "\u200B\u200B");

        // ① 被代理拦截的消息：直接丢弃
        if (plain.equals(sentinel) || plain.isBlank()) {
            event.setCancelled(true);
            pendingDecorated.remove(event.getPlayer().getUniqueId());
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("[Blocked] dropped " + event.getPlayer().getName() + " blocked message");
            }
            return;
        }

        // ② 装饰过的消息：取消原版广播，改发装饰后的那一条，避免玩家看到两条
        if (!getConfig().getBoolean("chat-format.replace-vanilla", true)) {
            return;
        }
        Component decorated = pendingDecorated.remove(event.getPlayer().getUniqueId());
        if (decorated == null) {
            return;   // 没装饰过就别动
        }

        event.setCancelled(true);
        // ★ 只交给代理广播，【不要】再在本服发一次。
        //   代理广播会覆盖所有子服（含本服发送者），本服再发一次就会出现两条完全相同的消息。
        broadcastToProxy(event.getPlayer(), decorated);
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[Broadcast] " + event.getPlayer().getName()
                + " -> " + PlainTextComponentSerializer.plainText().serialize(decorated));
        }
    }

    /** 把装饰后的消息经插件消息发给代理，由代理广播到全服与控制台。 */
    private void broadcastToProxy(Player player, Component message) {
        try {
            String mini = MiniMessage.miniMessage().serialize(message);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(out);
            dos.writeUTF(player.getName());
            dos.writeUTF(mini);
            player.sendPluginMessage(this, CH_CHAT_OUT, out.toByteArray());
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("[ProxyBroadcast] sent " + player.getName());
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to send chat to proxy: " + ex.getMessage());
        }
    }

    // 玩家进出时刷新名单，让 @ 补全更及时
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::pollNames, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        globalNames.remove(event.getPlayer().getName());
        displayMeta.remove(event.getPlayer().getUniqueId());
    }
}
