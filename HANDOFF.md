# 交接文档 — FishModLoader Forge 兼容

分支：`classloader-patch`

状态：Forge 基础链路和 legacy Forge JAR 预重映射已完成；当前正在以 Lucky Block 4.2.1 推进真实成员/API 兼容。样本总体仍为 **FAIL**。

## 必读入口

- [`PLAN-NEXT.md`](PLAN-NEXT.md)：真实 Forge mod 兼容矩阵、状态定义与验收规则。
- [`docs/lucky-block-compat-handoff.md`](docs/lucky-block-compat-handoff.md)：当前真实样本、最新 blocker 和下一步操作。
- [`docs/forge-compat-history.md`](docs/forge-compat-history.md)：命名空间、AW、Mixin、classloader 历史与排障原则。

已实施完毕的 `docs/forge-mod-remap-plan.md` 已删除；不要再按旧计划从头实现 JAR remapper。

## 环境与不可破坏约束

- Windows / Zulu JDK 17 / MITE-HDS R196。
- 本地游戏 jar：`libs/1.6.4-MITE.jar`；运行产物必须使用 `build/libs/FishModLoader-*-all-intermediary.jar`。
- 第三方样本：`run/mods/lucky-block-forge-1.6.4-1.0.jar`，SHA-256 `97548719a2370da47b4c078911b14a566e1e08066eb8b26ec4da1d4c68d84be7`。
- 第三方 jar、remap 缓存和运行产物不得提交或复制进 fixture。
- 开始工作前先执行 `git status --short`。保留全部已有源码、工具和用户未跟踪文档；未经用户要求不要 commit。
- `run/.fml/remappedForgeMods/` 是运行缓存。改变 remap 规则时必须升级 schema 或清理缓存，并从日志确认使用了新 runtime jar。

## 当前工作树

交接时的生产改动包括：

```text
M  src/main/java/net/xiaoyu233/fml/modfixer/LegacyForgeModRemapper.java
M  src/main/java/net/xiaoyu233/fml/modfixer/LegacyForgeModRemapperProbe.java
M  src/main/java/net/xiaoyu233/fml/reload/transform/forge_compat/WeightedRandomChestContentMixin.java
M  src/main/java/net/xiaoyu233/fml/reload/transform/forge_compat/WorldMixin.java
M  tools/main/java/org/moddedmite/fish/faloom/NamedToIntermediaryTinyGenerator.java
?? src/main/java/net/xiaoyu233/fml/modfixer/LegacyForgeChestContentBridge.java
?? src/main/java/net/xiaoyu233/fml/modfixer/LegacyForgeBlockSandBridge.java
```

实际状态可能还有用户创建的未跟踪 Markdown；不要删除、覆盖或纳入本专项。

## 已走通的真实链路

Lucky Block 最新证据已证明：

- official namespace 判定和 JAR 预重映射；
- source/runtime 双路径、发现、构造和 active list；
- preInit/init/postInit；
- 方块/物品注册及资源包接入；
- 放置和 legacy harvest callback；
- 多种 `Chosen drop`，包括物品、实体、chest 和 falling-block 路径。

因此以下旧结论已经删除，不得恢复为当前状态：

- “official remap 尚待实施”；
- “Lucky Block 只掉本体、未执行幸运逻辑”；
- “纹理是当前首要 blocker”；
- “尚未开始任何真实第三方样本测试”。

纹理旧错误在最新日志中未复现，但视觉效果仍需人工确认，不能仅凭日志写 PASS。

## 当前已实现但未提交的兼容修复

1. `WeightedRandomChestContent.func_76293_a(...)` 精确改写到 loader-owned chest bridge。
2. `World.canPlaceEntityOnSide(...)` / `func_72931_a` 兼容入口，使用 MITE 放置查询语义。
3. `BlockSand.func_72191_e_(World,III)` 精确改写到 loader-owned vanilla `canFallBelow` bridge。
4. remapper probe 包含对应正例和 wrong-owner/wrong-descriptor 负例。

最近记录中 `verifyForgeCompatibilityQuick`、`verifyForgeCompatibilityIntegration` 和 `buildJar` 均曾通过；每次继续修改后必须重新运行，旧成功不能替代当前验证。

## 最新决定性 blocker

最新报告：

```text
run/crash-reports/crash-2026-08-01_22.39.01-server.txt
```

触发路径：

```text
Chosen drop: type=structure,name=anviltrap,relativeToPlayer=true
SpawnOther.spawnOther(SpawnOther.java:98)
java.lang.NoSuchFieldError: cm
```

最新 remapped Lucky jar 仍包含：

```text
GETSTATIC net/minecraft/block/Block.cm:Lnet/minecraft/block/Block;
```

映射和 MITE 实际字段指向：

```text
aqz.cm:Lamv; -> Block.field_82510_ck:BlockAnvil
```

而第三方字节码使用 `aqz.cm:Laqz;`。源字段 descriptor 为 `Block`，映射声明为更具体的 `BlockAnvil`，导致严格 `owner + name + descriptor` 映射漏命中。修复不能只改字段名；必须同时处理目标 descriptor，并验证类型安全。

## 下一步精确操作

1. 重新读取最新 `git status`、本文件和 Lucky 专项交接；保存当前 diff，不 commit。
2. 反编译 source/runtime `mod.lucky.drops.SpawnOther`，机械确认 `aqz.cm:Laqz;` 与 remapped `Block.cm:LBlock;`。
3. 核实 `Block.field_82510_ck` 的声明 owner、descriptor 和 anvil 语义。
4. 在 `LegacyForgeModRemapper` 增加严格受控的字段 descriptor 漂移处理：
   - 正常映射失败后才进入；
   - 候选必须在 owner/name 下唯一；
   - 校验源/目标字段类型可安全赋值；
   - 同时改写 owner、name、descriptor；
   - 不唯一或不安全时 fail closed，不按短名猜测。
5. 扩展 `LegacyForgeModRemapperProbe`：
   - 正例 `GETSTATIC aqz.cm:Laqz;` 必须变为正确目标字段和 descriptor；
   - wrong owner/name/descriptor、候选不唯一和类型不安全必须不误改或明确拒绝；
   - 输出不得残留 `Block.cm`。
6. 对最新 remapped Lucky jar 做完整游戏成员链接审计，按 `owner + name + descriptor` 找出下一个潜在 blocker，而不是等用户逐个触发。
7. 运行：

```bash
./gradlew probeLegacyForgeModRemapper
./gradlew verifyForgeCompatibilityQuick
./gradlew verifyForgeCompatibilityIntegration
./gradlew verifyForgeCompatibility
./gradlew buildJar
git diff --check
```

8. 清理 `run/.fml/remappedForgeMods/` 或确认 schema 已变化，重新运行客户端。
9. 真实验收至少覆盖普通物品、实体、chest、fallingblock 和 anviltrap；要求无新 crash report、未处理 `LinkageError` 或 server tick loop 退出。
10. 使用与实现方不同家族的 Claude 只读审核当前完整 diff，并由主代理统一处置 findings。

## 独立遗留问题

- 某些旧物品 ID 产生 `ItemStack.getItem() == null` NPE；当前被 Lucky 自身捕获，但功能结果失败。
- end portal frame 等 MITE 不可携带方块会产生错误日志。
- 最新运行日志中有 12 个非致命 Mixin 应用失败：`BlockChestMixin`、`BlockComparatorMixin`、`BlockLadderMixin`、`BlockLogMixin`、`BlockTorchMixin`、`EntityItemMixin`、`EntityLivingBaseMixin`、`EntityLivingMixin`、`EntityMinecartBaseMixin`、`EntityMooshroomMixin`、`EntityPlayerMixin`、`EntitySheepMixin`。多数为 runtime Shadow 目标未命中；`BlockLogMixin` 是 `@Overwrite updateTick` 未命中。当前统一标记为“待逐项核对真实 MITE API，修复或禁用”，不得建立无理由允许清单。静态“缺失 0”不代表这些 Mixin 已成功应用。
- `pack.mcmeta`、语言资源等资源包 metadata 警告仍需分类。

这些不能和 `NoSuchFieldError: cm` 混为一个补丁，也不能通过吞异常伪装兼容。

## 完成门槛

Lucky Block 只有在以下条件同时满足后才能从 FAIL 提升：

- 已知致命成员/API 路径均有自动回归；
- 真实客户端重复破坏测试无 crash report 和未处理链接错误；
- 核心随机掉落有可观察正确结果；
- 纹理视觉人工确认；
- quick、integration、full 均通过；
- 异模型只读审核无未处置 blocker。
