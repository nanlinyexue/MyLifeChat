package fd.chat.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import fd.chat.MyLifeChatPlugin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;

/**
 * /在线 —— 跨服在线列表。
 *
 * 存在的意义：@提及 需要玩家知道别的服有谁在线。
 * 按子服分组显示，优先显示中文名（没设则显示账号名）。
 */
final class OnlineCommand implements SimpleCommand {

    private final MyLifeChatPlugin plugin;

    OnlineCommand(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!this.plugin.config().bool("online.enabled", true)) {
            source.sendMessage(Component.text("在线列表已关闭。"));
            return;
        }

        Map<String, List<String>> byServer = new LinkedHashMap<>();
        for (Player p : this.plugin.proxy().getAllPlayers()) {
            String server = p.getCurrentServer()
                .map(c -> c.getServerInfo().getName()).orElse("?");
            String name = this.plugin.identity().displayName(p.getUniqueId(), p.getUsername());
            byServer.computeIfAbsent(server, k -> new ArrayList<>()).add(name);
        }

        int total = this.plugin.proxy().getAllPlayers().size();
        if (total == 0) {
            source.sendMessage(this.plugin.renderer().parse(
                this.plugin.config().str("online.empty", "<gray>暂无人在线</gray>")));
            return;
        }

        String lineFormat = this.plugin.config().str("online.format",
            "<gray>[<white>%server%</white>]</gray> <white>%players%</white>");

        source.sendMessage(this.plugin.renderer().parse(
            "<aqua>当前在线 <white>" + total + "</white> 人<gray>（跨所有子服）</gray></aqua>"));
        for (Map.Entry<String, List<String>> e : byServer.entrySet()) {
            String players = String.join("<gray>, </gray><white>", e.getValue());
            source.sendMessage(this.plugin.renderer().parse(
                lineFormat.replace("%server%", e.getKey()).replace("%players%", players)));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }
}
