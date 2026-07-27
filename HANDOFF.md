# 交接文档 — FishModLoader Forge 兼容

分支：`classloader-patch`
最新 commit：`d7845aa fix: 服务端可完整启动，修复映射生成与运行时命名空间`
基线：`f78f589 add patches to mixins`（origin/forge-compat 的原始位置）

---

## 立即需要处理的事（第一件事）

### origin/forge-compat 被污染，需要 force-push 回退

本会话的 7 个 commit 被推到了共享远端 `MinecraftIsTooEasy/FishModLoader`，
把 `forge-compat` 从 `f78f589` 推到了 `ad7c1c9`。**不是本会话主动执行的
`git push`**（reflog 里只有 commit 记录，无 push；仓库无 hook，无 push 配置），
推测是环境中其他 agent 或工具所为，未能定位，请自行排查。

**损失评估：无。** `f78f589` 是 `ad7c1c9` 的直接祖先，这次是 fast-forward，
没有改写历史、没有丢失任何提交。

本地 ref 已用 `git update-ref` 摆正为 `f78f589`，但**远端仍是 `ad7c1c9`**。
当时网络中断无法推送，需要在网络恢复后执行：

```bash
# 1. 本会话工作推到自己的分支（快进，安全）
git push origin classloader-patch

# 2. forge-compat 回退（force-push，改写共享分支，执行前请确认）
git push --force-with-lease origin f78f589:refs/heads/forge-compat
```

注意：`--force-with-lease` 会在远端有你未知的新提交时拒绝执行，比 `--force` 安全。
另外 `origin/classloader-patch` 远端目前停在 `92df71d`，落后本地 7 个 commit。

---

## 当前状态（已验证）

### 构建
`./gradlew clean buildJar` **通过**。

### 服务端运行
可完整启动到：
```
[Server thread/INFO]: Done (2.781s)! For help, type "help" or "?"
```
- `IllegalAccessError`：0
- mixin 注入失败（`Scanned 0 target(s)`）：0
- mixin 目标未找到：0

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

## 待完成（按优先级）

### P0 — 目标的核心验收点，尚未做

- [ ] **用真实小型 Forge mod 实测加载**
      `mods/` 目录目前是空的，从未用真实 mod 验证过。这是
      「classloader 能加载一些小 forge mod」的实际验收标准。
      建议找单 jar、带 `@Mod` 注解、无复杂依赖的 1.6.4 mod。
      重点观察 `ForgeModDiscoverer` / `LegacyModLifecycle.constructAll` 路径。

- [ ] **`LoadController ctrl is null` 待正解**
      当前只是加了 null 保护让它不致命，启动日志里仍有两条 warn：
      ```
      LoadController transition to PREINIT failed (non-fatal)
      LoadController INIT phase transition (non-fatal)
      ```
      无 Forge mod 时 `Loader` 从未初始化。一旦放入真实 mod，
      这条路径必须真正走通，否则 mod 生命周期事件不会派发。
      入口：`LegacyModLifecycle.constructAll` / `Loader.instance().getModController()`

### P1 — 影响 mixin 正确性

- [ ] **49 处 `@Shadow` 目标在 MITE 中不存在**
      `@Shadow` 与 `@Overwrite` 一样会在 mixin 应用期硬失败。
      当前因 `InjectionConfig` 默认 `required=false` 被跳过并告警，
      意味着对应的 Forge hook **静默失效**。
      集中在：`PlayerInstance`(5)、`Packet51MapChunk`(3)、`ChunkProviderServer`(3)、
      `WorldServer`(2)、`WeightedRandomChestContent`(2)、`SlotFurnace`(2)。
      其中 2 项是 `fmlForge*`/`fmlPacket*` 前缀的 mixin 自建方法，属预期缺失。
      逐项清单：`bash tools/verify_overwrites.sh build/tmp/mite-named.jar`

- [ ] **`src/main/resources/mixin.refmap.json` 是过时产物**
      仓库里这份只有 60 条且缺 `ServerEntrypointMixin`；构建期生成的有 156 条。
      纯 `shadowJar` 会嵌入这份旧的（所以必须用 `-all-intermediary.jar`）。
      建议直接从仓库删除，避免误用。

### P2 — 清理

- [ ] `tasks.gradle` 里 ForgeGradle 式源码 patch 流水线（`applyForgePatches`、
      `compilePatchedSource`、`packagePatchedJar`）依赖从未提交的 `patches/minecraft/`，
      是死代码。删除或加注释标注废弃。
- [ ] `tasks.gradle` 末尾的 `-Xmaxerrs 2000` 是本会话为看清错误总数加的
      （javac 默认 100 会饱和），可保留也可回退。
- [ ] `ForgeSrgModRemapper` 目前是 identity passthrough 且标了 `@Deprecated`。
- [ ] `MixinConfigCreator` 是空 stub。
- [ ] 客户端从未启动测试过，只测了服务端。

---

## 本会话修掉的坑（避免重复踩）

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
| `RemapAccessWidener` | AW named → intermediary（本会话新建） |
| `NamedToIntermediaryTinyGenerator` | 生成 named2intermediary.tiny（切分逻辑本会话重写） |
| `LoaderRemapper` | loader jar named → intermediary（本会话加 classpath + MixinExtension） |
| `MixinRefmapGenerator` | 生成 refmap（本会话补全 `@At` 全引用形式） |

运行时（`src/main/java/net/xiaoyu233/fml/`）：
| 文件 | 本会话改动 |
|------|-----------|
| `relaunch/Launch.java` | 修命名空间；新增 launchwrapper 桥接 |
| `classloading/LaunchwrapperBridge.java` | 新建，委托给 KnotClassLoader |
| `classloading/KnotClassDelegate.java` | package definition 读 Manifest 属性 |
| `modfixer/LegacyModLifecycle.java` | 惰性 FMLCommonHandler；ctrl null 保护 |
| `FishModLoader.java` | AW 改用 intermediary 命名空间 |
