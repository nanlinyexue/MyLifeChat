# MyLifeChat

FloatDream 服务器群的自研跨服聊天系统，用于**完全替代**旧 BungeeCord 聊天体系（`chat_FD` / `nickname_FD` / `permissionGroup_FD`）。

## 组成

| 模块 | 类型 | 部署位置 | 职责 |
|---|---|---|---|
| 根目录 | Velocity 插件 | 代理（Velocity 4.2.1-SNAPSHOT） | 频道、中文名、私聊、反广告/反刷屏、三振、AI 钩子 |
| `bridge/` | Paper 插件 | 各子服（Paper 26.2 / 1.19.2） | 聊天框 `@` 补全、显示格式装饰、提及提示音 |

两者配套部署，版本联动，故同仓维护。

## 功能

- **频道**：全球（可计费）/ 子服 / 本地半径 / 私聊 / 公会（预留）
- **中文名**：唯一性校验、首次免费、改名收费、黑名单、未设置时一次性提示
- **@提及**：Tab 补全、点击私聊、悬停显示账号名、被提及者提示音
- **治理**：反广告、反刷屏、频道冷却、三振出局（警告 → 踢出）
- **AI 钩子**：`??` 前缀问答入口 + 错别字联想接口（供 `fdai` 接入）

## ★ 两个必须知道的硬约束

### 1. Velocity 的签名聊天约束（代理侧）

已反编译 `SessionChatHandler` / `KeyedChatHandler` 实证：

- **`KeyedChatHandler`（< 1.19.3）**：用 `player.getIdentifiedKey()` 判断。密钥为 null 时**安全降级**，取消/改写都不踢人。
- **`SessionChatHandler`（>= 1.19.3）**：**完全不看密钥**，只看聊天包里的 `signed` 字段（客户端自己填的）。正版客户端恒为 `true`。

因此对已签名入站包，代理侧**既不能取消也不能改写**，两者都会直接断连：

```java
// SessionChatHandler
if (!chatResult.isAllowed()) {              // denied()
    if (packet.isSigned()) KeyedChatHandler.invalidCancel(logger, player);   // → disconnect
}
if (chatResult.getMessage() != packet.getMessage()) {   // message("...")
    if (packet.isSigned()) KeyedChatHandler.invalidChange(logger, player);   // → disconnect
}
```

**结论：只有在能确认拿得到客户端密钥时才可接管聊天，否则必须原样放行交给后端装饰。**

> 生产踩坑记录：HyperZoneLogin 的 OutPre 流程用 `NettyReflectionHelper.createConnectedPlayer()`
> 构建玩家时**没有传入 chat session**，导致 `getIdentifiedKey()` 恒为 null —— 代理无法得知入站包
> 是否签名。早期版本据此判定为"未签名、可接管"并调用 `denied()`，使正版玩家**每次发言都被踢**
> （现象是 `SessionChatHandler: A plugin tried to cancel a signed chat message`）。

### 2. Paper 26.2 移除 `AsyncChatDecorateEvent#isPreview()`

该方法在 1.19.2 API 中存在，**26.2 API 中已被移除**。bridge 若直接调用并在 26.2 上运行，会在
**每条消息**上抛 `NoSuchMethodError`，导致聊天格式化整体失效（表现为原版 `<玩家名>: 消息`）。

现方案：按 **26.2 API 编译**，`isPreview` 走反射（兼容两版本），且所有桥接事件处理器均加兜底，
任何异常都不再影响聊天主链路。

> `bridge/build.sh` **显式只用 26.2 API**：`libs/` 下同时存在 1.19.2 与 26.2 两份 `paper-api`，
> 用 `libs/*.jar` 通配会让旧 API 先出现并遮蔽新 API，从而又编译出引用 `isPreview()` 的字节码。

## 构建

```bash
# 代理插件（JDK 25）→ build/MyLifeChat-1.0.0.jar
./build.sh

# 后端桥（JDK 25，按 Paper 26.2 API）→ build/MyLifeChatBridge-1.0.0.jar
cd bridge && ./build.sh
```

## 配置

生产配置位于各端的 `plugins/mylifechat/config.yml`。

**首次使用请先填写数据库信息**：仓库内 `resources/config.yml` 的 `password` 已置为 `CHANGE_ME`
（真实凭据不入库）。涉及两块存储：

- `fdchat_*` 表：中文名、玩家状态、忽略列表
- `xconomy_sc` 表：全球频道计费所用余额（XConomy）

## 数据表

| 表 | 用途 |
|---|---|
| `fdchat_nickname` | 中文名（`nickname` 唯一索引） |
| `fdchat_nickname_history` | 改名历史 |
| `fdchat_player` | 禁言、频道、提及音效、昵称提示标记等 |
| `fdchat_ignore` | 私聊屏蔽 |
| `fdchat_player`（`nickname_prompted`） | 「中文名提示一生只弹一次」的持久化标记 |

> 注意 `fdchat_player.username` 为 `NOT NULL` 且无默认值，写该表时**必须补齐 username**，
> 否则会抛 `Field 'username' doesn't have a default value`。该异常位于聊天主路径上，
> 曾造成每次发言一次失败往返（可感知卡顿）且提示反复弹出。
