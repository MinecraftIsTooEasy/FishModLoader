# Lucky Block 4.2.1 真实兼容交接

状态：**FAIL（基础链路与多种随机掉落已工作，仍有致命 legacy 成员链接错误）**

- 最后失败证据：2026-08-01，Windows / Zulu JDK 17 / MITE-HDS R196
- 本地样本：`run/mods/lucky-block-forge-1.6.4-1.0.jar`
- SHA-256：`97548719a2370da47b4c078911b14a566e1e08066eb8b26ec4da1d4c68d84be7`
- 第三方 jar 被忽略，不得提交、复制到 fixture 或再分发。

## 当前状态矩阵

| 能力 | 状态 | 证据/边界 |
|---|---|---|
| namespace 判定与 JAR remap | PASS | `Forge mod namespace: OFFICIAL`，runtime intermediary cache |
| discovery / construction | PASS | `Constructed Forge mod: lucky v4.2.1`，1 active mod |
| preInit / init / postInit | PASS | 三段事件均已派发 |
| block/item registration | PASS | 内容已注册并可放置 |
| resource pack 接入 | PASS | Lucky Block resource pack 已注册并参与 reload |
| 纹理视觉 | NOT TESTED | 旧缺失纹理日志未再复现，仍需人工确认 |
| legacy harvest callback | PASS | `LegacyForgeBlockHarvestBridge` 已进入 |
| 随机掉落选择 | PASS | 多次出现物品、实体、chest、fallingblock 等 `Chosen drop` |
| 全部掉落执行 | FAIL | 部分 Item/Block 与 legacy 成员仍不兼容 |
| 客户端/集成服务端总体 | FAIL | `anviltrap` 触发未处理 `NoSuchFieldError: cm` |

## 已解决的真实 blocker

以下问题不再是当前待办：

- official 游戏类 `aqz` 无法加载；
- mod 无法构造或进入 active list；
- lifecycle 未执行；
- Lucky 方块只掉本体、未进入随机逻辑；
- legacy chest 四参数静态 API 的 private `IllegalAccessError`；
- `World.func_72931_a(...)` 缺失；
- `BlockSand.func_72191_e_(World,III)` 缺失。

当前未提交实现通过 loader-owned bridge、精确调用改写和显式 named→intermediary 映射处理上述后三项。不要回退正确的 namespace remap 或 harvest bridge。

## 最新致命失败

报告：

```text
run/crash-reports/crash-2026-08-01_22.39.01-server.txt
```

日志：

```text
Chosen drop: type=structure,name=anviltrap,relativeToPlayer=true
java.lang.NoSuchFieldError: cm
    at mod.lucky.drops.SpawnOther.spawnOther(SpawnOther.java:98)
    at mod.lucky.BlockLucky.func_71893_a(BlockLucky.java:109)
```

runtime class 中残留：

```text
GETSTATIC net/minecraft/block/Block.cm:Lnet/minecraft/block/Block;
```

映射中的对应声明为：

```text
FIELD aqz Lamv; cm field_82510_ck
```

即旧 mod 使用 `Block.cm:Block`，映射/运行时使用 `Block.field_82510_ck:BlockAnvil`。这是字段 descriptor 漂移，不是单纯缺少名称映射。JVM Fieldref 包含 descriptor，只改名仍会链接失败。

## 当前工作树专项文件

```text
src/main/java/net/xiaoyu233/fml/modfixer/LegacyForgeModRemapper.java
src/main/java/net/xiaoyu233/fml/modfixer/LegacyForgeModRemapperProbe.java
src/main/java/net/xiaoyu233/fml/modfixer/LegacyForgeChestContentBridge.java
src/main/java/net/xiaoyu233/fml/modfixer/LegacyForgeBlockSandBridge.java
src/main/java/net/xiaoyu233/fml/reload/transform/forge_compat/WeightedRandomChestContentMixin.java
src/main/java/net/xiaoyu233/fml/reload/transform/forge_compat/WorldMixin.java
tools/main/java/org/moddedmite/fish/faloom/NamedToIntermediaryTinyGenerator.java
```

工作区可能还有用户未跟踪文档。开始前必须以 `git status --short` 为准，禁止清理或覆盖不属于本专项的文件。

## 下一实现任务

### 1. 字段 descriptor 漂移兼容

优先实现严格、通用且可诊断的字段 fallback：

- 仅在正常 owner/name/descriptor 映射失败后执行；
- 以 source owner + source field name 收集映射候选；
- 候选必须唯一；
- 目标字段必须在真实 intermediary game jar 中存在；
- 对 `GETSTATIC` 验证目标类型可安全用于旧调用期望类型；
- 同时重写 field name 和 descriptor；
- 写访问、候选不唯一、类型不兼容时 fail closed。

当前正例预期：

```text
GETSTATIC Block.cm:Block
→ GETSTATIC Block.field_82510_ck:BlockAnvil
```

禁止按 `cm` 全局替换、向 Block 注入伪字段或让 Lucky 捕获 `Throwable`。

### 2. 自动回归

扩展 `LegacyForgeModRemapperProbe`：

- fixture 生成 `GETSTATIC aqz.cm:Laqz;`；
- 输出必须是正确 intermediary owner/name/descriptor；
- wrong owner/name/descriptor 不得误改；
- 候选不唯一和不可赋值类型必须拒绝；
- 机械扫描输出不得残留目标 official/短字段引用。

增加对 remapped mod 全部游戏成员引用的静态链接检查，避免继续依赖人工随机触发逐个发现 ABI blocker。

### 3. 验证

```bash
./gradlew probeLegacyForgeModRemapper
./gradlew verifyForgeCompatibilityQuick
./gradlew verifyForgeCompatibilityIntegration
./gradlew verifyForgeCompatibility
./gradlew buildJar
git diff --check
```

然后清理 remap 缓存或升级 schema，运行真实客户端，至少覆盖：

- 普通物品掉落；
- 实体掉落；
- chest；
- fallingblock；
- anviltrap。

要求：没有新 crash report、`NoSuchFieldError`、`NoSuchMethodError`、`IllegalAccessError` 或 server tick loop 退出。

## 独立问题

### 无效 ItemStack

部分旧 ID（如工具组）会使 `ItemStack.getItem()` 返回 null，并在 `EntityItem` 构造时 NPE。Lucky 会捕获该异常，因此不是最新 server crash 根因，但对应掉落失败，后续必须按 ID/注册表语义单独处理。

### 不可携带方块

end portal frame 等 MITE 方块不能作为 `EntityItem` 携带，会产生错误日志。不要通过全局吞错解决，应建立明确过滤、替代或不支持策略。

### Mixin 与资源警告

运行时仍有若干非致命 Mixin warning、`pack.mcmeta` 和语言资源警告。它们不属于 `cm` 字段补丁，但在最终 PASS 前必须分类。

## 状态提升条件

在以下条件完成前保持 **FAIL**：

1. `cm` descriptor 漂移修复有正负自动回归；
2. remapped mod 游戏成员静态链接审计通过；
3. 真实客户端多路径随机掉落无未处理链接错误；
4. 纹理人工确认；
5. quick/integration/full 通过；
6. 异模型 Claude 对完整未提交 diff 的 findings 均已处置。
