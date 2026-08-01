# FishModLoader Forge 兼容计划（已完成）

分支：`classloader-patch`（基于原 `forge-compat` 工作）

状态：**原 forge-compat 计划已完成**

下一阶段：见 [`PLAN-NEXT.md`](PLAN-NEXT.md)

## 目标

让 FishModLoader 在不依赖 ForgeGradle binpatch 流水线的前提下，通过 Mixin、运行时类加载与转换机制加载 Forge 1.6.4 单 jar mod，并建立可重复的静态、探针和真实服务端验证基线。

## 最终成果

- 打通游戏 jar 的 `official → intermediary → named` 构建链路，编译类路径与运行时命名空间各自正确。
- loader 可成功编译并生成可运行的 `*-all-intermediary.jar`；构建期生成并嵌入 refmap 和 intermediary AccessWidener。
- forge_compat Mixin 的 `@Shadow`、`@Overwrite`、注入目标和描述符已有 named-jar 静态校验；该检查当前报告缺失 0。
- 完成 launchwrapper 兼容桥、Knot/App classloader 隔离与 Forge 生命周期派发，可发现、构造并驱动 legacy Forge mod。
- Forge Access Transformer 改为运行时 ASM 应用，支持 manifest `FMLAT`、AT 发现、访问级别及 final 标志变更、official → intermediary 映射和明确的失败告警。
- 建立真实 Forge AT 服务端夹具：在 `FMLServerStartedEvent` 首次加载目标类，断言 AT 生效、生命周期执行、服务端到达 `Done`，随后自动停服。
- 服务端已验证完整启动；客户端已人工验证到主菜单。后续已开始真实第三方 mod 矩阵，当前状态以 `PLAN-NEXT.md` 和 `docs/lucky-block-compat-handoff.md` 为准。

## 当前验证基线

环境前提：

- JDK 17（Zulu 17 已验证）。
- `libs/1.6.4-MITE.jar` 为 official/混淆命名空间的 MITE-HDS jar；该文件被忽略，不入库。
- 运行产物必须使用 `build/libs/*-all-intermediary.jar`，不能使用 `*-all.jar`。

聚合入口：

```bash
./gradlew verifyForgeCompatibilityQuick
./gradlew verifyForgeCompatibilityIntegration
# 稳定顶层入口（当前等同 integration；未来客户端/真实 mod 自动化加在此入口）
./gradlew verifyForgeCompatibility
```

基线证据：

| 验证 | 已确认结果 |
|---|---|
| `buildJar` | BUILD SUCCESSFUL |
| `verifyGameJar` | Linked OK 4559 / DEFECTS 0 |
| `verifyOverwrites` | Java 验证器按当前源码扫描全部 `@Shadow`/`@Overwrite` 并校验完整描述符：340 项，缺失 0；与旧 shell 脚本的历史数字口径不同 |
| `verifyInjections` | 缺陷 0 |
| `probeForgeAccessTransformer` | 在 named `runtimeClasspath` 上验证 AT 解析、映射决策、发现及直接 ASM 行为；不代表 intermediary 运行链，后者由服务端 E2E 覆盖 |
| `verifyForgeAccessTransformerServer` | AT 规则加载、`[AT] Applied`、夹具断言、生命周期和服务端 `Done` 均通过，正常停服 |
| 客户端人工冒烟 | 到达主菜单，无 `VerifyError`、无崩溃报告 |

已知基线噪声/风险：真实服务端日志仍包含若干非致命 `InvalidMixinException` warning；当前 AT E2E 不以这些 warning 作为失败条件，因此“服务端 E2E 通过”和“所有运行时 Mixin 均成功应用”不是同一结论。静态验证在 named jar 上报告 0 缺失，但不能证明 refmap 后的 intermediary 运行时目标全部命中。进入真实 mod 矩阵前应先分类这些 warning，并决定修复、禁用无效 patch 或建立允许清单。

真实服务端 E2E 日志保存于：
`build/forge-at-server-e2e/server-e2e.log`。

## 关键设计

### 命名空间与构建链路

```text
libs/1.6.4-MITE.jar (official)
  → build/tmp/mite-intermediary.jar
  → build/tmp/mite-named.jar
  → build/tmp/widen.jar                 （源码编译类路径）

shadowJar (named loader)
  → remapLoaderJar
  → 嵌入 refmap 与 intermediary AW
  → *-all-intermediary.jar              （运行产物）
```

`intermediary.tiny` 为 official → intermediary；`named.tiny` 为 intermediary → named，但其成员描述符带有 official 形态，构建工具会做专门翻译。`named.tiny` 来源于原版 1.6.4，不能代替 MITE 实际 API；Mixin 目标判断以真实 MITE jar 为准。

### Mixin 而非源码 patch

原 ForgeGradle 风格源码 patch 流水线不再作为实现路径。Minecraft/MITE 改造由 `net.xiaoyu233.fml.reload.transform` 下的 Mixin 完成，并由 loader 递归扫描注册，不需要手工维护 mixin 列表。

### 类加载边界

- AppClassLoader 持有 loader 与唯一的 launchwrapper 定义。
- KnotClassLoader/DynamicURLClassLoader 加载和转换游戏及 mod 类。
- `LaunchwrapperBridge` 为 Forge API 提供 `Launch.classLoader` 兼容入口，但实际委托 Knot。
- discovery、生命周期和 AT 注册必须在约定的 classloader 侧执行，避免两份静态状态表分裂。

### AT 与验证策略

AT 不再预翻译成 named AccessWidener，而是在类首次加载时由 `FMLClassTransformer` 修改 ASM access flags。静态扫描不能覆盖惰性 JVM 链接和 classloader 状态，因此验证分层为：构建/静态目标检查、JVM 链接探针、AT 行为探针、真实服务端 E2E。

## 后续入口

- 下一阶段真实 Forge mod 兼容矩阵：[`PLAN-NEXT.md`](PLAN-NEXT.md)
- 当前操作、命令和环境交接：[`HANDOFF.md`](HANDOFF.md)
- 原计划的错误演进、排障依据与维护陷阱：[`docs/forge-compat-history.md`](docs/forge-compat-history.md)

本文件不再维护“剩余 N 条错误”式历史快照；当前状态只以上述可执行验证为准。
