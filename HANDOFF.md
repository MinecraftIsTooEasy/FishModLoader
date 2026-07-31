# 交接文档 — FishModLoader Forge 兼容

分支：`classloader-patch`
最新提交：Forge Access Transformer 运行时支持（本提交）
基线：`f78f589 add patches to mixins`（origin/forge-compat 的原始位置）

---

## 当前状态（已全部验证）

### 构建
`./gradlew buildJar` — **BUILD SUCCESSFUL**

### 验证任务
| 任务 | 结果 |
|------|------|
| `verifyGameJar` | Linked OK **4559** / DEFECTS **0** |
| `verifyOverwrites` | **340** 项全部解析，缺失 **0**（原来 49 项，已全部修复） |
| `verifyInjections` | **0** 缺陷（自递归/签名/目标缺失全清零） |

### 服务端运行
可完整启动到：
```
[Server thread/INFO]: Done (0.742s)! For help, type "help" or "?"
```
Forge mod 五个生命周期（preInit / init / postInit / serverStarting / serverStarted）全部执行。
空 `mods/` 下同样启动到 Done，零回归。

### 客户端运行
启动到主菜单，零 `VerifyError`、无崩溃报告。

复现方式：
```bash
mkdir -p /d/code/fml_test && cd /d/code/fml_test
cp <repo>/build/libs/FishModLoader-4.0.0-all-intermediary.jar .
cp ~/Desktop/MITE-HDS.jar .
echo "eula=true" > eula.txt
java -cp "FishModLoader-4.0.0-all-intermediary.jar" \
     net.xiaoyu233.fml.relaunch.server.JarMain nogui
```

> **必须用 `-all-intermediary.jar`**，不是 `-all.jar`。
> 前者才经过 `remapLoaderJar` + refmap 嵌入 + AW 重映射。

### 客户端启动（本轮新增验证）
此前从未测过客户端。现已启动到主菜单，零 `VerifyError`、无崩溃报告。

决定性日志（原崩溃正是死在这一步）：
```
Writing reference files... [ok]
[AW] Widened class: net.minecraft.util.EnumSpecialSplash   ← GuiMainMenu 已构造
```

复现（需要一份带 libraries/natives/assets 的 1.6.4 客户端）：
```bash
# 脚本已生成在 /d/code/fml_client_test/run.sh，要点：
MC=D:/MCclient/Minecraft
java -Djava.library.path=natives -Dminecraft.path=1.6.4-MITE.jar \
     -cp "FishModLoader-...-all-intermediary.jar;1.6.4-MITE.jar;<22 个 libraries>" \
     net.xiaoyu233.fml.relaunch.client.Main \
     --username TestUser --version 1.6.4-MITE \
     --gameDir . --assetsDir "$MC/assets/virtual/legacy"
```

> `1.6.4-MITE.json` 声明的 LWJGL 是 `2.9.1-nightly-20130708-debug3`，本地没有；
> 用 `2.9.0` 可正常启动（vanilla 日志里实际加载的也是 2.9.x）。

### 环境前提
- `libs/1.6.4-MITE.jar` = 桌面的 `MITE-HDS.jar`（official/混淆命名空间，默认包 `a.class`）。
  被 .gitignore 忽略，不入库，换机器需自备。
- JDK 17（Zulu 17 实测可用）。

---

## 最重要的一条背景知识：三层命名空间

这是本会话大部分 bug 的共同根源，**动手前务必理解**：

| 命名空间 | 形态 | 出现位置 |
|---------|------|---------|
| `official` | `a.class`、`lq`、默认包 | `libs/1.6.4-MITE.jar` 原始游戏 jar |
| `intermediary` | `net/minecraft/...` + `func_*`/`field_*` | **运行时**游戏 jar、运行时 loader jar |
| `named` | `net/minecraft/...` + `blockID`/`getItem` | **源码**、`fishmodloader.accesswidener` |

映射文件：
- `intermediary.tiny` — tiny **v1**，`official` → `intermediary`
- `named.tiny` — tiny **v2**，`intermediary` → `named`。
  **陷阱：其中的成员描述符存的是 official 形态**（如 `(Lavi;...)V`、`[Laqz;`）
- `build/tmp/named2intermediary.tiny` — 构建期生成，`named` → `intermediary`

构建链路：
```
libs/1.6.4-MITE.jar (official)
  ├─ remapMiteToIntermediary  → build/tmp/mite-intermediary.jar
  ├─ remapMiteToNamed         → build/tmp/mite-named.jar   ← 编译类路径来源
  ├─ applyAccessWidener       → build/tmp/widen.jar        ← 实际 compileClasspath
  │                             （并剥离 com/google/** 1771 项）
  └─ remapAccessWidener       → fishmodloader-intermediary.accesswidener

shadowJar (named)
  └─ remapLoaderJar (+MixinExtension) → *-all-intermediary.jar
       ├─ genMixinRefmap              → 嵌入 mixin.refmap.json
       └─ embedRemappedAccessWidener  → 替换成 intermediary 版 AW
```

`named.tiny` 描述的是**原版 1.6.4**，不等于 MITE 实际 API。多次出现
"映射说有、jar 里没有"的情况（如 `canBlockStay`）。**以实际 jar 为准。**

---

## 排查工具

### tools/verify_overwrites.sh
静态校验 forge_compat 里所有 `@Overwrite`/`@Shadow` 目标是否真实存在：
```bash
bash tools/verify_overwrites.sh build/tmp/mite-named.jar
```
当前输出：检查 279 项，缺失 49 项（全为 `@Shadow`，`@Overwrite` 已清零）。

### tools VerifyGameJar（本轮新建）
对重映射后的游戏 jar **逐类跑一遍 JVM 校验器**，AW 已按运行时方式应用。
专抓 `VerifyError` / `IllegalAccessError` —— 这类错误只在类链接期出现，
静态扫描（包括 tiny-remapper 自己的 `fixPackageAccess`）抓不到。

```bash
./gradlew verifyGameJar                          # 默认查 build/tmp/mite-intermediary.jar
./gradlew verifyGameJar -PgameJar=<jar>           # 指定 jar（建议用运行时缓存的那份）
./gradlew verifyGameJar -PgameJar=<jar> -Ptrace=EntityBoneLord   # 打印单个类的完整异常
./gradlew verifyGameJar -PgameJar=<jar> -PawFile=<aw>            # 换 AW 做对照实验
```

当前输出（运行时 jar）：`Linked OK 4559 / DEFECTS 0`。

两个**必须知道**的坑，否则会得到假阴性：

1. **`defineClass` 不触发校验。** HotSpot 在**链接**期校验，而链接是惰性的。
   工具靠 `getDeclaredConstructors/Methods/Fields` 强制链接 ——
   这正是原始崩溃的触发路径（`ReferenceFileWriter` 调 `Class.getConstructor`）。
   工具第一版漏了这步，坏 AW 也报 0。
2. **`isLoaderOwned` 跳过的包不是噪音过滤**，而是复刻运行时事实:
   `LaunchClassBlocker` 把 `com.google.common` 等交给 AppCL，游戏 jar 里那份永不加载。
   不跳过会产生 124 条 split-loader 假报。

> 改 AW 或映射后，务必用**对照实验**确认工具真的能抓到问题（故意改坏一条，
> 看它是否报错），再相信「0 defects」。

### 崩溃被掩盖时怎么办
MITE 的 `CrashReport.makeCategoryDepth` 用 `stackTrace.length - depth` 做索引，
mixin 注入会改变栈深度导致索引为负，**崩溃报告器自身抛 AIOOBE，把真实错误吃掉**。
已在 `CrashReportMixin` 加钳制，但若再遇到类似"Index -1 out of bounds"，
用 JVM 异常日志直接看原始异常，比读崩溃报告可靠：
```bash
java -Xlog:exceptions=info:file=exc.log -cp ... 
grep -iE "World|Chunk|IllegalAccess" exc.log | tail -20
```
本会话正是靠这招才找到真正的 `IllegalAccessError`。

---

## 待完成（P2，不阻塞功能）

- [ ] **`LaunchMixin`**：KnotClassLoader 侧防止 `launchwrapper.Launch` 二次初始化。
      可先评估是否真的需要（目前未观察到实际问题）。
- [ ] **`ForgeAccessTransformerImporter.importFrom`**：已改为运行时 ASM AT 规则模型；支持 manifest FMLAT、固定位置及 META-INF/*_at.cfg 去重，已处于 intermediary/SRG 的规则保持原样，并基于 `intermediary.tiny` 精确映射 official 类、字段、方法及方法描述符。字段映射歧义、缺失成员或描述符中的未知 official 类型会显式 warning 并拒绝，不再假报成功；`probeForgeAccessTransformer` 已覆盖这些行为。仍待用真实带 AT mod 做服务端验证。
- [ ] **`ForgeSrgModRemapper`**：仍是 identity passthrough，目前不影响功能（运行时已在 intermediary 命名空间）。
- [ ] **`MixinConfigCreator`**：空 stub，暂不阻塞。
- [ ] **`src/main/resources/mixin.refmap.json`**：仓库里这份只有 60 条，构建期生成的有 156 条。
      建议从仓库删除，避免误用纯 `shadowJar` 时嵌入旧版。

### P2 清理已完成

- [x] ~~`tasks.gradle` 死代码（`applyForgePatches` / `compilePatchedSource` / `packagePatchedJar`）~~ — 已删除。

---

## 本会话修掉的坑（避免重复踩）

### 客户端 VerifyError（第三轮会话）

症状：客户端启动崩在 `ReferenceFileWriter.writeMobExperienceFile`，
`java.lang.VerifyError: Bad access to protected data in invokevirtual`，
`Type 'EntitySkeleton' is not assignable to 'EntityBoneLord'`。

**根因是 tiny-remapper 的盲区，不是 AW 写错了。**

MITE 原始 jar 里 `EntityBoneLord` / `EntitySkeleton` / `EntityMob` 同在默认包，
访问 protected 成员合法。`intermediary.tiny` 把它们拆到
`net/minecraft/entity` 和 `net/minecraft/entity/monster` 两个包后，
`EntityBoneLord.func_70636_d` 里那句
`invokevirtual EntitySkeleton.func_70692_ba()Z` 就非法了 ——
接收者是局部变量，不是 `this`。

`fixPackageAccess(true)` **修不了这个**。`PackageAccessChecker.checkMember`
实现了 JVMS 5.4.4（解析期访问控制），但没实现 **JVMS 4.10.1.8** ——
校验器的额外规则：跨运行时包访问父类 protected 成员时，栈上接收者必须
可赋值给**当前**类。tiny-remapper 认为这次访问合法（`EntityBoneLord`
确实是 `EntityMob` 的子类），于是不 widen。实测印证：构建期 jar 用了
`fixPackageAccess(true)`，`func_70692_ba` 仍是 protected。

**这类问题只能靠 AccessWidener 修**，而 AW 里那条规则一直是死的：

`RemapAccessWidener` 做 owner 精确查找。第 132 行 owner 写的是
`EntityMob`，但 `named.tiny` 是原版派生的，`canDespawn` 只挂在
`EntityLiving` 下（MITE 才在 `EntityMob` 覆盖它）。查找 miss →
该行以 **named 形式**原样输出 → 运行时 jar 里是 `func_70692_ba` →
AccessWidener 按名字匹配，零成员命中。更坑的是
`FMLClassTransformer` 仍会打印 `[AW] Widened class: EntityMob`，
看日志以为生效了。

修法：给 `RemapAccessWidener` 加父类链遍历（owner 精确 miss 后沿
 superclass + interfaces 做 BFS），层次由 `mite-named.jar` 经 ASM 读取。
第 132 行现在正确重写为 `func_70692_ba`。工具同时把仍未解析的条目
列出来而不再静默通过。

> **教训：改 AW 后必须验证它真的命中了成员，不能只看
> `[AW] Widened class` 日志。** 用 `verifyGameJar`（下方「排查工具」）。

> 另一个坑（写验证工具时踩的）：**HotSpot 的字节码校验发生在
> *链接*期，而链接是惰性的。** `defineClass` / `loadClass` 都不触发。
> 原始崩溃正是 `Class.getConstructor()` → `getDeclaredConstructors0`
> 才触发的。验证工具第一版因此是**假阴性**（坏 AW 也报 0）。
> 必须反射 `getDeclaredConstructors/Methods/Fields` 强制链接。

### 生命周期派发（第二轮会话）

最关键的一条：**`net.xiaoyu233.fml.modfixer` 在两个 classloader 里各有一份。**
`LaunchClassBlocker` block 了 `net.xiaoyu233.fml`，但又 whitelist 了
`net.xiaoyu233.fml.modfixer`（它需要看到游戏类）。结果：

- `FishModLoader.finishModLoading` 静态调用 `ForgeModDiscoverer.discoverIn` → **AppCL** 那份
- `fireForgePreInit` 经反射调 `LegacyModLifecycle` → **Knot** 那份

两份的 `discovered` / `loadedMods` 完全独立，所以 mod 被发现、日志也打了
「Discovered Forge mod」，却永远不会被构造。现已把 discovery 和 lifecycle
统一经 `FishModLoader.invokeInKnot` 走 Knot 那份。

> 判定方法：`Constructing Forge mods (side=..., loader=...)` 这行会打印
> 实际加载 `LegacyModLifecycle` 的 loader 与它看到的 side。实测是
> `side=SERVER, loader=KnotClassLoader` —— 说明 `FishModLoader` 自身仍是单份
> （未被 whitelist），静态状态跨 loader 一致。这个前提一旦被破坏，
> `@SidedProxy` / `@SideOnly` 会静默按错误的 side 解析。

其余同类「静默失效」问题：

1. **`Loader.modController` 从不存在** — vanilla 只在 `loadMods()` 里 new，
   FishModLoader 不调它。此前代码对 null 做保护后 return，等于
   `distributeStateMessage` 全程无人接收。→ `Loader.ensureModController()`。

2. **`LoadController.transition` 只认直接后继** — 外部驱动生命周期时从
   NOINIT 直接要 PREINITIALIZATION 会抛「state engine is invalid」。
   → `LoadController.advanceTo(desired)` 逐级推进。
   注意 `ERRORED` 的 ordinal 最大，必须先判 ERRORED 直接返回 false，
   否则 `forceState` 会把失败状态洗成健康状态。

3. **反射签名对不上** — `getMethod(name, Object.class)` 找不到声明
   `MinecraftServer` 参数的 `fireServerStarting`，每次服务端阶段都
   NoSuchMethodException。→ 按名字 + 运行时参数可赋值性匹配，多候选时报错不猜。

4. **`FMLServerHandler` 是私有构造单例** — `getDeclaredConstructor().newInstance()`
   抛 IllegalAccessException，`sidedDelegate` 永久为 null。→ 改用 `instance()`。
   另外 `ensureFMLCommonHandler` 原本先置 `commonHandlerInitialised = true`
   再干活，一次失败就永久不再重试。→ 只在成功时置位。

5. **`buildModList` 被资源包注册拖垮** — `addModToResourcePack` 在
   `sidedDelegate` 还没就绪时 NPE / NoClassDefFoundError，异常从
   `buildModList` 中途逃出，`activeModList` 和 `eventChannels` 只填了一半，
   之后所有事件无人接收。→ 加 try/catch 与 null 保护。

6. **字符串反射撞上构建期重映射** — `getMethod("getCommandManager")`、
   `getMethod("getCommands")` 在 named→intermediary 重映射后解析不到。
   loader 自己的代码要用强类型调用，让 remapper 处理。

7. **`FMLLog` 输出不可见** — 它走 `java.util.logging`，本项目没桥接到 log4j，
   所以 `Activating mod xxx`、mod handler 抛的异常全都看不到。
   排查生命周期时不要依赖 FMLLog，`ForgeModContainer.onEvent` 已改用
   `FishModLoader.LOGGER`。

### 第一轮会话（构建与命名空间）

按发现顺序，每条都是"静默失败"型问题：

1. **`tasks.gradle` 引用了不存在的类** — `remapMiteToIntermediary` 一直引用
   `RemapToIntermediary`，但 tools/ 里从来没有这个文件，整条验证链断掉。已补全。

2. **compileClasspath 命名空间错了** — `applyAccessWidener` 直接吃 official jar，
   而源码和 AW 都是 named 名，结果 widen 了 **0** 个类，且所有
   `net.minecraft.*` 无法解析。已改为 official→intermediary→named→widen。

3. **`named.tiny` 的描述符是 official 形态** — tiny-remapper 按 名字+描述符
   匹配成员，描述符对不上导致几乎所有方法映射静默失效。
   翻译 8864 个描述符后编译错误 **1082 → 66**（降 94%）。

4. **`Launch.java` 请求了不存在的命名空间** — 用 `"named"` 从
   `intermediary.tiny` 取映射，该文件只有 `official`/`intermediary`。
   映射为空 → 游戏 jar 静默保持混淆 → 所有 mixin 目标失效。

5. **`LoaderRemapper` 缺 `readClassPath`** — 游戏类不在 loader jar 里，
   tiny-remapper 无法解析成员归属，成员重命名全部静默跳过，
   产出的 jar 除常量池顺序外与输入等价。

6. **owner/descriptor 切分歧义** — `LongHashMapEntry/Ljava/lang/Object;`
   本身就是合法对象描述符，纯模式扫描无法区分。这个坑我反复改错三次
   （左扫、右扫、加 owner 校验都不对），最终改为**按已知类名集合切分**才正确。
   后果是 `LongHashMapEntry.key` 等字段被丢弃 → 运行时 `IllegalAccessError`。

7. **AW 命名空间不匹配** — 文件是 named（`EnumGameType.id`），
   运行时 jar 是 intermediary（`field_77154_e`）。AccessWidener 按名字匹配，
   类被"widened"但零成员命中，MITE 跨包访问全部 `IllegalAccessError`。

8. **MITE jar 内置古董 Guava/Gson** — `EventBus` 无 `SubscriberExceptionHandler`、
   `JsonParser` 无静态 `parseReader`。widen.jar 在 classpath 靠前遮蔽了
   build.gradle 声明的新版。已在构建期剥离 `com/google/**`（1771 项）。

9. **mixin 过早应用** — `initFMLCommonHandler` 经 `FMLServerHandler` 传递加载
   `MinecraftServer`，发生在 `main` 之前，`ServerEntrypointMixin` 的
   `require=1` 注入解析到 0 个目标而中止启动。改为惰性初始化。

10. **裸方法名选择器不可靠** — `@Inject(method = "main")` 在 MITE 上解析不到，
    必须写全描述符 `"main([Ljava/lang/String;)V"`。

11. **MITE 大幅重构了原版 API** — 删掉了整套方块放置 API
    （`canBlockStay`/`canPlaceBlockAt`/`dropBlockAsItem`/`onBlockPlaced`），
    改用 `isLegalAt`/`isLegalOn`/`onNotLegal`/`dropBlockAsEntityItem`。
    13 个 mixin 的 20+ 处 `@Overwrite` 指向不存在的方法，已降级为惰性 `@Unique`。

    > 更正记录：本分支早期（`92df71d`）曾把 `BlockCactus`/`BlockFlower`/
    > `BlockReed`/`BlockCrops` 的 `@Unique` **改成** `@Overwrite`，方向是错的
    > ——这些方法在 MITE 中同样不存在，`@Overwrite` 会导致运行时硬失败。
    > 后续已回滚。教训：改注解前先用 verify_overwrites.sh 核实目标是否存在。

---

## 相关文件索引

规划与背景：`PLAN.md`（内容截止 `3df6303`，运行时部分未更新，以本文档为准）

构建工具（`tools/main/java/org/moddedmite/fish/faloom/`）：
| 文件 | 作用 |
|------|------|
| `RemapToIntermediary` | official → intermediary（本会话新建） |
| `RemapIntermediaryToNamed` | intermediary → named，供编译用（本会话新建） |
| `RemapAccessWidener` | AW named → intermediary。**本轮加父类链遍历** + 未解析条目列表 |
| `VerifyGameJar` | **本轮新建**。JVM 验证器扫全 jar，抓 VerifyError / IllegalAccessError |
| `NamedToIntermediaryTinyGenerator` | 生成 named2intermediary.tiny（切分逻辑第一轮重写） |
| `LoaderRemapper` | loader jar named → intermediary（第一轮加 classpath + MixinExtension） |
| `MixinRefmapGenerator` | 生成 refmap（第一轮补全 `@At` 全引用形式） |

运行时（`src/main/java/net/xiaoyu233/fml/`）：
| 文件 | 本会话改动 |
|------|-----------|
| `relaunch/Launch.java` | 第一轮：修命名空间；新增 launchwrapper 桥接 |
| `classloading/LaunchwrapperBridge.java` | 第一轮新建，委托给 KnotClassLoader |
| `classloading/KnotClassDelegate.java` | 第一轮：package definition 读 Manifest 属性 |
| `modfixer/LegacyModLifecycle.java` | **本轮重写生命周期派发**：走 LoadController，去掉假的 null 保护，强类型替换字符串反射 |
| `modfixer/ForgeModContainer.java` | **本轮**：handler 异常改用 `FishModLoader.LOGGER`（FMLLog 不可见） |
| `FishModLoader.java` | 第一轮：AW 改 intermediary。**本轮新增 `invokeInKnot`**，统一 discovery/lifecycle 的 classloader |

FML 侧（`src/main/java/cpw/mods/fml/common/`，均为本轮改动）：
| 文件 | 改动 |
|------|------|
| `Loader.java` | 新增 `ensureModController()`：controller 从来没被创建过是「事件无人接收」的根因 |
| `LoadController.java` | 新增 `advanceTo()` 逐级推进（含 ERRORED 短路）；`buildModList` 资源包注册加保护 |
| `FMLCommonHandler.java` | 资源包注册/更新加 null 保护 |

AW 数据（`src/main/resources/fishmodloader.accesswidener`）：
第 132 行 `EntityMob canDespawn ()Z` 是客户端 VerifyError 的修复点。
它一直存在但因 owner 精确查找而是死条目，本轮由父类链遍历激活。
