# Legacy Forge 发布 Mod official → intermediary 重映射实施计划

状态：**待下个对话实施**
优先级：P0 blocker
范围：只修复 legacy Forge 发布 jar 的运行时命名空间适配；不得将第三方 mod jar 提交到仓库。

## 1. 问题陈述与现有证据

当前客户端可以进入主菜单和存档，但 Lucky Block 没有任何内容，因为它被扫描到后在构造阶段失败：

```text
Discovered Forge mod: lucky-block-forge-1.6.4-1.0.jar
Constructing Forge mods (side=CLIENT, loader=KnotClassLoader)
Failed to construct Forge mod lucky
java.lang.NoClassDefFoundError: aqz
Forge mod construction complete: 0 active mod(s)
```

证据位置：

- `run/logs/latest.log`：最新真实客户端日志；
- 本地样本 `run/mods/lucky-block-forge-1.6.4-1.0.jar`，SHA-256 为 `97548719a2370da47b4c078911b14a566e1e08066eb8b26ec4da1d4c68d84be7`；
- `mod/lucky/Lucky.class` 含字段描述符 `Laqz;`；
- `mod/lucky/BlockLucky.class` 继承 `aqz`；
- `src/main/resources/intermediary.tiny` 映射 `aqz → net/minecraft/block/Block`。

运行时游戏 jar 已是 intermediary，只提供 `net.minecraft.block.Block`。现有 `ForgeSrgModRemapper` 读取映射后返回 identity，导致 official 类、成员和描述符原样进入 JVM。此前“Forge 发布物已是 SRG/intermediary”的结论已被真实样本推翻。

**完成标准不是“能进入存档”，而是 mod 成功构造、进入 active list、生命周期执行且存在可观察功能。**

## 2. 已批准的架构决策

### 2.1 使用 JAR 级预重映射，不继续按类即时重映射

数据流固定为：

```text
原始 Forge jar（只用于发现、来源和原始 AT）
  → 命名空间判定
  → TinyRemapper official → intermediary
  → 原子写入游戏目录缓存
  → 只把 remapped jar 加入 Knot
  → FMLClassTransformer / Mixin / external coremod transformers
  → defineClass
```

理由：TinyRemapper 可以一次性处理完整类图、owner、字段、方法、描述符、签名、注解和继承成员解析；结果可扫描、缓存和复现。逐类 ASM remap 难以可靠处理继承成员，且当前钩子失败时返回原字节码，违背 fail-closed。

### 2.2 原始 jar 与运行时 jar 必须同时建模

`ForgeModDiscoverer.DiscoveredForgeMod` 应区分：

- `sourceJarPath`：用户提供的原始 jar，用于 provenance、SHA-256、原始 manifest/mcmod.info/AT 发现；
- `runtimeJarPath`：重映射后实际加入 Knot 的 jar，用于类和 coreplugin 加载。

不要用一个含义模糊的 `jarPath` 同时承担二者。`LegacyModInfo` 从原始 jar 扫描得到的 mod 自有类名通常不变，可以保留；其来源路径字段应明确是 source。`ForgeModContainer` 使用哪个 File 必须先追踪资源包/来源显示用途：默认优先 runtime jar 以与 code source 一致，但若 UI/provenance 要展示原路径，则新增独立 source 字段，不要偷偷混用。

### 2.3 Fail closed

以下情况禁止把原始 jar 加入 Knot、禁止静默 identity、禁止继续构造该 mod：

- 判定为 official 但 remap 失败；
- 判定为混合命名空间；
- remap 输出仍含确定的 official 游戏类引用；
- 映射资源、游戏 classpath 或缓存写入不可用；
- 输出 jar 不完整或不能重新打开。

错误日志必须包含 mod 文件、输入命名空间、首个决定性引用和原因。不能再通过捕获 `NoClassDefFoundError` 让游戏“看似能进”。

## 3. 精确改动位置

### 3.1 新建 `LegacyForgeModRemapper`

建议新文件：

```text
src/main/java/net/xiaoyu233/fml/modfixer/LegacyForgeModRemapper.java
```

职责：

1. 加载 `/intermediary.tiny`（Tiny v1，`official` → `intermediary`）；
2. 建立只包含**非 identity 游戏类映射**的 source/target 集合，供命名空间判定和输出扫描；
3. `prepare(Path sourceJar, Path intermediaryGameJar, Path gameDir)` 返回不可变结果：
   - namespace；
   - source path；
   - runtime path；
   - cache hit/miss；
   - 判定证据计数；
4. official 输入执行 TinyRemapper JAR 级 remap；
5. intermediary 输入安全 passthrough；
6. unknown/no-game-reference 输入允许 passthrough，但日志必须明确为 `UNKNOWN_NO_GAME_REFS`，不能宣称 intermediary；
7. mixed/ambiguous 输入拒绝；
8. 缓存、锁、原子发布和输出校验。

`ForgeSrgModRemapper.java` 不应继续保留虚假的 identity 说明。实施时二选一：

- 删除并用 `LegacyForgeModRemapper` 替代；或
- 暂保留一个 deprecated facade，仅转调新实现，随后删除 Knot 的按类钩子。

最终生产路径中不得再调用 `remapClass(...)`。

### 3.2 修改 `ForgeModDiscoverer.tryDiscover`

文件：

```text
src/main/java/net/xiaoyu233/fml/modfixer/ForgeModDiscoverer.java
```

顺序改为：

1. 从 source jar 读取 manifest、`mcmod.info`、`@Mod`、AT、coreplugin 元数据；
2. 确认是 legacy Forge jar；
3. 调用 `LegacyForgeModRemapper.prepare(source, FishModLoader.getGameJarPath(), Paths.get(Launch.minecraftHome))`；
4. prepare 成功后创建带 source/runtime 双路径的 `DiscoveredForgeMod`；
5. **只**将 `runtimeJarPath` 加入 Knot；
6. AT 仍从 `sourceJarPath` 导入，因为 AT 规则可能是 official，现有 importer 负责 official → intermediary；
7. coreplugin 类从 runtime jar 加载；
8. 任一步失败则该 jar 不进入 `discovered`，不加入 Knot，不注册 coreplugin，并输出单条汇总错误及具体 cause。

避免当前“先加入 discovered，再尝试 addCodeSource”的半成功状态。`discovered` 应在 prepare 和 addCodeSource 均成功后发布。

### 3.3 修改 `DiscoveredForgeMod` 与生命周期使用方

文件：

```text
src/main/java/net/xiaoyu233/fml/modfixer/ForgeModDiscoverer.java
src/main/java/net/xiaoyu233/fml/modfixer/LegacyModLifecycle.java
src/main/java/net/xiaoyu233/fml/modfixer/LegacyModInfo.java（仅在需要澄清 source path 时）
```

- 将 `jarPath` 拆为 `sourceJarPath` / `runtimeJarPath`；
- 错误与用户展示优先 source 文件名；
- 类加载和 code source 使用 runtime；
- metadata/来源记录保留 source；
- 检查 `ForgeModContainer`、资源包注册、配置目录和 mod source File 的用途后明确选择，不得靠碰巧两个 jar 都有资源而掩盖语义。

### 3.4 删除 Knot 中按类 remap 钩子

文件：

```text
src/main/java/net/xiaoyu233/fml/classloading/KnotClassDelegate.java
```

删除：

```java
ForgeSrgModRemapper.remapClass(...)
```

`getRawClassByteArray` 应直接返回 runtime jar 中已经重映射的 bytes。保留既有后续顺序：

```text
runtime intermediary bytes
→ FMLClassTransformer
→ Mixin
→ external IClassTransformer
→ defineClass
```

通过测试机械确认后续 transformer 看到的是 intermediary 描述符。

### 3.5 复用现有 TinyRemapper API，但不要直接复用有缺陷的生命周期对象

可参考：

```text
src/main/java/net/xiaoyu233/fml/mapping/CachedMappedJar.java
src/main/java/net/xiaoyu233/fml/util/ModRemapper.java
src/main/java/net/fabricmc/loader/impl/discovery/RuntimeModRemapper.java
```

当前依赖：

```text
net.fabricmc:tiny-remapper:0.14.0
```

构造建议：

```java
TinyRemapper.newRemapper()
    .withMappings(TinyUtils.createTinyMappingProvider(reader, "official", "intermediary"))
    .ignoreConflicts(false)
    .checkPackageAccess(false)
    .build();
```

实施者需用 0.14.0 实际 API 验证 builder 选项。对 mod remap 不得 `ignoreConflicts(true)` 后静默产出可疑 jar；若 MITE/继承映射确实要求放宽，应将具体冲突变为可诊断测试后再决定。

执行：

```java
remapper.readClassPath(intermediaryGameJar);
remapper.readInputs(sourceJar);
output.addNonClassFiles(sourceJar, NonClassCopyMode.FIX_META_INF 或等价安全模式, remapper);
remapper.apply(output);
remapper.finish();
```

必须以 `FishModLoader.getGameJarPath()` 指向的**已重映射 intermediary 游戏 jar**作为 classpath，而不是原始 `libs/1.6.4-MITE.jar`。每次 remap 使用新的 TinyRemapper 实例，不能复用 finish 后对象。

## 4. 命名空间判定

新增枚举建议：

```text
OFFICIAL
INTERMEDIARY
UNKNOWN_NO_GAME_REFS
MIXED
```

扫描 jar 中所有 class 的结构化引用，不只做原始字节串搜索：

- class/super/interface；
- field/method descriptor；
- field/method instruction owner；
- signatures；
- annotation/type annotation descriptor；
- method handle、constant dynamic、invokedynamic descriptor；
- class literals、数组类型和异常类型。

只使用 `intermediary.tiny` 中 source != target 的类映射作为强证据，避免默认包中本来就是 identity 的 MITE 类造成误判。

判定规则：

- 只出现 official source：`OFFICIAL`；
- 只出现 intermediary target：`INTERMEDIARY`；
- 两者都出现：`MIXED`，fail closed，并列出前几个引用；
- 两者均未出现：`UNKNOWN_NO_GAME_REFS`，允许 passthrough，记录原因。

额外扫描反射字符串（如 `"aqz"`）只产生 warning；不能擅自重写普通字符串。若真实 mod 因反射字符串失败，单独设计显式兼容规则。

注意：同一个 source/target 名可能恰好也是 mod 自有类名。判定应结合引用位置和映射集合，并设置最低证据阈值/多引用统计；但 Lucky Block 的 `aqz`、`abw`、`uf` 等大量引用应稳定判为 OFFICIAL。阈值不能让单个合法 official 游戏字段漏过，最终输出扫描是第二道保障。

## 5. 缓存与并发

缓存目录：

```text
<gameDir>/.fml/remappedForgeMods/
```

该目录位于已忽略的 `run/` 或用户游戏目录，不入库，无需把产物放进 `mods/`。

缓存键至少包含：

- source jar 全量 SHA-256；
- `/intermediary.tiny` SHA-256；
- intermediary game jar SHA-256；
- FishModLoader 版本；
- 独立的 remap schema/version 常量；
- TinyRemapper 版本（显式常量或纳入 schema bump）。

建议文件名：

```text
<sanitized-source-name>-<key-prefix>-intermediary.jar
```

安全要求：

- 输出先写同目录唯一 `.tmp`；
- 完成 remap、关闭 zip、重新打开和 namespace 验证后再 `ATOMIC_MOVE`；文件系统不支持时退回 `REPLACE_EXISTING`，但仍只发布完整文件；
- 同 JVM 对同一 key 使用 `ConcurrentHashMap`/future 去重；必要时加跨进程 lock file；
- cache hit 也要至少验证可打开、存在 class、manifest/资源可读和命名空间扫描通过；
- remap 失败删除临时文件，不污染缓存；
- 不覆盖用户原 jar。

## 6. 非 class 资源、Manifest 与签名

输出必须保留：

- `mcmod.info`；
- assets、语言、配置模板；
- manifest 主属性（`FMLAT`、`FMLCorePlugin`、`ForceLoadAsMod` 等）；
- AT 文件；
- service/resource 文件。

修改 class 后原 jar 签名失效。必须使用 TinyRemapper 的 `NonClassCopyMode.FIX_META_INF`（若 0.14.0 提供）或显式：

- 移除 `META-INF/*.SF`、`*.RSA`、`*.DSA`、`SIG-*`；
- 清理 manifest entry digest/signature 属性；
- 保留 manifest main attributes。

不得把无效签名原样复制后依赖 JVM 恰好不校验。增加签名资源 fixture 验证这一行为。

## 7. AT、CorePlugin 与双 classloader 边界

- **AT**：从 source jar 读取。`ForgeAccessTransformerImporter` 已能把 official AT 规则映射到 intermediary；若改从 runtime jar 读取，同一资源内容仍是 official，语义不会自动改变，容易误导。
- **普通 mod 类**：只从 runtime jar 加载。
- **CorePlugin 与其 transformer**：从 runtime jar 加载，使 transformer 自身引用的游戏类也已 remap。
- **Manifest 中 coreplugin 类名**：通常为 mod 自有类名，remap 后不变；仍从 source manifest 读取。
- **App/Knot 状态**：discovery 继续在 Knot copy 执行；AT 必须继续通过 App-owned `FishModLoader.importForgeAccessTransformers(sourceJarPath)` 导入 App 侧注册表。
- 不允许 source 与 runtime 两个 jar 同时成为 Knot code source，否则可能随机加载旧 class。

CorePlugin 可能按 official 名字符串匹配待变换类，这是后续高风险边界。首个实现必须记录支持限制；不要为了普通 mod blocker 同时承诺任意 coremod 兼容。

## 8. 自动回归设计（不得提交 Lucky Block jar）

### 8.1 新建 synthetic official Forge fixture

在 `src/integrationTest/forgeRemap/` 建立仓库自有 fixture。推荐通过一个小型 Java/ASM fixture builder 生成输入 jar，避免提交二进制：

- `@Mod` 入口类包含类型为 official `aqz` 的字段，但在 remap 后应为 `net.minecraft.block.Block`；
- 一个类 `extends aqz`；
- 至少一条 official 字段访问和一条 official 方法调用，使用 `intermediary.tiny` 中经真实 jar 核实存在的成员；
- 描述符包含 official 参数/返回类型；
- manifest 含测试用属性和伪签名条目；
- 含普通资源，验证复制；
- jar 不包含 `aqz.class`，防止测试误加载 stub。

若用 Java 源码构造，可在默认包编译测试 mod 与临时 `aqz` stub，打包时排除 stub；但 ASM builder 更容易精确控制 owner/descriptor。fixture 只能使用项目自有代码和映射，不得抽取 Lucky Block class。

### 8.2 新增 probe/Gradle 任务

建议：

```text
probeLegacyForgeModRemapper
verifyForgeModRemapIntegration
```

Probe 断言：

1. official fixture 判为 `OFFICIAL`；
2. remap 后字段描述符、父类、字段名、方法名和描述符均为 intermediary；
3. 输出扫描不存在已映射 official 引用；
4. 普通资源和 manifest main attrs保留；签名元数据移除；
5. 第二次 prepare 命中缓存且字节一致；
6. source 内容或 mapping/schema key 改变会产生不同缓存；
7. intermediary fixture passthrough，不 double-remap；
8. no-game-ref fixture 判为 `UNKNOWN_NO_GAME_REFS` 并可加载；
9. mixed fixture 被拒绝，且未加入 Knot；
10. 制造 remap 失败后没有发布缓存文件。

Integration 断言：

- fixture 被发现；
- 日志明确 `namespace=OFFICIAL`、cache hit/miss 和 runtime jar；
- `Constructed Forge mod`；
- active mod 数为 1；
- preInit/init/postInit 和适用服务端阶段执行；
- mod 入口反射字段类型为 `net.minecraft.block.Block`；
- 服务端到 `Done` 并正常 stop。

把新 probe 加到 `verifyForgeCompatibilityQuick`；把真实 fixture 生命周期测试加到 `verifyForgeCompatibilityIntegration`。保留现有 AT fixture。

### 8.3 负向测试必须存在

在修复前运行 synthetic official fixture 应稳定复现 official 类加载失败；修复后通过。至少保留 mixed/failure 两个 fail-closed 测试，防止未来又退化为“失败就返回原 bytes”。

## 9. 分阶段实施与回滚点

### 阶段 A：纯 remapper 与 probe

- 新增 namespace scanner、cache key、JAR remapper；
- 不接生产 discovery；
- 完成所有字节码/资源/缓存/fail-closed probe。

接受标准：probe 全通过，现有 quick/integration 不回归。
回滚点：仅新增未接线代码和 fixture，容易独立回退。

### 阶段 B：接入 discovery，移除按类 remap

- source/runtime 双路径；
- remap 成功后再 addCodeSource/discovered；
- 删除 Knot 的 `remapClass` 调用；
- AT 从 source、coreplugin 从 runtime；
- 新增生命周期 integration。

接受标准：synthetic official mod 完整生命周期通过；intermediary/unknown fixture 不 double-remap；错误输入 fail closed。
回滚点：一个集中接线提交，不与真实 mod 专项 API 修复混杂。

### 阶段 C：本地 Lucky Block 人工验收

不提交样本。记录 SHA-256、启动命令和完整日志：

```bash
./gradlew verifyForgeCompatibility
./gradlew runClient
```

最低日志断言：

```text
Forge mod namespace: OFFICIAL
Remapped Forge mod ... -> .../.fml/remappedForgeMods/...
Constructed Forge mod: lucky
Forge mod construction complete: 1 active mod(s)
```

并确认不再出现：

```text
NoClassDefFoundError: aqz
ClassNotFoundException: aqz
```

功能验收：

- preInit/init/postInit 均无 handler 异常；
- 配置或注册日志出现；
- 新建测试世界；
- 通过配方、创造/命令或世界生成中的至少一条可重复路径确认 Lucky Block 方块实际存在；
- 保存 `run/logs/latest.log` 的证据摘要。

如果构造成功后出现 MITE API 差异（`NoSuchMethodError` 等），将其记为**下一独立兼容问题**，不要回退正确的 namespace remap，也不要把 Lucky Block提前记为 PASS。

### 阶段 D：文档与矩阵

- 更新 `PLAN-NEXT.md` 首个真实样本状态；
- 更新 `HANDOFF.md` 的缓存位置、清理命令、日志判据；
- 更正所有“ForgeSrgModRemapper identity 正确”的历史表述；
- 在兼容矩阵记录 Lucky Block source、SHA-256、环境、客户端/服务端状态和首个失败阶段；
- 若只达到构造而功能未通过，状态必须是 `PARTIAL` 或 `FAIL`，不能写 PASS。

## 10. 验证命令与完成门槛

实施者至少运行：

```bash
./gradlew probeLegacyForgeModRemapper
./gradlew verifyForgeCompatibilityQuick
./gradlew verifyForgeCompatibilityIntegration
./gradlew verifyForgeCompatibility
./gradlew runClient                 # 本地 Lucky Block 人工测试

git diff --check
git status --short
```

机械检查：

- `git grep ForgeSrgModRemapper` 不再显示生产按类 remap 钩子；
- `git grep EMPTY_IDENTITY` 不再保留错误实现；
- Knot code source 中只有 runtime remapped Forge jar；
- cache 与 fixture 产物均在忽略目录；
- staged files 为空，除非用户明确要求提交；
- 第三方 Lucky Block jar 不在 `git status` 中。

完成门槛：

1. synthetic official fixture 覆盖 class/field/method/descriptor/inheritance 并通过；
2. mixed/remap failure 确实 fail closed；
3. 现有 AW、Mixin、AT quick/integration 全通过；
4. Lucky Block 不再因 `aqz` 失败，并成功进入 active mod list；
5. 人工功能路径有证据，或明确记录 namespace 之后的下一个 blocker；
6. S3AI Claude 独立审核无 blocker，所有 findings 有 disposition。

## 11. 主要风险

- MITE 实际成员与 vanilla `intermediary.tiny` 不完全一致：TinyRemapper classpath 必须使用实际 remapped MITE jar，真实成员缺失仍可能成为后续兼容问题。
- Forge 发布 jar 可能混用 official 类名与反射字符串；结构化 remap 不应擅改普通字符串。
- CorePlugin transformer 可能按 official 字符串匹配类名，普通 mod 支持不能被夸大成任意 coremod 支持。
- 输出 jar 签名失效，必须清理签名元数据。
- App/Knot 双份 `modfixer` 状态仍是高风险边界；路径和缓存结果必须在实际执行 discovery 的 Knot copy 中一致。
- 当前服务端存在非致命运行时 Mixin warning；它们与 mod remap 是独立 P0，不能用本工作掩盖。
- 当前工作树已有未提交的计划整理改动；下个对话必须先读 `git status` 和本文件，不得误覆盖。

## 12. 下个对话起步指令

1. 读取 `AGENTS.md`、`HANDOFF.md`、`PLAN-NEXT.md` 和本文件；
2. 检查 `git status`，保留现有未提交文档/Gradle聚合任务改动；
3. 先探活 `super-gpt/gpt-5.6-sol`；
4. 让单一 writer 实施阶段 A，不要直接从 Lucky jar 抄 fixture；
5. 阶段 A 通过后用 S3AI Claude 只读审核；
6. 再实施阶段 B，并重复 review/validation；
7. 最后人工运行本地 Lucky Block，记录真实结果；
8. 未经用户要求不要 commit。
