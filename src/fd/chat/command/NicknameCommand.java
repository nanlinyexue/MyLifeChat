package fd.chat.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import fd.chat.MyLifeChatPlugin;
import fd.chat.user.NicknameRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * /中文名 —— 全新实现，取代旧的 nickname_FD。
 *
 * 规则：
 *   - 无参数：查询自己的中文名
 *   - 有参数：设置/更换
 *   - 首次设置免费，之后每次更换收费（金额可配）
 *   - 唯一性由数据库唯一索引 + 内存原子占位双重保证
 */
final class NicknameCommand implements SimpleCommand {

    private final MyLifeChatPlugin plugin;

    NicknameCommand(MyLifeChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("该指令只能由玩家使用。"));
            return;
        }

        // 查询
        if (args.length == 0) {
            Optional<NicknameRepository.Entry> entry = this.plugin.nicknames().byUuid(player.getUniqueId());
            if (entry.isPresent()) {
                source.sendMessage(this.plugin.renderer().parse(
                    "<green>你的中文名是 <yellow><name></yellow>。",
                    TagResolver.resolver("name", Tag.inserting(Component.text(entry.get().nickname())))));
                double cost = this.plugin.config().d("nickname.change-cost", 0);
                if (cost > 0) {
                    source.sendMessage(this.plugin.renderer().parse(
                        "<gray>更换中文名需要 <yellow>" + this.plugin.economy().format(cost) + "</yellow>。"));
                }
            } else {
                source.sendMessage(this.plugin.renderer().parse(
                    "<red>你还没有设置中文名。<yellow>/中文名 <名字></yellow>"));
            }
            return;
        }

        String nickname = args[0].trim();
        setNickname(player, nickname);
    }

    private void setNickname(Player player, String nickname) {
        UUID uuid = player.getUniqueId();
        String username = player.getUsername();

        if (!this.plugin.config().bool("nickname.enabled", true)) {
            player.sendMessage(this.plugin.renderer().parse("<red>中文名功能当前已关闭。"));
            return;
        }

        // 1) 正则
        String regex = this.plugin.config().str("nickname.regex", "^[\\u4e00-\\u9fa5]{2,6}$");
        int min = this.plugin.config().i("nickname.min-length", 2);
        int max = this.plugin.config().i("nickname.max-length", 6);

        if (nickname.length() < min || nickname.length() > max) {
            player.sendMessage(this.plugin.renderer().parse(
                "<red>中文名长度必须是 <yellow>" + min + "~" + max + "</yellow> 个字符。"));
            return;
        }
        Pattern pattern = fd.chat.config.YamlConfig.compile(regex, this.plugin.logger(), "nickname.regex");
        if (pattern != null && !pattern.matcher(nickname).matches()) {
            player.sendMessage(this.plugin.renderer().parse(
                "<red>中文名只能使用简体或正体中文（不含空格与符号）。"));
            return;
        }

        // 2) 黑名单（主配置 + 广告库）
        List<String> blacklist = new ArrayList<>(this.plugin.config().strList("nickname.blacklist"));
        blacklist.addAll(this.plugin.adFilter().nicknameBlacklist());
        for (String bad : blacklist) {
            if (!bad.isBlank() && nickname.contains(bad)) {
                player.sendMessage(this.plugin.renderer().parse(
                    "<red>该中文名包含禁用词，请更换。"));
                return;
            }
        }

        // 3) 广告词
        if (this.plugin.adFilter().nicknameLooksLikeAd(nickname)) {
            player.sendMessage(this.plugin.renderer().parse("<red>该中文名包含疑似广告内容，请更换。"));
            return;
        }

        // 4) 唯一性
        boolean bypass = player.hasPermission(
            this.plugin.config().str("nickname.bypass-permission", "mylife.nickname.bypass"));
        if (!bypass && this.plugin.nicknames().isTakenByOther(nickname, uuid)) {
            Optional<NicknameRepository.Entry> holder = this.plugin.nicknames().byNickname(nickname);
            player.sendMessage(this.plugin.renderer().parse(
                "<red>中文名 <yellow>" + nickname + "</yellow> 已被占用"
                + holder.map(e -> "<gray>（" + maskName(e.username()) + "）").orElse("")
                + "，请换一个。"));
            return;
        }

        // 5) 冷却
        long cooldownSeconds = this.plugin.config().l("nickname.change-cooldown-seconds", 0);
        Optional<NicknameRepository.Entry> existing = this.plugin.nicknames().byUuid(uuid);
        if (cooldownSeconds > 0 && existing.isPresent()) {
            long elapsed = (System.currentTimeMillis() - existing.get().updatedAt()) / 1000;
            if (elapsed < cooldownSeconds) {
                player.sendMessage(this.plugin.renderer().parse(
                    "<red>改名冷却中，还需等待 <yellow>" + (cooldownSeconds - elapsed) + "</yellow> 秒。"));
                return;
            }
        }

        // 6) 计费：首次免费
        boolean firstFree = this.plugin.config().bool("nickname.first-free", true);
        boolean isFirst = existing.isEmpty();
        double cost = (isFirst && firstFree) ? 0 : this.plugin.config().d("nickname.change-cost", 0);

        if (bypass) {
            cost = 0;
        }

        if (cost > 0) {
            if (!this.plugin.economy().enabled()) {
                player.sendMessage(this.plugin.renderer().parse("<red>经济系统未启用，无法收费改名。"));
                return;
            }
            double balance = this.plugin.economy().balance(uuid);
            if (balance < cost) {
                player.sendMessage(this.plugin.renderer().parse(
                    "<red>余额不足。更换中文名需要 <yellow>" + this.plugin.economy().format(cost)
                    + "</yellow>，你还差 <yellow>"
                    + this.plugin.economy().format(cost - balance) + "</yellow>。"));
                return;
            }
            if (!this.plugin.economy().withdraw(uuid, cost)) {
                player.sendMessage(this.plugin.renderer().parse("<red>扣费失败，请稍后重试。"));
                return;
            }
        }

        // 7) 落库
        NicknameRepository.Result result =
            this.plugin.nicknames().set(uuid, username, nickname, cost, !isFirst || !firstFree);

        switch (result) {
            case OK -> {
                this.plugin.nicknamePrompts().markDone(uuid, username);
                player.sendMessage(this.plugin.renderer().parse(
                    "<green>中文名已设置为 <yellow>" + nickname + "</yellow>。"
                    + (cost > 0 ? "<gray>（扣除 " + this.plugin.economy().format(cost) + "）" : "")));
                if (cost > 0 && this.plugin.economy().enabled()) {
                    player.sendMessage(this.plugin.renderer().parse(
                        "<gray>当前余额：<yellow>"
                        + this.plugin.economy().format(this.plugin.economy().balance(uuid)) + "</yellow>"));
                }
                if (this.plugin.config().bool("nickname.broadcast-change", true)
                    && !isFirst) {
                    this.plugin.proxy().sendMessage(this.plugin.renderer().parse(
                        "<gray>[<yellow>改名<gray>] <white>" + maskName(username)
                        + " <gray>现在叫 <yellow>" + nickname));
                }
            }
            case TAKEN -> player.sendMessage(this.plugin.renderer().parse(
                "<red>该中文名刚刚被他人抢注了，请换一个。"));
            case INVALID -> player.sendMessage(this.plugin.renderer().parse("<red>中文名不合法。"));
            case UNCHANGED -> player.sendMessage(this.plugin.renderer().parse(
                "<gray>这就是你当前的中文名。"));
            default -> player.sendMessage(this.plugin.renderer().parse(
                "<red>保存失败，请联系管理员（可能是数据库问题）。"));
        }
    }

    /** 只显示账号名首尾，避免暴露完整 ID。 */
    private static String maskName(String username) {
        if (username == null || username.length() <= 2) {
            return String.valueOf(username);
        }
        return username.charAt(0) + "***" + username.charAt(username.length() - 1);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            return List.of();
        }
        return List.of();
    }
}
