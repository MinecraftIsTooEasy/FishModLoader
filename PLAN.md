# FishModLoader forge-compat 改造规划

分支：`classloader-patch`（基于 `forge-compat`）

## 目标

让 FishModLoader 能正确加载小型 Forge 1.6.4 mod（含 `@Mod` 注解的单 jar mod），
不依赖原版 ForgeGradle 的 binpatch 流水线，全部通过 Mixin 实现对 Minecraft/MITE 类的改造。

---

## 已完成（本分支）

### 1. classloader 完善

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

---

## 待完成（后续优先级排序）

### P0 — 必须（影响 mod 能否运行）

- [ ] **`LaunchClassLoader.findClass` 死变量清理**
  `untransformedName` 行的 `codeSource` 局部变量算完后没有传给 `defineClass`，
  `defineClass` 实际用的是 `getMetadata(...).codeSource`，两者不一致。
  应合并：删除 `codeSource` 局部变量，统一走 `getMetadata`。

- [ ] **`net.minecraft.launchwrapper.Launch` 在 KnotClassLoader 侧的 Mixin**
  如果 launchwrapper 被 KnotClassLoader 加载（未被 AppCL 预先加载），
  则需要一个 `LaunchMixin` 在静态初始化时把 `blackboard` 指向 FishModLoader 的版本，
  防止模块加载顺序导致的二次创建。

- [ ] **`ForgeAccessTransformerImporter` 实际应用**
  当前 `ForgeModDiscoverer` 已调用 `ForgeAccessTransformerImporter.importFrom(jarPath)`
  但 `ForgeAccessTransformerImporter` 的实现需要验证能否正确扩展 AccessWidener。

### P1 — 重要（影响常用 Forge API）

- [ ] **`BlockComparatorMixin` 新方法接入**
  `onNeighborTileChange`/`weakTileChanges`/`onNeighborBlockChange` 用 `@Unique` 正确
  添加了新方法，但需要确认 `BlockComparator` 的 `updateTick`/`onNeighborBlockChange`
  是否已通过 `@Inject` 或 `@Overwrite` 路由到这些方法。

- [ ] **`BlockSnowMixin.isBlockReplaceable` / `quantityDropped(int, int, Random)`**
  `@Unique` 正确添加 Forge 新重载，但 `BlockSnow` 中 `quantityDropped(Random)` 已被
  `@Overwrite` 替换为返回 1（元数据掉落已被 `@Unique` 重载覆盖）；需要确认调用点。

- [ ] **`CraftingManager` Mixin**
  Forge 为 `CraftingManager` 添加了 `getRecipeList()` 等方法，
  部分 mod 会在 PreInit 时调用它添加合成配方事件。需要检查是否已有对应 Mixin。

- [ ] **`GameRegistry` / `OreDictionary` 验证**
  确认 `GameRegistry.registerBlock` / `registerItem` / `addRecipe` 链路
  能正确触发 Forge 事件而不 NPE。

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
