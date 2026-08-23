# Fish Mod Loader
A simple Mixin-driven  mod loader for MITE1.6.4 R196

## 构建 Release

使用 Java 17 运行：

```bash
./gradlew clean release
```

发布文件会生成在 `build/libs`：

* `FishModLoader-v<版本>.jar`
* `FishModLoader-v<版本>-installer-universal.jar`
* `1.6.4-MITE-HDS_FMLv<版本>.jar`

## For migrating from v1.x to v2.x
* There is a mod migrating tool in `net.xiaoyu233.fml.util.ModRemapper`. You can use this to migrate your compiled mod file quickly
* Remapping for source code is still in developing. You can currently use the migrated jar file as a reference to modify source code
* (Or just decompile the migrated jar to use as the new source)
