# 交接文档 — FishModLoader Forge 兼容

分支：`classloader-patch`

状态：原 forge-compat 计划已完成；下一阶段进入真实 Forge mod 兼容矩阵。

## 文档入口

- [`PLAN.md`](PLAN.md)：已完成计划、最终成果、当前验证基线和关键设计。
- [`PLAN-NEXT.md`](PLAN-NEXT.md)：下一阶段真实 Forge mod 兼容矩阵；尚未宣称任何第三方 mod 已验证。
- [`docs/forge-compat-history.md`](docs/forge-compat-history.md)：历史错误演进、命名空间、classloader、AW/Mixin 排障记录。

## 环境前提

- Windows；仓库脚本也可从 Git Bash 执行。
- JDK 17（Zulu 17 已验证）。
- 自备 `libs/1.6.4-MITE.jar`，内容为 official/混淆命名空间的 MITE-HDS jar。该文件被 `.gitignore` 忽略，不入库。
- 实际运行使用 `build/libs/FishModLoader-*-all-intermediary.jar`，不要使用 named 命名空间的 `*-all.jar`。
- 客户端需要完整的 1.6.4 libraries/natives/assets。`1.6.4-MITE.json` 声明的 LWJGL `2.9.1-nightly-20130708-debug3` 本地通常不可得；实测以 LWJGL `2.9.0` 替代可启动到主菜单。

## 标准验证命令

### 日常快速验证

```bash
./gradlew verifyForgeCompatibilityQuick
```

该聚合任务执行构建、静态 Mixin 目标/注入检查、全游戏 jar JVM 链接验证和 Forge AT 行为探针；**不启动真实服务端**。

### 真实服务端集成验证

```bash
./gradlew verifyForgeCompatibilityIntegration
```

该任务依赖 quick 基线，然后构建真实 Forge AT 夹具、启动服务端、检查规则加载与 AT 应用、等待生命周期断言和 `Done`，发送 `stop` 并要求退出码 0。完整日志：

```text
build/forge-at-server-e2e/server-e2e.log
```

### 完整入口

```bash
./gradlew verifyForgeCompatibility
```

当前完整入口依赖 integration，因此也覆盖 quick。它是稳定的顶层入口；未来客户端或真实 mod 自动化应追加到该入口。Gradle 会按任务图去重共享的 `buildJar`、映射和编译前置，不需要手工串联多个命令。

### 查看任务图但不执行

```bash
./gradlew verifyForgeCompatibilityQuick --dry-run
./gradlew verifyForgeCompatibilityIntegration --dry-run
```

## 分项排查命令

```bash
./gradlew buildJar
./gradlew verifyOverwrites
./gradlew verifyInjections
./gradlew verifyGameJar
./gradlew probeForgeAccessTransformer
./gradlew verifyForgeAccessTransformerServer
```

`verifyGameJar` 支持：

```bash
./gradlew verifyGameJar -PgameJar=<jar>
./gradlew verifyGameJar -PgameJar=<jar> -Ptrace=EntityBoneLord
./gradlew verifyGameJar -PgameJar=<jar> -PawFile=<aw>
```

`verifyOverwrites` / `verifyInjections` 默认消费 `build/tmp/mite-named.jar`。旧的 `tools/verify_overwrites.sh` 可辅助排查，但当前基线与聚合任务使用 Java 验证器。

## 当前决定性基线

已确认的最近基线：

- `buildJar`：BUILD SUCCESSFUL。
- `verifyGameJar`：Linked OK 4559 / DEFECTS 0。
- `verifyOverwrites`：340 项全部解析，缺失 0。
- `verifyInjections`：缺陷 0。
- `probeForgeAccessTransformer`：在 named `runtimeClasspath` 上验证 AT 解析、映射、发现与直接 ASM 行为，通过；真实 intermediary classloader 链由服务端 E2E 覆盖。
- `verifyForgeAccessTransformerServer`：
  - 读取夹具 manifest `FMLAT` 与 `META-INF/fixture_at.cfg`；
  - 日志出现 `Forge AT rules loaded:`；
  - 日志出现 `[AT] Applied`；
  - 日志出现 `[Forge AT fixture] ASSERTION PASSED`；
  - 服务端出现 `Done (`，随后正常停服。
- 客户端已人工启动到主菜单，无 `VerifyError` 和崩溃报告；客户端未纳入本轮自动聚合。

### 已知基线风险

真实 AT E2E 虽然通过，但其服务端日志仍含若干非致命 `InvalidMixinException` warning（例如 refmap 后的 intermediary `@Shadow`/`@Overwrite` 未命中）。当前集成任务只断言 AT、生命周期、`Done` 和正常退出，不会因这些 warning 失败；因此不能把 `verifyOverwrites` 在 named jar 上的“缺失 0”解释为运行时所有 Mixin 均已成功应用。下一阶段开始真实 mod 判定前，应分类这些 warning，并选择修复、禁用无效 patch 或建立有依据的允许清单。

## 最重要的维护约束

1. **三层命名空间**：原始 jar 是 official，运行时是 intermediary，源码/AW 是 named。`named.tiny` 的成员描述符带 official 形态，且其 vanilla API 信息不能替代 MITE 实际 jar。
2. **运行产物**：只有 `*-all-intermediary.jar` 含运行时所需重映射、refmap 和 intermediary AW。
3. **惰性链接**：`defineClass` 不足以验证字节码；`verifyGameJar` 会通过反射强制链接。
4. **classloader 单例边界**：modfixer/AT/lifecycle 状态不可在 App 与 Knot 之间无意分裂。修改 blocker 或 whitelist 后必须重跑真实服务端 integration。
5. **Mixin 注解以真实目标为准**：目标不存在时 `@Overwrite`/`@Shadow` 会硬失败；新增 Forge API 才使用 `@Unique`。
6. **不要把夹具当真实 mod 矩阵**：仓库 AT 夹具证明基础链路，不代表任何第三方 mod 已兼容。

## 下一步（下个对话直接执行）

真实 Lucky Block 的 namespace、构造、注册和三段生命周期现已走通，但最终人工验收仍为 **FAIL**：创造背包中是紫黑缺失纹理，放置后敲掉只掉落本体，没有执行幸运方块行为。

详细证据、当前高风险改动和下一步排查顺序见：

- [`docs/lucky-block-compat-handoff.md`](docs/lucky-block-compat-handoff.md)

下个对话不要继续随机换 mod，也不要把生命周期日志当成功。先修复：

1. Forge 资源 namespace / icon registration，日志当前为 `Missing resource: textures/blocks/MISSING_ICON_TILE_850_blockLucky.png`，而 jar 内实际资源是 `assets/lucky/textures/blocks/blockLucky.png`；
2. legacy Forge 方块破坏回调到 MITE 实际 harvest/break 路径的 bridge，使 `BlockLucky` 的随机掉落逻辑真正执行并替代默认本体掉落。

必须先补仓库自产的纹理与破坏回调 fixture，再改生产兼容层；清 remap 缓存后跑真实客户端人工验收。第三方 Lucky Block jar不得提交。最新真实 mod 专项修复尚未得到可信 S3AI Claude 复审，完成后必须补审。
