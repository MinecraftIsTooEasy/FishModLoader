package net.xiaoyu233.fml.forge.event;

/**
 * Package marker for Forge event Mixins. The presence of this class lets
 * {@link net.xiaoyu233.fml.config.InjectionConfig.Builder#of} use
 * {@code MixinPackage.class.getPackage()} to discover every Mixin sitting
 * next to it.
 *
 * <p>The Mixins in this package translate Forge 1.6.4's event-bus posts
 * (the rough equivalent of the patches that lived in
 * {@code patches/minecraft/...}) into {@code @Inject} hooks on the
 * matching vanilla / MITE classes.
 */
public final class MixinPackage {
    private MixinPackage() {}
}
