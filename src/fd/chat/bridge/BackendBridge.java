package fd.chat.bridge;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import fd.chat.MyLifeChatPlugin;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 与后端桥（MyLifeChatBridge）通信的代理侧。
 *
 * 为什么需要它：两件事在 Velocity 侧做不到，必须由 Paper 侧执行 ——
 *
 *   1) 聊天框 @ 补全
 *      Velocity 的 TabCompleteEvent 仅对「已注册代理指令 + 客户端 1.13 以下」触发
 *      （ClientPlaySessionHandler.handleTabCompleteResponse 中非 1.13 以下直接转发客户端）。
 *      Paper 的 AsyncTabCompleteEvent 没有此限制。
 *
 *   2) 提及音效
 *      Velocity 的 ConnectedPlayer.playSound 对 1.19.3 以下客户端直接 return。
 *      Paper 的 Player.playSound 无版本限制（HubLite 就是用这个，实测有效）。
 *
 * 分工：代理负责「知道谁在线」（全局视角），后端负责「改补全 / 播音效」（本地能力）。
 */
public final class BackendBridge {

    /** 后端 -> 代理：请求全服在线名单 */
    public static final String CH_REQUEST = "mylifechat:names";
    /** 代理 -> 后端：名单（逗号分隔） */
    public static final String CH_NAMELIST = "mylifechat:namelist";
    /** 代理 -> 后端：让某人播音效 */
    public static final String CH_SOUND = "mylifechat:sound";
    /** 代理 -> 后端：玩家展示元数据（已签名消息的显示格式只能在后端决定） */
    public static final String CH_DISPLAY = "mylifechat:display";
    /** 后端 -> 代理：装饰后的聊天内容，由代理广播（控制台 + 全服） */
    public static final String CH_CHAT_OUT = "mylifechat:chatout";

    private final MyLifeChatPlugin plugin;

    public BackendBridge(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
    }

    /** 注册通道（在 onProxyInitialize 里调用）。 */
    public void register() {
        var registrar = this.plugin.proxy().getChannelRegistrar();
        registrar.register(
            MinecraftChannelIdentifier.from(CH_REQUEST),
            MinecraftChannelIdentifier.from(CH_NAMELIST),
            MinecraftChannelIdentifier.from(CH_SOUND),
            MinecraftChannelIdentifier.from(CH_DISPLAY),
            MinecraftChannelIdentifier.from(CH_CHAT_OUT)
        );
        this.plugin.logger().info("Backend bridge channels registered (@tab + mention sound).");
    }

    public void unregister() {
        var registrar = this.plugin.proxy().getChannelRegistrar();
        registrar.unregister(
            MinecraftChannelIdentifier.from(CH_REQUEST),
            MinecraftChannelIdentifier.from(CH_NAMELIST),
            MinecraftChannelIdentifier.from(CH_SOUND),
            MinecraftChannelIdentifier.from(CH_DISPLAY),
            MinecraftChannelIdentifier.from(CH_CHAT_OUT)
        );
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        String id = event.getIdentifier().getId();

        // 后端装饰好的聊天内容 -> 由代理广播（这样控制台能记录、其它子服也能收到）
        if (id.equals(CH_CHAT_OUT)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            if (!(event.getSource() instanceof ServerConnection conn)) {
                return;
            }
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()));
                String sender = in.readUTF();
                String mini = in.readUTF();
                var component = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(mini);
                // 广播给所有玩家
                this.plugin.proxy().sendMessage(component);
                // 以及控制台/日志（与盗版路径保持一致）
                this.plugin.logger().info("[{}] {}", "global", sender + ": "
                    + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(component));
                if (this.plugin.config().bool("settings.debug", false)) {
                    this.plugin.logger().info("[ChatOut] from {} via {}", sender,
                        conn.getServerInfo().getName());
                }
            } catch (Exception ex) {
                this.plugin.logger().warn("Broadcasting decorated chat failed.", ex);
            }
            return;
        }

        if (!id.equals(CH_REQUEST)) {
            return;   // 其余只处理名单请求
        }
        // 标记已处理，避免继续下发给客户端
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection conn)) {
            return;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()));
            String carrier = in.readUTF();   // 发请求的玩家（用它把回复送回去）
            String csv = buildNameList();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(out);
            dos.writeUTF(csv);
            conn.sendPluginMessage(MinecraftChannelIdentifier.from(CH_NAMELIST), out.toByteArray());
            if (this.plugin.config().bool("settings.debug", false)) {
                this.plugin.logger().info("[Bridge] replied name list to {} ({} entries)", carrier, csv.split(",").length);
            }
        } catch (Exception ex) {
            this.plugin.logger().warn("Failed to reply name list.", ex);
        }
    }

    /** 全服在线玩家名（账号名 + 中文名，中文名优先排在前面）。 */
    private String buildNameList() {
        Set<String> names = new LinkedHashSet<>();
        // 先放中文名
        for (Player p : this.plugin.proxy().getAllPlayers()) {
            this.plugin.nicknames().byUuid(p.getUniqueId())
                .map(fd.chat.user.NicknameRepository.Entry::nickname)
                .filter(n -> n != null && !n.isBlank())
                .ifPresent(names::add);
        }
        // 再放账号名
        for (Player p : this.plugin.proxy().getAllPlayers()) {
            names.add(p.getUsername());
        }
        return String.join(",", names);
    }

    /**
     * 把玩家的展示元数据下发给其后端。
     *
     * 为什么必须这么做：正版玩家（1.19.1+）的聊天带签名，Velocity 不允许插件改写消息
     * （改写即踢人，见 KeyedChatHandler.invalidChange）。所以「显示成什么样」只能在
     * Paper 侧的 AsyncChatDecorateEvent（签名之前）决定 —— 代理负责把
     * 频道名 / 中文名 / 位置串 / 格式模板 同步过去。
     */
    public void pushDisplay(Player player) {
        player.getCurrentServer().ifPresent(conn -> {
            try {
                String channelKey = this.plugin.users().channel(player.getUniqueId());
                var parsed = this.plugin.channels().parse("", channelKey);
                var channel = parsed.channel();

                String nickname = this.plugin.identity()
                    .displayName(player.getUniqueId(), player.getUsername());
                String server = conn.getServerInfo().getName();
                String gamemode = this.plugin.config().str("locations." + server + ".gamemode", "");
                String position = this.plugin.config().str("locations." + server + ".position", "");
                String channelName = channel.displayName() == null ? "" : channel.displayName();
                String format = this.plugin.config().str("format.chat", "");
                // LuckPerms 前缀由代理取好，后端不碰权限系统
                String prefix = this.plugin.luckPerms() == null
                    ? "" : this.plugin.luckPerms().rawPrefix(player);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(out);
                dos.writeUTF(player.getUniqueId().toString());
                dos.writeUTF(channelName);
                dos.writeUTF(nickname);
                dos.writeUTF(gamemode);
                dos.writeUTF(position);
                dos.writeUTF(format);
                dos.writeUTF(prefix);
                conn.sendPluginMessage(MinecraftChannelIdentifier.from(CH_DISPLAY), out.toByteArray());
                if (this.plugin.config().bool("settings.debug", false)) {
                    this.plugin.logger().info("[Meta] {} channel='{}' nickname='{}' gamemode='{}' formatLen={}",
                        player.getUsername(), channelName, nickname, gamemode,
                        format == null ? -1 : format.length());
                }
            } catch (Exception ex) {
                this.plugin.logger().debug("Failed to push display meta.", ex);
            }
        });
    }

    /** 给所有在线玩家重新下发（配置热重载后调用）。 */
    public void pushDisplayAll() {
        for (Player p : this.plugin.proxy().getAllPlayers()) {
            pushDisplay(p);
        }
    }

    /**
     * 让某个玩家播放音效（经其后端执行）。
     * Velocity 的 playSound 对 1.19.3 以下无效，所以必须委托后端。
     */
    public void playSound(Player target, String soundKey, float volume, float pitch) {
        target.getCurrentServer().ifPresent(conn -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(out);
                dos.writeUTF(target.getUsername());
                dos.writeUTF(soundKey);
                dos.writeFloat(volume);
                dos.writeFloat(pitch);
                conn.sendPluginMessage(MinecraftChannelIdentifier.from(CH_SOUND), out.toByteArray());
            } catch (Exception ex) {
                this.plugin.logger().debug("Failed to send sound request (bridge missing?).", ex);
            }
        });
    }
}
