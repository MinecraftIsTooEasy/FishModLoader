# FishModLoader

### v3.4.1
* 修复了无法读取.minecraft\assets\virtual\legacy\lang\下的语言文件的问题
* 加入了处于开发环境时自动开启MITE DEV模式的配置项(默认开)
* 重新内置guava-28.0-jre

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