# Forge 兼容改造历史与排障记录

本文保存原 [`PLAN.md`](../PLAN.md) 与 [`HANDOFF.md`](../HANDOFF.md) 中仍有维护价值、但不应继续混入当前状态的错误演进和踩坑记录。数字均为**当时快照**，不得作为当前缺陷数；当前基线以 [`PLAN.md`](../PLAN.md) 的可执行验证为准。

## 1. 构建与命名空间演进

早期构建存在以下连续问题：

1. `tasks.gradle` 引用了当时不存在的 `RemapToIntermediary`，验证链无法执行。
2. `applyAccessWidener` 直接消费 official jar，而源码和 AW 使用 named 名，导致 widen 0 个类、`net.minecraft.*` 全部不可解析。
3. 修复 official → intermediary → named 后，编译错误曾依次为 2230、1094、1082、66，最终全部解决。这些数字只用于说明映射修复效果。
4. `named.tiny` 的成员描述符是 official 形态；按“名字 + 描述符”匹配会静默失败。翻译 8864 个描述符曾使错误从 1082 降至 66。
5. `RemapIntermediaryToNamed` 需要 `ignoreFieldDesc(true)` 处理字段描述符不一致；同时必须过滤 `MinecraftServer.field_71322_p → playersOnline` 这类与 MITE 现有字段冲突的映射。
6. MITE jar 内置旧 Guava/Gson，曾遮蔽构建声明的新版本；构建期从 `widen.jar` 剥离 `com/google/**` 以让声明依赖生效。

正确链路为：

```text
MITE jar (official)
  → mite-intermediary.jar
  → mite-named.jar
  → widen.jar（编译）

loader named jar
  → named2intermediary 映射
  → intermediary loader jar（运行）
```

### owner/descriptor 切分陷阱

`LongHashMapEntry/Ljava/lang/Object;` 一类文本既可能包含 owner 分隔又可能是合法对象描述符。左扫、右扫或简单 owner 校验都不可靠；最终按已知类名集合切分。错误切分曾丢失字段映射并导致运行时 `IllegalAccessError`。

### 映射不等于 MITE API

`named.tiny` 描述原版 1.6.4。MITE 删除了 `canBlockStay`、`canPlaceBlockAt`、`dropBlockAsItem`、`onBlockPlaced` 等 API，改用 `isLegalAt`、`isLegalOn`、`onNotLegal`、`dropBlockAsEntityItem`。修改 Mixin 注解前必须对真实 jar 核实，不能只信映射。

历史上曾把 `BlockCactus`、`BlockFlower`、`BlockReed`、`BlockCrops` 中实际不存在的目标从 `@Unique` 改成 `@Overwrite`；这会在 Mixin 应用期硬失败，后续已回滚。类似地，13 个 Mixin 的 20 余处缺失目标最终按实际 API 调整。

## 2. AccessWidener 与 JVM 链接验证

运行时游戏 jar 使用 intermediary 名，而源 AW 使用 named 名。若不重映射 AW，日志可能打印“Widened class”，但成员名未命中，实际访问权限完全没变。

`RemapAccessWidener` 还必须沿父类和接口查找：AW 条目可能以子类为 owner，而映射只在原版声明成员的父类上记录。历史上的 `EntityMob.canDespawn` 条目就因精确 owner miss 而保持 named 形式，最终触发客户端 `VerifyError`。

### 客户端 VerifyError 根因

MITE 原始类同处默认包；remap 后被拆到多个包，原本合法的 protected 访问可能违反 JVMS 4.10.1.8：跨运行时包访问父类 protected 成员时，栈上接收者还必须可赋值给当前类。tiny-remapper 的 `fixPackageAccess(true)` 覆盖解析期访问控制，但未覆盖该校验器规则。

典型症状：

```text
VerifyError: Bad access to protected data in invokevirtual
Type 'EntitySkeleton' is not assignable to 'EntityBoneLord'
```

这类问题通过正确命中的 AccessWidener 修复。

### 为什么 `verifyGameJar` 必须强制链接

`defineClass` / `loadClass` 不保证触发 HotSpot 字节码校验；链接是惰性的。工具必须调用 `getDeclaredConstructors/Methods/Fields` 强制链接，否则坏 AW 也可能报告 0 缺陷。

`isLoaderOwned` 跳过 Guava 等包不是降噪技巧，而是模拟 `LaunchClassBlocker` 的真实边界；游戏 jar 中这些类运行时不会由 Knot 加载。不跳过会形成 split-loader 假报。

改 AW 或映射后，应先做对照实验：故意破坏一条规则，确认 `verifyGameJar` 能捕获，再相信 0 defects。

## 3. Mixin 验证演进

早期静态脚本直接以 named 名查 intermediary jar，产生大量误报。改用 named jar或先通过 `named.tiny` 翻译后，结果才有意义。

历史快照包括“81 项缺失”“49 个 Shadow 缺失”“66 条编译错误”等；它们都已失效。旧 shell 脚本与当前 Java 验证器统计口径不同：Java 版按当前源码扫描全部 `@Shadow`/`@Overwrite`，并对 named jar 做完整描述符与父类链校验，因此当前的 340 项不能与旧数字直接相减。静态现状由 `verifyOverwrites` 和 `verifyInjections` 的执行结果决定。

需要特别注意：真实 AT 服务端 E2E 当前仍会记录一些非致命 `InvalidMixinException` warning，显示 refmap 后的 intermediary `@Shadow`/`@Overwrite` 未命中；服务端仍可到 `Done`，且 AT 断言通过。这说明 named-jar 静态检查与运行时 refmap 应用存在覆盖差异。后续必须分类这些 warning，不能用静态“缺失 0”宣称运行时所有 Mixin 都成功应用。

维护原则：

- `@Overwrite` / `@Shadow` 目标缺失会硬失败；必须验证真实目标与描述符。
- `@Unique` 添加 Forge API 时目标本就可以不存在；不能为了“让 patch 生效”盲目改成 `@Overwrite`。
- 裸方法名注入选择器不可靠；对关键入口使用完整描述符，例如 `main([Ljava/lang/String;)V`。
- Mixin 过早应用可能在 `main` 前解析到尚不符合前提的类；FML handler 初始化应保持惰性。

## 4. Classloader 与 Forge 生命周期

### 双份静态状态

`net.xiaoyu233.fml.modfixer` 曾在 AppClassLoader 与 KnotClassLoader 各加载一份。App 侧 discovery 写入的静态 `discovered` / AT 规则对 Knot 侧生命周期不可见，表现为日志显示发现 mod，却不构造或不应用 AT。

修复后 discovery/lifecycle 通过 `FishModLoader.invokeInKnot` 统一执行，AT 规则显式导入实际执行转换的 App 侧注册表。维护时必须继续确认：

- 哪一侧持有 loader 全局状态；
- 哪一侧需要看到游戏类；
- 不要因 whitelist/blocker 调整再次制造第二份单例或注册表。

`LaunchwrapperBridge` 只提供 Forge 兼容接口并委托 Knot。`net.minecraft.launchwrapper.*` 由 AppClassLoader 保持单份，避免独立 `Launch.blackboard`。

### 生命周期曾经的静默失效点

1. `Loader.modController` 原本只在 vanilla `loadMods()` 创建，而 FishModLoader 不走该方法；增加 `ensureModController()`。
2. `LoadController.transition` 只接受直接后继，外部驱动从 NOINIT 到 PREINITIALIZATION 会失败；增加逐级 `advanceTo()`。`ERRORED` 必须先短路，不能被强制状态洗掉。
3. 按 `Object.class` 查找反射方法无法匹配 `MinecraftServer` 参数；改为按名字和运行时可赋值性匹配，多候选时报错。
4. `FMLServerHandler` 是私有构造单例，应调用 `instance()`；初始化成功前不能提前置完成标志。
5. 资源包注册曾在 sided delegate 未就绪时中断 `buildModList`，导致 active list/event channels 半初始化；增加边界保护。
6. loader 源码中的字符串方法名不会被构建重映射；可以强类型调用时应让 remapper 处理。
7. `FMLLog` 使用 JUL 且未桥接 log4j，排障时可能看不到 handler 异常；关键路径使用 FishModLoader logger。

## 5. 真实 Forge AT 服务端夹具的价值

单元/探针验证无法证明真实 classloader、发现和生命周期组合正确。`src/integrationTest/forgeAt` 因此构建一个本地 legacy Forge mod jar：

- 含 `@Mod`、manifest `FMLAT` 和 `META-INF/fixture_at.cfg`；
- 入口类不符号引用目标类；
- 目标类直到 `FMLServerStartedEvent` 才反射首次加载；
- 断言字段从 `private final` 变为 `public` 且移除 final；
- 同时要求规则发现日志、`[AT] Applied`、服务端 `Done` 和正常停服。

它曾直接暴露 App/Knot 两份 AT 规则表的问题，因此应保留为 integration 层，而不能用 parser probe 替代。

## 6. 崩溃报告器掩盖原始异常

MITE 的 `CrashReport.makeCategoryDepth` 曾因 Mixin 改变栈深而索引为负，崩溃报告器自己的 AIOOBE 会吞掉真实错误。若再次出现类似症状，可直接记录 JVM 异常：

```bash
java -Xlog:exceptions=info:file=exc.log -cp ...
grep -iE "World|Chunk|IllegalAccess" exc.log | tail -20
```

不要仅依赖最终 crash report。

### 客户端复现环境陷阱

客户端需要一份完整的 1.6.4 libraries、natives 和 assets，并使用 `*-all-intermediary.jar`。`1.6.4-MITE.json` 声明 LWJGL `2.9.1-nightly-20130708-debug3`，该版本本地通常不存在；历史实测以 LWJGL `2.9.0` 替代可正常启动到主菜单。排查客户端回归时应先核对这项环境差异，避免把依赖缺失误判为 loader 回归。

## 7. 已淘汰路径与维护结论

- ForgeGradle 风格 `applyForgePatches` / `compilePatchedSource` / `packagePatchedJar`：**已删除**；Mixin 是最终实现路径。
- `ForgeSrgModRemapper`：曾被替换为 identity passthrough，但真实 Lucky Block 4.2.1 发布 jar 的 `Laqz;` 引用证明该假设错误；该结论已废止。此后已实现 official → intermediary JAR 级预重映射，当前状态见 [`../HANDOFF.md`](../HANDOFF.md)。
- `MixinConfigCreator`：**保留孤立 stub**；无调用点，不是当前待实现项。
- `mixin.refmap.json`：**已从仓库删除**；它是构建产物，不应提交。
- 历史错误计数、提交号和“待完成”复选框只用于追溯，不应复制回当前计划或交接文档。
