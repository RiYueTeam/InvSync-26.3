# InvSync for Minecraft 26.3

InvSync 可以在不同的 Minecraft 世界或服务器之间同步同一玩家的背包、末影箱、经验、血量、饥饿值、状态效果、原版统计和进度。玩家按 **UUID** 匹配。坐标、朝向、维度和重生点由各服独立管理。

## Linux 从零一键启动 MySQL

在准备运行数据库的 Linux 主机上，先确认本机 Docker Engine 正在运行，当前用户能执行 `docker info`，并且 `openssl`、`curl`、`sha256sum` 和 JDK 25 在 `PATH` 中。把 `bootstrap-mysql.sh` 与 `SetupMySql.java` 一起放进 `scripts/`，然后在它的上一级目录运行。

```bash
bash scripts/bootstrap-mysql.sh
```

脚本会从 Maven Central 下载固定版本的 MySQL Connector/J 9.6.0，核对 SHA-256 后再启动 MySQL。数据库机器只需要这两个脚本。Fabric 和 Spigot 的 JAR 各自装在对应游戏服上。

| 提示 | 两台不同机器的填写示例 |
| --- | --- |
| 数据库监听的本机 IPv4 | `10.0.0.5`，这必须是数据库主机已经拥有的地址 |
| 两服连接 MySQL 的域名或 IPv4 | `10.0.0.5`，或两服均能解析到该地址的私网域名 |
| Fabric 来源 IPv4 | `10.0.0.5`，例如 Fabric 与数据库同机且通过该地址连接 |
| Spigot 来源 IPv4 | `10.0.0.6`，填数据库实际看到的来源地址 |
| MySQL 端口 | `3306`，端口已被占用时选一个空闲端口 |

脚本会自动启动 MySQL 8.4、签发私有 CA 和数据库证书、强制 TLS、创建 `InvSync` 库和 `invsync_user`，最后打印两份配置的目录。把输出的 `Fabric/` 和 `Spigot/` **内部文件**分别复制到对应服务端根目录，再安装相应 JAR 并启动。连接配置使用 `VERIFY_IDENTITY`。

跨机器时，在数据库主机上只向两台游戏服的实际来源开放该 MySQL 端口。根账号供本机初始化使用，游戏服使用按来源 IPv4 限定的同步账号。保留脚本打印的数据库工作目录和 Docker 数据卷，工作目录包含服务端证书、CA 私钥和管理密码。给游戏服复制的文件限于输出的 `Fabric/`、`Spigot/` 两个配置目录。

脚本每次运行都会创建新容器和新数据卷。已有数据库继续使用下方的配置工具。服务端启动后，可用脚本打印的容器名查看日志或重启容器。

**真实 Linux Docker 实测通过。** 使用 MySQL 8.4.9 在独立回环端口完成容器初始化、自动建库及双服配置生成。生成的两份配置均通过 `VERIFY_IDENTITY` 建立 TLS 1.3 连接，并验证了用 Fabric 配置写入的测试记录可由 Spigot 配置从同一库读出。2.3.8 的两个配置脚本可从[对应标签的 scripts 目录](https://github.com/RiYueTeam/InvSync-26.3/tree/v2.3.8-26.3/scripts) 下载。

## 一键建库和生成配置

先准备一台已启动 TLS 的 MySQL，以及有建库、创建用户和授权权限的管理账号。脚本用 Fabric JAR 里的 JDBC 驱动，运行时只需要另备 **JDK 25**。

在项目目录打开终端，运行一条命令。

```powershell
java scripts/SetupMySql.java build/libs/invsync-2.3.8+26.3.jar
```

如果 Fabric JAR 在别处，把命令最后一段换成它的路径。脚本会依次询问以下信息。

| 输入项 | 怎么填 |
| --- | --- |
| MySQL 域名和端口 | 域名填证书上的名称，端口默认 `3306` |
| 库名和新同步账号 | 默认 `InvSync` 和 `invsync_user`。同步密码由脚本随机生成 |
| Fabric 和 Spigot 的来源地址 | 填 MySQL 看到的两台游戏服的来源 IP 或主机名。两端来源相同，第二项直接回车 |
| 私有 CA | 证书已被 Java 信任时直接回车。使用私有 CA 时填 MySQL 提供的 CA PEM 文件，脚本会生成两端的 PKCS12 信任库 |
| 管理账号和密码 | 输入有建库、创建用户和授权权限的账号。密码输入时隐藏 |

成功后会显示一个 `invsync-config-*` 目录。把其中 `Fabric/` 和 `Spigot/` **里面的文件**分别复制到两台服务端根目录，保留子目录结构。停服复制后再启动，用同一 UUID 试切服。配置文件包含生成的同步密码，请只发给对应服务器的管理员。

脚本和两端配置都使用 `VERIFY_IDENTITY`。新建的同步账号要求 TLS，来源限制为填入的地址，权限只覆盖对应库的 `InvSync_base` 表。如果库已存在，脚本会使用该库并保留已有数据。若同名来源账号已存在，换一个新的同步账号名再运行。

已用脚本生成的配置和信任库启动本地 Fabric 与 Spigot 26.3，确认通过同一个 MySQL 8.4.13 的 TLS 1.3 完成双向切服。测试还确认了同名来源账号会保留，证书主机名不匹配时建库流程会拒绝连接。

## 三步配置 Fabric + Spigot

已经有 MySQL 时，按这三步配置。两端都使用 Minecraft 26.3、Java 25 和 InvSync 2.3.8。

1. **装对应的 JAR。** Fabric 的 `invsync-2.3.8+26.3.jar` 放进 `mods/`。Spigot 的 `invsync-spigot-2.3.8+26.3.jar` 放进 `plugins/`。从旧版升级前，先停服备份两个世界和数据库。
2. **照抄下方两份配置。** 把 `db.example.net`、`3306`、`invsync`、`invsync_user` 和 `换成数据库密码` 改成实际值。域名要与数据库证书匹配，库名要在两端完全一致。数据库管理员可先建好该库，账号需要在该库拥有建表、查询、插入和更新权限。
3. **启动并切服验收。** 用同一账号在 A 服把一件物品放入末影箱，正常退出后进 B 服确认。两端应识别为同一个玩家 UUID。

**Fabric** 的文件是 `config/invsync.json`。

```json
{
  "DATABASE_TYPE": "MYSQL",
  "DATABASE_NAME": "invsync",
  "MYSQL_ADDRESS": "db.example.net",
  "MYSQL_PORT": "3306",
  "MYSQL_USERNAME": "invsync_user",
  "MYSQL_PASSWORD": "换成数据库密码",
  "MYSQL_SSL_MODE": "VERIFY_IDENTITY",
  "SYNC_PLAYER_DATA": true,
  "SYNC_ADVANCEMENTS": true
}
```

**Spigot** 的文件是 `plugins/InvSyncSpigot/config.yml`。

```yaml
mysql:
  host: db.example.net
  port: 3306
  database: invsync
  username: invsync_user
  password: '换成数据库密码'
  tls:
    mode: VERIFY_IDENTITY
```

数据库证书如果受 Java 默认信任库信任，上面两份配置就够了。自签证书或私有 CA 需要先按 [MySQL TLS](#mysql-tls) 导入 CA，再填写两端的信任库路径。两台游戏服都要能访问数据库端口。

## 安装

1. 使用 **Minecraft Java 版 26.3**、**Java 25** 和 **Fabric Loader 0.19.5 或更新版本**。可以在启动服务器的环境里运行 `java -version` 检查 Java 版本。
2. 从 [2.3.8 发布页](https://github.com/RiYueTeam/InvSync-26.3/releases/tag/v2.3.8-26.3) 下载对应的 JAR，或使用下表中的本地构建产物。单人世界使用对应 Fabric 实例的 `mods/` 目录。
3. 首次启动后按下表找到配置文件，停服修改配置，再重启。普通联机客户端可以直接连接，数据库驱动已随 JAR 打包。

| 服务端 | 本地 JAR | 安装目录 | 配置文件 |
| --- | --- | --- | --- |
| Fabric | `build/libs/invsync-2.3.8+26.3.jar` | `mods/` | `config/invsync.json` |
| Spigot | `spigot/build/libs/invsync-spigot-2.3.8+26.3.jar` | `plugins/` | `plugins/InvSyncSpigot/config.yml` |

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

Fabric 和 Spigot 从 2.3.6 起都支持 MySQL TLS。跨机器部署时，使用 `VERIFY_IDENTITY` 同时校验证书的信任链和数据库主机名。连接地址应与服务器证书 SAN 中的 DNS 名称匹配。用 IP 连接时，证书 SAN 需要包含对应的 IP 地址。

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

## MySQL 断线时如何游玩和切服

首次启动时，两端都需要成功连接同一个 MySQL。玩家开始使用共享背包之前，当前服会取得该角色的独占记录。已绑定的两个登录 UUID 使用同一份共享存档。MySQL 断线或另一台游戏服关闭，都不会自动解除角色占用。

| 情况 | 玩家可以做什么 |
| --- | --- |
| 正在原服游玩，MySQL 断线 | 继续游玩。自动保存和退服时，共享数据写入原服本地。 |
| 断线期间退出后想回原服 | 完成原有登录认证后，只要本地有该角色的占用及存档，且尚未向数据库发起释放，就可以重登。如果交接结果待确认，则等待数据库恢复。 |
| 断线期间想进入另一服 | 需要等原服提交最终存档。目标服不会使用自己留下的旧背包放行。 |
| 一台游戏服关闭，另一服能访问 MySQL | 另一服正常接待玩家。已完成交接的角色可以进入，仍由已关闭实例占用的角色要等原实例启动并完成补传。 |
| MySQL 恢复 | 在线玩家继续使用当前存档。退服后，原服在后台补传，在同一笔事务中保存最终数据并解除占用。交接完成后即可登录另一服。 |

本地在 Fabric 26.3、Spigot 26.3 和开启 TLS 主机名校验的 MySQL 8.4.13 上实测了两个方向。停止 MySQL 后，玩家能修改物品、退出并在原服重登。原服在断库期间正常重启后仍能恢复物品。原服关闭时，另一服能接待独立玩家。原服重新上线并补传后，目标服分别读到了 3 颗钻石和 3 颗绿宝石。另一次测试单独阻断了 Fabric 的数据库账号。使用另一个已绑定 UUID 的玩家在 Spigot 上被拒绝，恢复后读到了末影箱内最新的 9 个金锭。

升级时先让两端玩家退出，备份并停止旧实例，再一起更换两个 JAR。旧版依赖数据库连接锁，不能与采用持久占用记录的版本混跑。新版配置工具会授予同步账号对 `InvSync_sessions` 表的 `CREATE, SELECT, INSERT, UPDATE` 权限。沿用旧账号时，需要管理员补上这张表的权限。

每台游戏服的工作目录下都会生成 `invsync-pending/`。该目录保存该实例的占用凭据和未交接存档。Docker 部署应将它放在各实例独立的持久卷中。原服在断库期间正常关闭后，从原工作目录重启即可继续恢复。此机制要求数据库和本地待提交记录保持连续，不能用旧备份覆盖其中一侧或手动清空占用表。

## 从 InvSync 2.3.3 迁移

1. 停止所有旧实例，备份数据库、配置和各服世界。SQLite 应在停服后备份数据库目录。MySQL 可导出原数据库。
2. 在各服安装新版。先生成新的 `config/invsync.json`，再停服，将原数据库的连接参数或 SQLite 目录和库名填入新配置。SQLite 路径填 `.db` 所在目录，`DATABASE_NAME` 填文件名去掉 `.db` 后的部分。
3. 在恢复对玩家开放前，先用一份数据库备份演练。新版继续读取 `InvSync_base`，并通过 Minecraft 的数据修复器升级未标注版本的 1.20.1 玩家 NBT 和进度。使用测试账号验证背包、统计和进度，再部署到其余实例。
4. 所有共用该库的实例一起切换。新版采用不同的会话锁，并会写入 26.3 格式。需要回退时，停服后恢复升级前的备份。

## Fabric 与 Spigot 混合端

混合端需要在两端使用 **Minecraft 26.3、Java 25 和 InvSync 2.3.8**。Fabric 服使用本项目的 Fabric JAR，Spigot 服使用 `invsync-spigot-2.3.8+26.3.jar`。

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

混合端共享原版背包、快捷栏、盔甲、副手、末影箱、经验和原版进度。末影箱按槽位整体同步，清空后切服也会同步空箱。血量、饥饿值和统计等字段由 Fabric 端管理，Spigot 写回时会保留数据库中已有的这些字段。背包或末影箱含有 Spigot 无法解析的物品时，插件会拒绝加载该玩家的共享数据。

玩家可以先在任一端进服。首次在 Spigot 产生的记录只包含混合端支持的玩家字段，Fabric 2.3.7 会把这些字段合并进自己的本地玩家数据。

从 2.3.6 升级前，停服备份两端的世界目录和 MySQL。如果两端原有末影箱不同，先决定以哪个版本为准。完整共享记录里的末影箱内容会在进服时应用到本地，两边的内容不会合并。历史 Spigot 部分记录缺少末影箱字段时，2.3.7 会保留玩家当前所在服的末影箱，直到新版首次保存该玩家。

### 2.3.7 混合端 TLS 实测

已在本机用 Fabric Loader 0.19.5、Spigot 26.3 build 4664 和 MySQL 8.4.13 实测。两服通过 `VERIFY_IDENTITY` 连接同一个数据库，MySQL 会话状态确认两端使用 TLS 1.3。测试客户端用同一 UUID 退出一服后再登录另一服。

| 切换方向 | 目标服核对结果 |
| --- | --- |
| Fabric → Spigot | 7 颗钻石、17 级和当前等级内 6 点经验，`mine_stone` 已完成，头盔和副手盾牌已同步 |
| Spigot → Fabric | 11 颗绿宝石、23 级和当前等级内 9 点经验，`upgrade_tools` 已完成、`mine_stone` 已撤销，副手更新为火把，末影箱第 0 槽更新为 2 个铜锭，第 26 槽有 3 个苹果 |
| 新玩家先进入 Spigot，再进入 Fabric | 13 块铁锭、12 级和当前等级内 4 点经验，`mine_stone` 已完成，末影箱第 5 槽有 6 个紫水晶碎片 |

分别在 Fabric 和 Spigot 清空末影箱后切服，目标服都显示为空。对缺少 `EnderItems` 的历史 Spigot 部分记录，两端也都验证了首次读取会保留玩家原有的本地末影箱。槽位编号从 0 开始。

### 用登录插件确认身份并绑定两个 UUID

玩家可在游戏中输入 `/invsync help` 或 `/invsync` 查看绑定步骤。

适用于两个服务器使用同一个 MySQL、同名角色有不同 UUID 的情况。两个服务器都要启用认证回调，并在登录插件里设置成功后的控制台命令。Fabric 在 `config/invsync.json` 中设置 `"REQUIRE_AUTH_CALLBACK": true` 和 `"SERVER_ID": "fabric"`。Spigot 在 `plugins/InvSyncSpigot/config.yml` 顶层设置 `require-auth-callback: true` 和 `server-id: spigot`。每个服务器使用独立、固定的标识。默认关闭此模式，沿用原来的进服同步。

配置登录插件在当前玩家已经进入服务器、密码登录或自动登录成功后，立即以**服务器控制台**执行 `invsync authenticated 玩家名`。把“玩家名”替换为该插件提供的玩家名占位符。认证前由登录插件限制玩家移动和操作本地背包，InvSync 等到回调后才读取共享库。请先在两个入口分别验证登录、自动登录和退出重连。

1. 登录第一台服务器，执行 `/invsync code` 获取五分钟有效的一次性码。
2. 登录第二台服务器，以另一个同名角色执行 `/invsync redeem 绑定码`。核对提示中的服务器及完整 UUID。
3. 先整理另一侧的背包和末影箱。`/invsync keep here` 选当前角色的共享存档，`/invsync keep other` 选发码角色的共享存档。`/invsync cancel` 放弃选择。若不认识另一角色，请联系管理员。
4. 双方退出并重新登录。两个登录 UUID 从此读取和保存到选中的 UUID。旧 UUID 的原始数据库记录保留。保留选择面向尚未绑定的两个 UUID，不支持覆盖既有的绑定。

现有数据库需要让两端的 MySQL 账号对 `InvSync_binding_codes` 拥有 CREATE、SELECT、INSERT、UPDATE、DELETE 权限，对 `InvSync_bound_roles` 拥有 CREATE、SELECT、INSERT 权限。新的一键配置会设置这些授权。功能启用后会自动创建表。

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

Fabric 构建产物位于 `build/libs/`。本地参与 TLS 实测的 Fabric 2.3.7 JAR 由 JDK 25 编译和打包，Fabric 的完整 Gradle 构建曾因依赖下载停滞，仍待验证。Spigot 2.3.7 插件已用 Gradle 构建，并使用该产物完成混合端 TLS 实测。

## 上游与许可证

移植自 MrNavaStar 的 [InvSync](https://github.com/MrNavaStar/InvSync)，采用 [MIT 许可证](LICENSE)。也可以通过 [Ko-fi](https://ko-fi.com/mrnavastar) 支持原作者。
