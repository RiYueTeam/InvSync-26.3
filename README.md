[![Maintenance](https://img.shields.io/badge/Maintained%3F-yes-green.svg)](https://GitHub.com/Naereen/StrapDown.js/graphs/commit-activity)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg?style=flat-square)](http://makeapullrequest.com)
[![MIT license](https://img.shields.io/badge/License-MIT-blue.svg)](https://lbesson.mit-license.org/)

[<img alt="modrinth" height="40" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/modrinth_vector.svg">](https://modrinth.com/plugin/invsync)

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/G2G4DZF4D)

<img src="https://raw.githubusercontent.com/MrNavaStar/invSync/master/src/main/resources/assets/invsync/icon.png" width="300" height="300">

# InvSync

InvSync synchronizes player inventories, health, hunger, experience, status
effects, statistics, and advancements across Minecraft servers and worlds.
It supports SQLite and MySQL.

## Minecraft 26.3 (Java Edition)

Use Java 25 and Fabric Loader 0.19.5 or newer. Put the InvSync JAR in the
`mods` directory of each participating server. The JDBC drivers are bundled.

The first launch creates `config/invsync.json`. SQLite is the default and stores
`InvSync.db` under `config/invsync`. To share SQLite across servers on the same host, set
`SQLITE_DIRECTORY` to the same absolute path on each. For servers on different
hosts, select
`MYSQL` and point every server at the same existing MySQL database. Use the same
sync settings on all servers and make sure players have the same UUID on each.

To reuse an InvSync 2.3.3 database, back it up, stop all old instances, and copy
the old database settings into the new JSON config. InvSync continues to use
the `InvSync_base` table. Unversioned player NBT and advancements are treated as
Minecraft 1.20.1 data and upgraded with Minecraft's data fixer. Migrate all
participating servers together because the new version uses different session
locks and writes 26.3 data.

Build from source with Java 25 and `./gradlew build` (Windows:
`.\gradlew.bat build`). The JAR is generated under `build/libs`.

## Support the original author

You can support MrNavaStar by [buying them a coffee](https://ko-fi.com/mrnavastar).
