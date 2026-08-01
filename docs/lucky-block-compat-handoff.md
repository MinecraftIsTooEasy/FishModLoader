# Lucky Block 4.2.1 真实客户端兼容交接

状态：**FAIL（能加载和注册，但核心功能不可用）**
最后人工验收：2026-08-01，Windows / Zulu JDK 17 / MITE-HDS R196
本地样本：`run/mods/lucky-block-forge-1.6.4-1.0.jar`
SHA-256：`97548719a2370da47b4c078911b14a566e1e08066eb8b26ec4da1d4c68d84be7`

> 第三方 jar 被 `.gitignore` 忽略，不得提交、复制到 fixture 或再分发。

## 1. 用户最终人工验收结果

Lucky Block 已出现在创造模式背包，但仍不能视为兼容：

1. 方块显示为紫黑缺失纹理；
2. 方块可以放置；
3. 敲掉后只掉落 Lucky Block 本体；
4. 没有触发 Lucky Block 的随机奖励/事件行为。

因此当前结果不是 PASS，也不是“仅视觉问题”，而是核心玩法路径失败。按 `PLAN-NEXT.md` 状态定义应记为 **FAIL**。

## 2. 已经解决的链路

当前工作树已经实现并接线 legacy Forge official → intermediary JAR 预重映射：

- `LegacyForgeModRemapper`：namespace 扫描、缓存、原子发布、输出验证；
- `ForgeModDiscoverer`：source/runtime 双路径，只有 runtime jar 加入 Knot；
- AT 从 source jar 导入，coreplugin 使用 runtime jar；
- `KnotClassDelegate` 已移除旧的按类 `ForgeSrgModRemapper.remapClass`；
- synthetic probe 和 official mod 服务端生命周期 E2E 已加入 Gradle；
- `GameRegistry` 与 AW 有若干 MITE API 适配。

真实 Lucky Block 最新客户端日志证明以下阶段已通过：

```text
Forge mod namespace: OFFICIAL
Constructed Forge mod: lucky v4.2.1 (3 lifecycle handler(s))
Forge mod construction complete: 1 active mod(s)
Dispatching ... FMLPreInitializationEvent ... lucky
Dispatching ... FMLInitializationEvent ... lucky
Dispatching ... FMLPostInitializationEvent ... lucky
Dispatched Forge INIT/POSTINIT/AVAILABLE to 1 mod(s)
```

之前逐步修掉的真实错误包括：

- `NoClassDefFoundError: aqz`；
- `NoSuchFieldError: d`（`Material.d` 等 official 成员未 remap）；
- `NoSuchMethodError: Block.<init>(int, Material)`；
- 插入 `BlockConstants` 后的 `VerifyError: Operand stack overflow`；
- `IllegalAccessError: Block.func_71848_c(float)`；
- `ItemBlock.<init>(int, Block)` 与 MITE `ItemBlock(Block)` 差异；
- `GameData` 缺少 MITE ItemBlock 的 `ItemData`；
- 普通 mod Block 被错误强制转换为 `BlockProxy`。

## 3. 当前决定性失败证据

### 3.1 缺失纹理

`run/logs/latest.log`：

```text
Missing resource: textures/blocks/MISSING_ICON_TILE_850_blockLucky.png
```

但 source 和 remapped runtime jar 都确实包含：

```text
assets/lucky/textures/blocks/blockLucky.png
```

这说明不是 remapper 丢资源，而是纹理注册/ResourceLocation namespace 没有正确走 `lucky:blockLucky`。优先追踪：

- `mod.lucky.client.ClientProxy` 和 `mod.lucky.BlockLucky` 的纹理注册调用；
- `TextureMapMixin` / `TextureAtlasSpriteMixin` / IconRegister Forge shim；
- `Block.setTextureName`、`registerBlockIcons`、`getIcon` 兼容路径；
- 当前为何生成 `MISSING_ICON_TILE_850_blockLucky`，而不是查找 `lucky:textures/blocks/blockLucky.png`。

不得通过把第三方 PNG 复制到 Minecraft 默认资源域来冒充兼容；应修复 Forge 资源域语义，并用自建资源 fixture 回归。

### 3.2 敲掉只掉本体，不执行幸运事件

Lucky Block 的 `mod/lucky/BlockLucky.class` 已 remap 并加载，但人工行为表明预期破坏回调未执行，或执行后退化到 MITE 默认掉落。

优先检查 remapped runtime class：

```bash
javap -classpath run/.fml/remappedForgeMods/<最新缓存>.jar -p -c mod.lucky.BlockLucky
```

重点对照：

- Lucky Block 发布物覆盖的是哪个 1.6.4 vanilla/Forge 回调（当前可见 `func_71893_a(...)` 等）；
- MITE `net.minecraft.block.Block` 实际破坏/收获调用链；
- `BlockMixin`、`ForgeHooks`、`ForgeEventFactory` 是否调用 legacy Forge 回调；
- method name remap 是否把 override 映射成了一个在 MITE 中存在但不会被实际破坏路径调用的方法；
- `MakeLuckyDrops` / `LuckyDrop` / `SpawnEntity` 是否真正进入；
- 客户端与服务端侧是否错误，事件是否只在 server side 执行；
- MITE 默认 drop 是否在幸运逻辑前后仍被执行。

需要在 loader 中补 bridge/hook，而不是修改 Lucky Block jar。增加 synthetic 方块 fixture：覆盖同一个 legacy Forge 回调，放置并破坏后输出确定 marker，自动断言回调被调用且默认本体掉落被正确取消/替换。

## 4. 当前工作树中的高风险未完成代码

当前没有 commit，且工作树原本就有文档改动。必须先运行 `git status --short` 并保留所有已有内容。

最新真实 mod 修复涉及：

- `src/main/java/net/xiaoyu233/fml/modfixer/LegacyForgeModRemapper.java`
- `src/main/java/net/xiaoyu233/fml/modfixer/LegacyForgeModRemapperProbe.java`
- `src/main/java/cpw/mods/fml/common/registry/GameRegistry.java`
- `src/main/resources/fishmodloader.accesswidener`
- `src/main/java/net/xiaoyu233/fml/modfixer/ForgeModDiscoverer.java`
- `src/main/java/net/xiaoyu233/fml/modfixer/LegacyModLifecycle.java`
- `src/main/java/net/xiaoyu233/fml/classloading/KnotClassDelegate.java`
- `tasks.gradle`

特别注意：

1. `LegacyForgeModRemapper` 现在有一个 remap 后 ASM 补偿 pass，对 TinyRemapper 未处理的成员名做 exact/唯一签名 fallback。必须审计误映射风险，不能因 Lucky Block 恰好启动就视为通用正确。
2. 该 pass 还专门把 legacy `Block(int, Material)` 调用改写为 MITE `Block(int, Material, new BlockConstants())`。需增加专门 probe，机械断言 descriptor、插入指令和 max stack。
3. `GameRegistry` 现在优先使用 MITE `ItemBlock(Block)`，显式调用 `GameData.newItemAdded`，并将 `blockRegistry` 从 `BlockProxy` 改为真实 `Block`。需检查所有调用方和存档 ID 语义。
4. AW 新公开了 `Block.setHardness`、`setResistance`、`setStepSound`。这是 Forge API 兼容需要，但需确认范围和 runtime remapped AW。
5. 最近两次 S3AI Claude 审核尝试因 runner/工具环境故障，没有得到可信审核结论。不得写成“审核通过”。下一个对话修复功能后必须重新用可工作的 S3AI Claude 或项目规则要求的异模型 Claude 做只读审核。

## 5. 当前自动验证基线

在最新修改后已通过：

```bash
./gradlew verifyForgeCompatibilityQuick
./gradlew verifyForgeCompatibilityIntegration
```

最近结果：

- quick：BUILD SUCCESSFUL；
- integration：BUILD SUCCESSFUL；
- game jar：Linked OK 4559 / DEFECTS 0；
- overwrite/shadow 静态检查：340，缺失 0；
- injection defects：0；
- AT server E2E：通过；
- synthetic official remap lifecycle E2E：通过。

这些只能证明基础链路无回归，**不能覆盖纹理或方块破坏功能**。

## 6. 下一个对话的执行顺序

1. 读取 `AGENTS.md`、`HANDOFF.md`、`PLAN-NEXT.md`、`docs/forge-mod-remap-plan.md` 和本文件；
2. `git status --short`，保护脏工作树，不 commit；
3. 从 `run/logs/latest.log` 的缺失纹理开始追踪 resource namespace；
4. 反编译/ASM 检查 source 与 runtime `BlockLucky`、`ClientProxy`，追踪纹理注册；
5. 追踪 MITE 实际方块破坏调用链，确定 Lucky override 为什么没执行；
6. 先做仓库自产的资源 fixture 和破坏回调 fixture，再改生产兼容层；
7. 运行 probe、quick、integration；
8. 清除 `run/.fml/remappedForgeMods/` 后重新 `buildJar` / `runClient`；
9. 人工验收必须同时满足：纹理正确、放置正常、敲掉触发非本体随机结果；
10. 用异模型 Claude 只读交叉审核并处置 findings；
11. 未经用户要求不要 commit，不得提交第三方 jar。

## 7. 状态判定

当前 Lucky Block 4.2.1：

- discovery：PASS
- official → intermediary remap：PARTIAL（足以加载，但通用成员 fallback 尚待审核）
- construction：PASS
- preInit/init/postInit：PASS
- block/item registration：PASS
- client texture：FAIL
- placement：PASS
- lucky break/drop behavior：FAIL
- 客户端总体：**FAIL**
- 服务端总体：NOT TESTED（真实 Lucky Block）

在纹理和幸运破坏行为都通过前，不得将该样本改成 PASS。
