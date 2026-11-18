# InvSync for Minecraft 26.3

InvSync 可以在不同的 Minecraft 世界或服务器之间同步同一玩家的背包、末影箱、经验、血量、饥饿值、状态效果、原版统计和进度。玩家按 **UUID** 匹配。坐标、朝向、维度和重生点由各服独立管理。

## 安装

1. 使用 **Minecraft Java 版 26.3**、**Java 25** 和 **Fabric Loader 0.19.5 或更新版本**。可以在启动服务器的环境里运行 `java -version` 检查 Java 版本。
2. 从 [Releases](https://github.com/Yarra2000/InvSync-26.3/releases/tag/v2.3.4-26.3) 下载 `invsync-2.3.4+26.3.jar`，放进每台参与同步的服务器的 `mods/` 目录。单人世界使用对应 Fabric 实例的 `mods/` 目录。
3. 启动一次以生成 `config/invsync.json`，然后停服并按下表选择数据库。修改配置后重启。普通联机客户端可以直接连接，数据库驱动已随 JAR 打包。

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

## 同步选项与测试

| 配置键 | 默认值 | 同步内容 |
| --- | --- | --- |
| `SYNC_PLAYER_DATA` | `true` | 玩家 NBT，包括背包、末影箱、经验、血量、饥饿值和状态效果。各服独立的字段见[黑名单](src/main/resources/assets/invsync/blacklist) |
| `SYNC_ADVANCEMENTS` | `true` | 进度及达成的条件 |
| `SYNC_STATS` | `true` | 原版统计，例如跳跃次数 |

各服使用相同的同步开关。设为 `false` 会暂停对应类别的读写，保留数据库中的记录。使用模组物品或数据包进度时，各服需要有对应的物品或进度定义。

玩家首次加入且数据库中没有该 UUID 的记录时，从当前服的数据开始。InvSync 在进服时读取，并在服务器保存玩家数据时写入，包括退服时的保存。同一 UUID 同时只允许一个服务器持有会话。

验证时，用同一账号进入 A 服，放一件可辨识的物品进背包并正常退出，再进入 B 服确认物品和数量。也可以在 B 服修改数量，退出后回 A 服确认反向同步。若使用代理，检查所有后端收到的玩家 UUID 是否一致。

## 从 InvSync 2.3.3 迁移

1. 停止所有旧实例，备份数据库、配置和各服世界。SQLite 应在停服后备份数据库目录。MySQL 可导出原数据库。
2. 在各服安装新版。先生成新的 `config/invsync.json`，再停服，将原数据库的连接参数或 SQLite 目录和库名填入新配置。SQLite 路径填 `.db` 所在目录，`DATABASE_NAME` 填文件名去掉 `.db` 后的部分。
3. 在恢复对玩家开放前，先用一份数据库备份演练。新版继续读取 `InvSync_base`，并通过 Minecraft 的数据修复器升级未标注版本的 1.20.1 玩家 NBT 和进度。使用测试账号验证背包、统计和进度，再部署到其余实例。
4. 所有共用该库的实例一起切换。新版采用不同的会话锁，并会写入 26.3 格式。需要回退时，停服后恢复升级前的备份。

## 常见问题

| 症状 | 检查项 |
| --- | --- |
| 启动时提示模组不兼容 | 检查 Minecraft、Fabric Loader 和服务端进程实际使用的 Java 版本。检查 `mods/` 里是否还有旧版 InvSync |
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

构建产物位于 `build/libs/`。发布页的 JAR 由 JDK 25 编译和打包。已在 Minecraft 26.3 和 Fabric Loader 0.19.5 上验证 SQLite 保存、读取、实例间交接和 1.20.1 样例数据迁移。MySQL 仍待联机验证。本次 Gradle 构建因依赖下载停滞，完整的 Gradle 构建流程仍待验证。

## 上游与许可证

移植自 MrNavaStar 的 [InvSync](https://github.com/MrNavaStar/InvSync)，采用 [MIT 许可证](LICENSE)。也可以通过 [Ko-fi](https://ko-fi.com/mrnavastar) 支持原作者。
