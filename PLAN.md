# FishModLoader forge-compat 改造规划

分支：`classloader-patch`（基于 `forge-compat`）
最新进展：Forge Access Transformer 运行时支持（已提交）

## 目标

让 FishModLoader 能正确加载小型 Forge 1.6.4 mod（含 `@Mod` 注解的单 jar mod），
不依赖原版 ForgeGradle 的 binpatch 流水线，全部通过 Mixin 实现对 Minecraft/MITE 类的改造。

---

## 已完成（本分支）

### 0. 构建可验证性（新增，前置条件）

| 文件 | 改动 |
|------|------|
| `tools/main/java/.../RemapToIntermediary.java` | **新建**：`tasks.gradle` 的 `remapMiteToIntermediary` 任务一直引用这个类，但 tools/ 里从未存在，导致该任务必然失败、整条验证链断掉。按 `LoaderRemapper` 的写法补全（official → intermediary，v1 tiny）。注意不能加 `ignoreFieldDesc(true)`，否则 `st` 类同名字段冲突报 `duplicate field` |
| `tools/verify_overwrites.sh` | **新建**：静态校验 forge_compat 里所有 `@Overwrite`/`@Shadow` 目标是否真实存在于 MITE 类上（含父类链） |
| `tools/main/java/.../RemapIntermediaryToNamed.java` | **新建**：产出编译所需的 **named** 命名空间 jar（intermediary → named，v2 tiny） |
| `tasks.gradle` | 新增 `remapMiteToNamed` 任务；`applyAccessWidener` 改为消费 named jar 而非原始 official jar；提高 javac 错误上限到 2000（默认 100 会饱和、掩盖真实进展） |

> `libs/1.6.4-MITE.jar` 就是 official 命名空间的 MITE-HDS.jar（默认包 `a.class` 等）。
> 该文件被 .gitignore 忽略，不入库。

### 关键修复：编译类路径命名空间错误

原 `applyAccessWidener` 直接吃 **official** jar 产出 `widen.jar` 作为 compileClasspath，
但源码和 `fishmodloader.accesswidener` 都是 **named** 名 —— 结果 widen 了 **0** 个类，
且编译器无法解析任何 `net.minecraft.*`。

正确的命名空间链路：

```
libs/1.6.4-MITE.jar        official（a.class ...）
  → mite-intermediary.jar  via intermediary.tiny  (official → intermediary)
  → mite-named.jar         via named.tiny         (intermediary → named)
  → widen.jar              via fishmodloader.accesswidener
```

修好后 AW 正常加载 86 个目标，编译错误数从「全部无法解析」降到可量化的 1094 条。

`RemapIntermediaryToNamed` 需要两个特殊处理：

1. **`ignoreFieldDesc(true)`** —— `named.tiny` 存的是 *official* 字段描述符（`[Laqz;`），
   而输入 jar 已是 intermediary（`[Lnet/minecraft/block/Block;`）。描述符不匹配会
   静默跳过 `Block.field_71973_m → blocksList` 等字段。
   实测：不加 2230 条错误，加了 1094 条 —— 净收益明显，保留。
2. **跳过冲突映射项** —— `named.tiny` 想把 `field_71322_p` 改名为 `playersOnline`，
   但 MITE 的 `MinecraftServer` 本就有 `playersOnline` 字段，属 tinyremapper
   不可修复冲突，会中断整个构建。已在生成编译用 jar 时过滤该条。

### 重要：命名空间陷阱

校验时必须注意三层命名空间，否则会产生大量误报：

- Mixin 源码写的是 **named**（`blockID`、`canBlockStay`）
- 重映射后的游戏 jar 是 **intermediary**，其中多数成员仍是 SRG 名（`field_71990_ca`、`func_71854_d`）
- `named.tiny` 提供 intermediary → named 映射

直接用 named 名去 jar 里 grep 会误报约 298 项；先经 `named.tiny` 翻译再比对后降到 81 项。

### 关键发现：`named.tiny` 不等于 MITE 实际 API

`named.tiny` 描述的是**原版 1.6.4**。它声称 BlockCactus/BlockFlower/BlockReed 有
`canBlockStay`（映射为 `func_71854_d`），但在实际 MITE jar 中，
`func_71854_d` 在**所有** block 类里都不存在。

MITE 删除了整套原版方块放置 API，改用：
`isLegalAt` / `isLegalOn` / `onNotLegal` / `dropBlockAsEntityItem(BlockBreakInfo)`

**以实际 jar 为准，不能以 `named.tiny` 为准。**

### 1. forge_compat Mixin：@Overwrite 目标缺失修复

`@Overwrite`/`@Shadow` 在目标成员缺失时会在 mixin 应用期**硬失败**。
经 `verify_overwrites.sh` 核实，以下 9 个 Mixin（来自原 commit f78f589，非本分支引入）
用 `@Overwrite`/`@Shadow` 指向 MITE 已删除的 API，已全部降级为惰性的 `@Unique`：

`BlockButton`、`BlockLadder`、`BlockLever`、`BlockMushroom`、`BlockSnow`、
`BlockTorch`、`BlockTripWireSource`、`BlockPumpkin`、`BlockTrapDoor`

涉及方法：`canBlockStay`、`canPlaceBlockAt`、`canPlaceBlockOnSide`、
`quantityDropped`、`isValidSupportBlock`
（`isValidSupportBlock` 原本还是带方法体的 `@Shadow`，本身即非法用法）

共转换 20 处注解。

除上述 9 个文件外，又核实并修正 4 处 `@Overwrite`：
`BlockBaseRailLogic::updateRailMetadata`、`BlockDoor::isOpaqueCube`
（MITE 改用 `isStandardFormCube`）、`BlockSand::canFallAbove`、
`EntityPlayerMP::getDefaultEyeHeight`。

校验结果：缺失项 **81 → 49**，**所有 `@Overwrite` 硬失败已清零**。
剩余 49 项均为 `@Shadow`，同样会在 mixin 应用期失败，需后续逐项处理。

> 校验命令：`bash tools/verify_overwrites.sh build/tmp/mite-named.jar`
> （推荐用 named jar，它与 mixin 源码命名空间一致；用 intermediary jar 结果相同，但需依赖脚本内部翻译）

> 更正：本分支早先曾把 `BlockCactus`/`BlockFlower`/`BlockReed`/`BlockCrops`
> 的 `@Unique` 改成 `@Overwrite`（commit 92df71d），这是**错误**的——
> 经 remapped jar 核实，这些方法在 MITE 中同样不存在，`@Overwrite` 会导致
> 运行时失败。已回滚为 `@Unique`。

### 2. classloader 完善

| 文件 | 改动 |
|------|------|
| `KnotClassDelegate.java` | 修复 package definition stub：从 Manifest 读取 sealed/spec/impl 属性，替换原来的 null 占位符 |
| `LaunchwrapperBridge.java` | **新建**：继承 `net.minecraft.launchwrapper.LaunchClassLoader`，将所有类加载委托给 KnotClassLoader，使 Forge mod 通过 `net.minecraft.launchwrapper.Launch.classLoader` 能拿到真实可用的 classloader |
| `Launch.java` | 在 KnotClassLoader 就绪后调用 `initLaunchwrapperBridge`：把 `net.minecraft.launchwrapper.Launch.blackboard` 指向 FishModLoader 的同名 Map，并创建 `LaunchwrapperBridge` 赋给 `Launch.classLoader` |

### 2. forge_compat Mixin patch 修复（@Unique → @Overwrite）

以下4个 Mixin 的方法原本用 `@Unique` 标注，导致 Mixin 将方法重命名为
`fishmodloader$xxx`，原始类中的方法保持不变——等价于 patch 未生效。
已全部改为 `@Overwrite` 以正确替换目标方法：

| Mixin | 方法 | 说明 |
|-------|------|------|
| `BlockCactusMixin` | `canBlockStay` | Forge patch：仙人掌只能生长在沙子或另一块仙人掌上 |
| `BlockFlowerMixin` | `canBlockStay` | Forge patch：花需要足够光照或能看到天空才能维持 |
| `BlockReedMixin` | `canBlockStay` | Forge patch：甘蔗允许在相邻水格旁的土/草/沙地上生长，null-safe 检查 |
| `BlockCropsMixin` | `getBlockDropped` | Forge patch：成熟作物的幸运附魔额外掉种子 |

## 编译现状（已实测）

历史记录：当时 `./gradlew compileJava` 尚有 **66** 条错误；当前已通过（本轮 JDK 17 验证）。

演进过程（每一步都实测）：

| 阶段 | 错误数 |
|------|--------|
| 初始（compileClasspath 是 official jar，全部无法解析） | 全量失败 |
| 修好 official→intermediary→named 链路 | 2230 |
| 加 `ignoreFieldDesc(true)` | 1094 |
| 补全 fabric mappings 工具类 | 1082 |
| 翻译 named.tiny 的 official 描述符 | **66** |

最后一步是关键：named.tiny 的成员描述符存的是 official 命名空间，
导致 tiny-remapper 的 name+desc 匹配几乎全部失效。翻译 8864 个描述符后
错误数下降 94%。

剩余 66 条分布于 19 个文件，全是**原有问题**（MITE API 与 vanilla Forge 分歧），
已通过 git stash 对比基线证实：有/无本分支改动，出错文件完全相同，**零回归**。

本分支所改文件的自身错误数（单独 javac 验证）：

| 文件 | 自身错误 |
|------|---------|
| `LaunchwrapperBridge.java` | 0 |
| `KnotClassDelegate.java` | 0 |
| `BlockButton/Lever/Ladder/TripWireSource/Pumpkin/TrapDoor/Mushroom/Cactus/Flower/Crops` Mixin | 0 |
| `BlockTorch` Mixin | 4（基线也是 4）|
| `BlockSnow` Mixin | 1（基线也是 1）|
| `BlockReed` Mixin | 1（基线也是 1）|

后三者的错误均在本分支**未触碰的方法体**内，属 MITE API 分歧：
`World.setBlockMetadataWithNotify`、`World.getSavedLightValue`、`World.getBlockMaterial` 均不存在。

### 剩余 66 条错误的分布

已解决：缺失源码包（`FilteringMappingVisitor`、`MixinIntermediaryDevRemapper`
已补全，未使用的 `IntermediaryMappingProvider` import 已删）。

剩余全部是 MITE API 与 vanilla Forge 1.6.4 的分歧，Top 文件：

| 文件 | 错误 |
|------|------|
| `forge_compat/BlockTorchMixin` | 8 |
| `cpw/.../network/FMLNetworkHandler` | 8 |
| `net/minecraftforge/fluids/BlockFluidFinite` | 6 |
| `net/minecraftforge/common/WorldSpecificSaveHandler` | 6 |
| `net/minecraftforge/common/ForgeHooks` | 6 |
| `net/minecraftforge/fluids/BlockFluidClassic` | 4 |
| `cpw/.../network/NetworkRegistry` | 4 |

典型缺失 API：`World.setBlockMetadataWithNotify`、`World.getSavedLightValue`、
`World.getBlockMaterial`、`Block.idDropped`、`EntityItem.lifespan`。
需逐个映射到 MITE 的对应写法，超出本次目标范围。

---

### 3. 其余待核实项（verify_overwrites.sh 报告）

脚本仍报 81 项缺失，其中 6 项是 `fmlForge*`/`fmlPacket*` 前缀的
mixin 自建辅助方法（本就不该存在于 jar，属预期）。其余需逐项人工确认，
典型分组：

- `idDropped`（BlockOre / BlockRedstoneOre）— MITE 改用 `dropBlockAsEntityItem`
- `Packet51MapChunk` / `Packet56MapChunks` / `PlayerInstance` 字段 — 类本身在 jar 中，字段名已变
- `WorldServer::isRaining` / `resetRainAndThunder`、`FurnaceRecipes::metaSmeltingList` 等

完整清单：`bash tools/verify_overwrites.sh`

---

## 待完成（后续优先级排序）

### P0 — 必须（当前阻塞项）

- [x] ~~补齐缺失源码包~~ 已完成（`FilteringMappingVisitor`、
  `MixinIntermediaryDevRemapper`）

- [x] **历史 66 条 MITE API 分歧**：当前 `compileJava` 已通过；上方分布表仅保留为历史记录。

- [x] **历史 49 处 `@Shadow` 目标缺失**：当前 `verifyOverwrites` 检查 340 项、缺失 0。

- [x] **`LaunchClassLoader.findClass` 死变量清理**
  `codeSource` 局部变量已删除，`defineClass` 统一使用 `getMetadata(...).codeSource`。
  （仍有未读取的 `signers` 局部变量，仅属后续清理，不影响行为。）

- [ ] **`net.minecraft.launchwrapper.Launch` 在 KnotClassLoader 侧的 Mixin**
  如果 launchwrapper 被 KnotClassLoader 加载（未被 AppCL 预先加载），
  则需要一个 `LaunchMixin` 在静态初始化时把 `blackboard` 指向 FishModLoader 的版本，
  防止模块加载顺序导致的二次创建。

- [ ] **`ForgeAccessTransformerImporter` 实际应用**
  已移除 AT→named AccessWidener 翻译，改由 `FMLClassTransformer` 在运行时修改 ASM access flags；已覆盖可见性、`+f`/`-f`、无 descriptor 字段、AT 文件发现，以及基于 `intermediary.tiny` 的 official → intermediary 类/字段/方法和方法描述符映射。无法精确映射或字段映射歧义会显式 warning 并拒绝，`probeForgeAccessTransformer` 已验证。仍需用真实带 AT mod 做服务端验证。

### P1 — 重要（影响常用 Forge API）

- [x] **`BlockComparatorMixin` 新方法接入**
  确认 `onNeighborBlockChange(World,int,int,int,int) boolean` 在 BlockRedstoneLogic/Block 中存在且签名匹配。
  `@Unique` 的 `onNeighborTileChange` / `weakTileChanges` 作为 Forge API 添加，无需 @Overwrite 路由。

- [x] **`BlockSnowMixin.isBlockReplaceable` / `quantityDropped`**
  确认 MITE BlockSnow/Block 中均不存在 canBlockStay/canPlaceBlockAt/isBlockReplaceable/quantityDropped，
  故以上方法保持 `@Unique`（Forge API 添加）是正确的。`updateTick` 的 getSavedLightValue 调用有效。

- [x] **`CraftingManager` Mixin**
  确认 MITE CraftingManager 有 getRecipeList() 和 addRecipe(ItemStack,boolean,Object...) 方法，
  GameRegistry / OreDictionary 的现有调用合法，无需修改。

- [x] **`GameRegistry` / `OreDictionary` 验证**
  CraftingManager API 兼容确认，OreDictionary.getRecipeList() 调用合法，compileJava 通过。

### P2 — 优化 / 后续

- [ ] 删除 `tasks.gradle` 中已无 `patches/` 目录的 ForgeGradle 流水线（`applyForgePatches`、
  `compilePatchedSource`、`packagePatchedJar` 等任务），或保留作为备用但加注释说明已废弃。
- [ ] `ForgeSrgModRemapper` 当前是 identity passthrough（`@Deprecated`），
  SRG → intermediary 实际映射可补全，但不影响现有 mod 加载（运行时已在 intermediary 命名空间）。
- [ ] 完善 `MixinConfigCreator`（现为空 stub）。
- [ ] 补全 `BlockNetherStalkMixin`、`BlockTorchMixin`、`BlockLadderMixin` 等
  其余 `@Unique` 方法的调用链，确认 Forge canBlockStay/isReplaceable 语义正确。

---

## 关键设计说明

### 为什么不用 ForgeGradle 源码 patch

`tasks.gradle` 里的 `applyForgePatches` 需要 `.patch` 文件和能运行的 MITE jar；
Mixin 方式在运行时字节码层面打补丁，不改动原始 jar，构建更简单，
且完全可追踪（每个改动对应一个 Mixin 类）。

### forge_compat Mixin 的加载方式

`FishModLoader.registerModloaderMixin` 用 `InjectionConfig.Builder` + `PackageLoader.getClasses`
递归扫描 `net.xiaoyu233.fml.reload.transform` 包（含 `forge_compat` 子包），
自动注册所有带 `@Mixin` 注解的类，**无需手动维护 `fishmodloader.mixin.json`**。

### classloader 层次

```
AppClassLoader
  └─ KnotClassLoader (SecureClassLoader)
       └─ DynamicURLClassLoader (URLClassLoader)  ← 实际 URL 存放处
       KnotClassDelegate                           ← 委托实现
         ├─ getMixinTransformer()                  ← Mixin 变换
         ├─ FMLClassTransformer                    ← AccessWidener + AsmTransformer
         └─ ForgeSrgModRemapper                    ← SRG 重映射（当前 identity）
  LaunchwrapperBridge (LaunchClassLoader)         ← 仅作为 Launch.classLoader 兼容桥接
       └─ delegates to KnotClassLoader
```
