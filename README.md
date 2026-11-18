# InvSync for Minecraft 26.3

InvSync 可以在不同的 Minecraft 世界或服务器之间同步同一玩家的背包、末影箱、经验、血量、饥饿值、状态效果、原版统计和进度。玩家按 **UUID** 匹配。坐标、朝向、维度和重生点由各服独立管理。

## 安装

1. 使用 **Minecraft Java 版 26.3**、**Java 25** 和 **Fabric Loader 0.19.5 或更新版本**。可以在启动服务器的环境里运行 `java -version` 检查 Java 版本。
2. 从 [2.3.6 发布页](https://github.com/RiYueTeam/InvSync-26.3/releases/tag/v2.3.6-26.3) 下载对应的 JAR，或使用下表中的本地构建产物。单人世界使用对应 Fabric 实例的 `mods/` 目录。
3. 首次启动后按下表找到配置文件，停服修改配置，再重启。普通联机客户端可以直接连接，数据库驱动已随 JAR 打包。

| 服务端 | 本地 JAR | 安装目录 | 配置文件 |
| --- | --- | --- | --- |
| Fabric | `build/libs/invsync-2.3.6+26.3.jar` | `mods/` | `config/invsync.json` |
| Spigot | `spigot/build/libs/invsync-spigot-2.3.6+26.3.jar` | `plugins/` | `plugins/InvSyncSpigot/config.yml` |

以下 SQLite 示例适用于 Fabric。Fabric 与 Spigot 混用时，两端都选 MySQL。

| 场景 | 数据库 | 各实例的设置 |
| --- | --- | --- |
| 单个 Fabric 实例下的多个单人世界，或同一个服务端实例 | SQLite | 使用默认配置即可 |
| 同一台机器上的多个服务端进程 | SQLite | 使用同一个绝对目录和数据库名 |
| 不同机器上的多个服务端 | MySQL | 连接到同一个 MySQL 实例和数据库 |

## SQLite 配置

默认在启动目录下的 `config/invsync/` 创建 `InvSync.db`。同一台机器上的多个服务端需要把 `SQLITE_DIRECTORY` 改成**同一个绝对路径**。各服还需使用相同的 `DATABASE_NAME`，并允许服务端进程写入该目录。

Windows 示例，JSON 中可用正斜杠表示路径：

```json
{
  "DATABASE_TYPE": "SQLITE",
  "DATABASE_NAME": "InvSync",
  "SQLITE_DIRECTORY": "D:/minecraft/shared-invsync",
  "SYNC_PLAYER_DATA": true,
  "SYNC_ADVANCEMENTS": true,
  "SYNC_STATS": true
}
```

Linux 可将路径改成 `/srv/minecraft/shared-invsync`。SQLite 还会创建 `InvSync.locks/` 存放玩家会话的锁文件。运行期间请保留该目录。

## MySQL 配置

1. 先在 MySQL 上创建一个数据库，例如 `invsync`，并准备一个专用账号。账号需要能连接该库，并有建表、查询、插入和更新权限。InvSync 会自动创建 `InvSync_base` 表。
2. 把每台服务器的配置都指向同一个 MySQL 主机、端口和数据库。账号密码填实际值。下面的 `db.internal` 是示例地址。
3. 重启各服。在每台服务器上检查 `logs/latest.log`，确认能正常启动，再用测试账号进行跨服验证。

```json
{
  "DATABASE_TYPE": "MYSQL",
  "DATABASE_NAME": "invsync",
  "MYSQL_ADDRESS": "db.internal",
  "MYSQL_PORT": "3306",
  "MYSQL_USERNAME": "invsync",
  "MYSQL_PASSWORD": "填写实际密码",
  "SYNC_PLAYER_DATA": true,
  "SYNC_ADVANCEMENTS": true,
  "SYNC_STATS": true
}
```

配置文件会保存数据库密码，请限制文件读取权限。`MYSQL_PORT` 按示例保留为带引号的字符串。

## MySQL TLS

Fabric 和 Spigot 的 2.3.6 版都支持 MySQL TLS。跨机器部署时，使用 `VERIFY_IDENTITY` 同时校验证书的信任链和数据库主机名。连接地址应与服务器证书 SAN 中的 DNS 名称匹配。用 IP 连接时，证书 SAN 需要包含对应的 IP 地址。

| Fabric 的 `config/invsync.json` | Spigot 的 `mysql.tls` | 说明 |
| --- | --- | --- |
| `MYSQL_SSL_MODE` | `mode` | `PREFERRED` 保持旧配置行为。`REQUIRED` 强制加密。`VERIFY_CA` 还校验信任链。`VERIFY_IDENTITY` 同时校验信任链和主机名。`DISABLED` 关闭 MySQL TLS |
| `MYSQL_TRUST_STORE` | `trust-store` | 可选的 CA 信任库路径。留空时使用 Java 默认信任库 |
| `MYSQL_TRUST_STORE_TYPE` | `trust-store-type` | `PKCS12`（默认）或 `JKS` |
| `MYSQL_TRUST_STORE_PASSWORD` | `trust-store-password` | 自定义信任库的密码 |

如果数据库证书由私有 CA 签发，可以用 JDK 的 `keytool` 导入 CA 公共证书。命令会提示设置或输入信任库密码。

```sh
keytool -importcert -alias mysql-ca -file mysql-ca.pem -keystore mysql-ca.p12 -storetype PKCS12
```

将 `mysql-ca.p12` 放到两台游戏服可读取的位置。相对路径按各自服务端的工作目录解析。指定自定义信任库时，只使用该信任库。把以下字段合并进现有 MySQL 配置，保留数据库名、端口和数据库账号设置。

Fabric 示例

```json
{
  "MYSQL_ADDRESS": "db.example.net",
  "MYSQL_SSL_MODE": "VERIFY_IDENTITY",
  "MYSQL_TRUST_STORE": "config/mysql-ca.p12",
  "MYSQL_TRUST_STORE_TYPE": "PKCS12",
  "MYSQL_TRUST_STORE_PASSWORD": "填写信任库密码"
}
```

Spigot 示例

```yaml
mysql:
  host: db.example.net
  tls:
    mode: VERIFY_IDENTITY
    trust-store: plugins/InvSyncSpigot/mysql-ca.p12
    trust-store-type: PKCS12
    trust-store-password: '填写信任库密码'
```

两台机器部署时，让两服都能解析并访问证书上的数据库域名。防火墙仅向游戏服所在的私网或固定出口 IP 开放 MySQL 端口。数据库端可启用 `require_secure_transport=ON`，拒绝未加密的远程连接。若 Fabric 与 MySQL 同机，也要为 Fabric 使用与证书匹配的连接地址。

`PREFERRED` 在数据库无法协商 TLS 时可能回退到明文。需要严格验证时，请在两端显式设置 `VERIFY_IDENTITY` 并重启。若使用私有 CA，部署前请通过可信渠道取得 CA 公共证书并核对指纹。


### TLS 双服实测

本地 MySQL 8.4.13 开启了 `require_secure_transport`。两台 26.3 测试服使用 `VERIFY_IDENTITY` 和自定义 PKCS12 信任库。在玩家在线期间，从 MySQL 会话状态确认两端均使用 `TLSv1.3` 和 `TLS_AES_256_GCM_SHA384`。Fabric → Spigot → Fabric 的背包、经验和进度同步通过，新玩家从 Spigot 首次写入后切到 Fabric 也通过。

测试证书的 SAN 只包含 `localhost`。两端分别改用 `127.0.0.1` 和替换为无关 CA 信任库，均在 TLS 握手阶段被拒绝。

## 同步选项与测试

| 配置键 | 默认值 | 同步内容 |
| --- | --- | --- |
| `SYNC_PLAYER_DATA` | `true` | 玩家 NBT，包括背包、末影箱、经验、血量、饥饿值和状态效果。各服独立的字段见[黑名单](src/main/resources/assets/invsync/blacklist) |
| `SYNC_ADVANCEMENTS` | `true` | 进度及达成的条件 |
| `SYNC_STATS` | `true` | 原版统计，例如跳跃次数 |

各服使用相同的同步开关。设为 `false` 会暂停对应类别的读写，保留数据库中的记录。使用模组物品或数据包进度时，各服需要有对应的物品或进度定义。

玩家首次加入且数据库中没有该 UUID 的记录时，从当前服的数据开始。InvSync 在进服时读取，并在服务器保存玩家数据时写入，包括退服时的保存。同一 UUID 同时只允许一个服务器持有会话。

验证时，用同一账号进入 A 服，放一件可辨识的物品进背包并正常退出，再进入 B 服确认物品和数量。也可以在 B 服修改数量，退出后回 A 服确认反向同步。若使用代理，检查所有后端收到的玩家 UUID 是否一致。

### MySQL 双服实测

使用发布页的 JAR 在本机运行两台独立的 Minecraft Java 26.3 服务端。两服均使用 Java 25 和 Fabric Loader 0.19.5，并连接同一个 MySQL 8.4.13 数据库。测试客户端使用同一个玩家 UUID 登录，每次正常退服后再进入另一台服务器。

| 切换方向 | 目标服背包 | 目标服经验 | 目标服进度 |
| --- | --- | --- | --- |
| A → B | 7 颗钻石 | 17 级，当前等级内 6 点 | `minecraft:story/mine_stone` 已完成 |
| B → A | 同一快捷栏槽位更新为 11 颗绿宝石 | 23 级，当前等级内 9 点 | `minecraft:story/upgrade_tools` 已完成，`minecraft:story/mine_stone` 已撤销 |

B 服首次接收该玩家时没有本地玩家数据或进度记录。两次退服后都在 MySQL 的 `InvSync_base` 表中查到了同一 UUID 的更新记录。背包、经验和进度双向同步均通过。

## 从 InvSync 2.3.3 迁移

1. 停止所有旧实例，备份数据库、配置和各服世界。SQLite 应在停服后备份数据库目录。MySQL 可导出原数据库。
2. 在各服安装新版。先生成新的 `config/invsync.json`，再停服，将原数据库的连接参数或 SQLite 目录和库名填入新配置。SQLite 路径填 `.db` 所在目录，`DATABASE_NAME` 填文件名去掉 `.db` 后的部分。
3. 在恢复对玩家开放前，先用一份数据库备份演练。新版继续读取 `InvSync_base`，并通过 Minecraft 的数据修复器升级未标注版本的 1.20.1 玩家 NBT 和进度。使用测试账号验证背包、统计和进度，再部署到其余实例。
4. 所有共用该库的实例一起切换。新版采用不同的会话锁，并会写入 26.3 格式。需要回退时，停服后恢复升级前的备份。

## Fabric 与 Spigot 混合端

混合端需要在两端使用 **Minecraft 26.3、Java 25 和 InvSync 2.3.6**。Fabric 服使用本项目的 Fabric JAR，Spigot 服使用 `invsync-spigot-2.3.6+26.3.jar`。

1. 在 Fabric 服的 `mods/` 安装新版，按上面的 MySQL 示例配置 `config/invsync.json`，开启 `SYNC_PLAYER_DATA` 和 `SYNC_ADVANCEMENTS`。
2. 在 Spigot 服的 `plugins/` 放入配套插件。启动一次生成 `plugins/InvSyncSpigot/config.yml`，然后停服填写数据库连接信息。
3. 两端使用同一 MySQL 实例、同一数据库名和一致的玩家 UUID。配置完成后重启两服。插件连不上 MySQL 或无法安全读取玩家数据时，会拒绝该玩家进入。

```yaml
mysql:
  host: db.internal
  port: 3306
  database: invsync
  username: invsync
  password: '填写实际密码'
```

混合端共享原版背包、快捷栏、盔甲、副手、经验和原版进度。Spigot 不参与末影箱、血量、饥饿值和统计等其他类别的同步，写回时会保留 Fabric 已存的其他字段。含有 Spigot 无法解析的物品时，插件会拒绝加载该背包。

玩家可以先在任一端进服。首次在 Spigot 产生的记录只包含混合端支持的玩家字段，Fabric 2.3.6 会把这些字段合并进自己的本地玩家数据。

### 混合端实测

已在本机用 Fabric Loader 0.19.5、Spigot 26.3 build 4664 和 MySQL 8.4.13 实测。两服连接同一个数据库，测试客户端用同一 UUID 退出一服后再登录另一服。

| 切换方向 | 目标服核对结果 |
| --- | --- |
| Fabric → Spigot | 7 颗钻石、17 级和当前等级内 6 点经验，`mine_stone` 已完成，头盔和副手盾牌已同步 |
| Spigot → Fabric | 11 颗绿宝石、23 级和当前等级内 9 点经验，`upgrade_tools` 已完成、`mine_stone` 已撤销，副手更新为火把 |
| 新玩家先进入 Spigot，再进入 Fabric | 13 块铁锭、12 级和当前等级内 4 点经验，`mine_stone` 已完成 |

往返测试也核对了 Fabric 的原有末影箱内容。

### 构建 Spigot 插件

使用 JDK 25。`spigotServerJar` 指向 26.3 的实际服务端类 JAR（包含 `net/minecraft/server/level/ServerPlayer.class`）。如果手里是 Mojang 或 Spigot 的启动包，先提取其中包含服务端类的内层 JAR。

```powershell
.\gradlew.bat -p spigot -PspigotServerJar=C:/path/to/server-26.3.jar build
```

Gradle 默认从 Spigot 仓库下载 API。也可以提供本地的 Spigot 26.3 API JAR，追加 `-PspigotApiJar=C:/path/to/spigot-api.jar`。插件产物在 `spigot/build/libs/`，MySQL 驱动已打包。

## 常见问题

| 症状 | 检查项 |
| --- | --- |
| 启动时提示模组不兼容 | 检查 Minecraft、Fabric Loader 和服务端进程实际使用的 Java 版本。检查 `mods/` 里是否还有旧版 InvSync |
| TLS 报 `Server identity verification failed` | 检查连接地址是否与证书 SAN 匹配。DNS 名要匹配 DNS SAN，用 IP 时要匹配 IP SAN |
| TLS 报 `Path does not chain with any of the trust anchors` | 核对信任库里是否导入了签发该服务器证书的 CA，检查服务器提供的中间证书链，以及路径、类型和密码 |
| 启动报 `Could not initialize InvSync` | 查看日志中的具体异常。检查 JSON 格式和 SQLite 目录写权限，或检查 MySQL 地址、端口、数据库名和账号权限 |
| 切服时提示数据加载失败，日志有 `active InvSync session` | 等前一个服务器完成退服保存后再连接。若仍出现，确认同一个 UUID 是否还在其他服务器在线 |
| 能进服，但数据没有同步 | 检查两服的玩家 UUID、数据库地址或 SQLite 绝对路径、数据库名和同步开关。用正常退服的方式重测，并查看两服日志 |
| 日志有 `Could not load InvSync data` 或 `Could not save InvSync data` | 查看后续异常定位连接或数据问题。修复后用测试账号再次验证读写，再开放切服 |

## 从源码构建

使用 **JDK 25**。仓库自带 Gradle 9.6.0 Wrapper，首次运行会下载 Gradle、构建依赖和 Minecraft 文件。

```powershell
.\gradlew.bat build
```

Linux 或 macOS：

```sh
bash ./gradlew build
```

Fabric 构建产物位于 `build/libs/`。本地参与 TLS 实测的 Fabric 2.3.6 JAR 由 JDK 25 编译和打包，Fabric 的完整 Gradle 构建曾因依赖下载停滞，仍待验证。Spigot 2.3.6 插件已用 Gradle 构建，并使用该产物完成[双服 TLS 实测](#tls-双服实测)。

## 上游与许可证

移植自 MrNavaStar 的 [InvSync](https://github.com/MrNavaStar/InvSync)，采用 [MIT 许可证](LICENSE)。也可以通过 [Ko-fi](https://ko-fi.com/mrnavastar) 支持原作者。
