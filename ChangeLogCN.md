# FishModLoader

### v4.0.0
* 加入了`IdAllocator`类
  + 可运用于方块、物品、实体、附魔、状态效果、成就、生物群系、维度、世界类型、数据包、村民职业
    - 其余id暂不考虑修改(待商榷)
  + 根据modId的哈希值自动配置id范围
  + 自动生成id配置文件
  + 自动解决id范围冲突
    -IdUtil.getNextItemID()与IdUtil.getNextBlockID()改为弃用状态

```java
    public static IdAllocator EXAMPLE_ALLOCATOR = new IdAllocator("modid").setCount(IdType.BLOCK, 16).setCount(IdType.ITEM, 64);
    public static Block EXAMPLE_BLOCK = new BlockOreStorage(allocator.getBlockId("example"), Material.example);
```

* 修改构建系统为Gradle 9.5.1
* 升级Mixin版本为0.8.7
* 升级ASM全家桶版本为9.9.1
* 最低java支持版本改为java21(待商榷)
  * 加入了不支持的java版本提示(NYI)
* 加入了Fabric模组支持，但能正常加载的仅限不依赖于Minecraft本体的极少数模组
* 修复了`类文件输出目录`的默认配置在非Windows系统路径错误的问题
* 修复了找不到json格式语言文件报错的问题
  + 加入ModResourceManager.ableToJsonLang(namespace)用于允许哪些namespace使用json格式语言文件
* MITE可被识别为一个单独的mod，版本为版本号去除“R” ~~~什么vR196~~~

#### **_NYI or Plans_**

* 菜单界面加入与主界面一致的fml版本显示
  + 点击进入Credits界面
* 修改混淆映射表为分包的MCP
* 将大多数依赖库改为外置
* 多语言支持（包括安装器，配置文件等）
* 集成字符长度修复
* 集成mite网站修复
* 添加FishModLoader的图标
* 重置namespace系统，使用ResourceLocation
* lwjgl3支持
* 加入了Forge模组支持
  + Forge事件系统
  + 矿物词典
  + Forge配置系统
* 重置安装器，支持安装任意版本fml
  + 安装fml改为新建一个版本实例，不占用原先的实例
* fml-loom
* 改名FishLoader避免歧义(待商榷)
* 软件包修改，代码优化
  * 外部可用类移至net.xiaoyu233.fml.api(或org.moddedmite.fishloader.api)包下
* 自动注册资源域名

---

### v3.4.2
* 现在只有英特尔GPU才能激活渲染修复
* 加入了ItemStack的Namespace
* 修复了部分情况下无法读取json语言文件的问题
* 修复了附魔重复注册的问题
* 修复了处于开发模式下，只能获取汤煲厌食的问题

---

### v3.4.1
* 加入了处于开发环境时自动开启MITE DEV模式的配置项(默认开)
* 重新内置guava-28.0-jre
* 将内置log4j版本升为2.24.3以避免漏洞和异常
* 重写了SoundsRegisterEvent, 允许注册音效, 唱片, 音乐
* 修复了无法读取.minecraft\assets\virtual\legacy\lang\下的语言文件的问题
* 修复了读取的json语言文件与当前选择语言不符的问题

---

### v3.4.0
* 完善了输出类加载信息,现在可输出class_tinker与access_widener
* 完善了Class Tinkerers,现在可在mod中向原有的enum添加内容
* 支持mod自定义assets,需要在init中写入`ModResourceManager.addResourcePackDomain("modid")`,方块物品需要在icon字符串最前面插入`modid:`
* 支持读取json格式的语言文件
* 注册物品/方块允许单独定义Unlocalized Name和不定义Resource Location & Unlocalized Name
* 移除了一些无用类与无用注释
* 修复了用户MITE版本错误的问题
* 修复了mixin out无效的问题
* 修复MITE自身的bug(来自Debris与黎明的曙光)
  * packet read string字符串长度解禁
  * tnt复制(可配置,默认开启)
  * 服务器掉入虚空崩溃
  * 丢失成就导致坏档与nbt的安全读取
  * 反作弊导致部分方块变为近似方块或实体消失
  * 掉落的沙子崩溃
  * LongHashMap导致的加载区块卡顿

---

### v3.3.3
* 配置文件目录修改为.minecraft/config
* MITE的modid修改为minecraft,版本修改为1.6.4-mite(可能导致部分mod不可用)
* 修复了进服务器无法触发PlayerLoggedInEvent的问题
* 为EnumExtends添加了EnumOptions