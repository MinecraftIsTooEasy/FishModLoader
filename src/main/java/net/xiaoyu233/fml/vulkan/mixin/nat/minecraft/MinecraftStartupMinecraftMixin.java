package net.xiaoyu233.fml.vulkan.mixin.nat.minecraft;

import net.minecraft.*;
import net.minecraft.client.main.Main;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Face;
import net.xiaoyu233.fml.vulkan.render.state.DepthFunc;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import net.xiaoyu233.fml.vulkan.window.NativeWindow;
import org.lwjgl.LWJGLException;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.DisplayMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Native area "minecraft" (Startup group, NR-07): game start-up, display-mode update and the loading screen on the native
 * window and the {@code Draw}/{@code Frame} state setters instead of LWJGL {@code Display} and GL11.
 *
 * <p>{@code scaledTessellator} has no GL call (Tessellator is migrated by NR-06) and {@code macDisplayModes} only uses the
 * NR-05 {@link DisplayMode} shim, so both stay untouched.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftStartupMinecraftMixin {
    @Shadow public GameSettings gameSettings;
    @Shadow public int displayWidth;
    @Shadow public int displayHeight;
    @Shadow private boolean fullscreen;
    @Shadow @Final private File fileAssets;
    @Shadow @Final public File mcDataDir;
    @Shadow @Final public File saves_dir_MITE;
    @Shadow @Final private File fileResourcepacks;
    @Shadow @Final private Session session;
    @Shadow @Final private MetadataSerializer metadataSerializer_;
    @Shadow @Final private static ResourceLocation locationMojangPng;
    @Shadow private TextureManager renderEngine;
    @Shadow public MouseHelper mouseHelper;
    @Shadow public GuiAchievement guiAchievement;
    @Shadow public RenderGlobal renderGlobal;
    @Shadow public EffectRenderer effectRenderer;
    @Shadow public WorldClient theWorld;
    @Shadow public GuiIngame ingameGUI;
    @Shadow public LoadingScreenRenderer loadingScreen;
    @Shadow public FontRenderer fontRenderer;
    @Shadow public FontRenderer standardGalacticFontRenderer;
    @Shadow public EntityRenderer entityRenderer;
    @Shadow public StatFileWriter statFileWriter;
    @Shadow public SoundManager sndManager;
    @Shadow public DefaultResourcePack mcDefaultResourcePack;
    @Shadow private ISaveFormat saveLoader;
    @Shadow private ResourcePackRepository mcResourcePackRepository;
    @Shadow private ReloadableResourceManager mcResourceManager;
    @Shadow private LanguageManager mcLanguageManager;
    @Shadow private String serverName;
    @Shadow private int serverPort;

    @Shadow public abstract ILogAgent getLogAgent();

    @Shadow private ByteBuffer readImage(File file) throws IOException {
        throw new AssertionError();
    }

    @Shadow public abstract void refreshResources();

    @Shadow public abstract void displayGuiScreen(GuiScreen screen);

    @Shadow public abstract void toggleFullscreen();

    @Shadow public abstract void scaledTessellator(int x, int y, int u, int v, int w, int h);

    /**
     * Start-up on the native window: NativeWindow.create replaces Display.setDisplayMode/setTitle/setIcon/create (icons are
     * skipped on macOS). As in MITE, a failed first create is retried once after 1 s; a retry requested in fullscreen falls
     * back to a windowed desktop-sized window. The initial GL state becomes Draw/Frame setters. Every non-GL statement and its
     * order is kept.
     *
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    private void startGame() throws LWJGLException {
        Minecraft self = (Minecraft) (Object) this;
        this.gameSettings = new GameSettings(self, this.mcDataDir);
        if (this.gameSettings.overrideHeight > 0 && this.gameSettings.overrideWidth > 0) {
            this.displayWidth = this.gameSettings.overrideWidth;
            this.displayHeight = this.gameSettings.overrideHeight;
        }

        if (this.fullscreen) {
            int[] desktop = NativeWindow.desktopMode();
            this.displayWidth = desktop[0];
            this.displayHeight = desktop[1];
            if (this.displayWidth <= 0) {
                this.displayWidth = 1;
            }
            if (this.displayHeight <= 0) {
                this.displayHeight = 1;
            }
        }

        String title = "Minecraft " + Minecraft.getVersionDescriptor(false);
        this.getLogAgent().logInfo("Native Vulkan renderer, GLFW " + GLFW.glfwGetVersionString());
        ByteBuffer[] icons = null;
        if (Util.getOSType() != EnumOS.MACOS) {
            // vanilla read icons/icon_16x16.png + icon_32x32.png from the assets dir; WindowIcons also falls back to the copies
            // bundled in the jar and adds the 48..256 px sizes the Windows taskbar wants
            icons = net.xiaoyu233.fml.vulkan.window.WindowIcons.load(this.fileAssets);
        }

        boolean retried = false;
        try {
            NativeWindow.create(this.displayWidth, this.displayHeight, title, !Main.is_MITE_DS, icons);
        } catch (RuntimeException e) {
            // MITE retried Display.create() once after a 1 s pause (transient GLFW/Vulkan init failure).
            e.printStackTrace();
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException ignored) {
            }
            if (this.fullscreen) {
                // Retry windowed at the desktop size, as MITE's updateDisplayMode fallback did.
                int[] desktop = NativeWindow.desktopMode();
                this.displayWidth = Math.max(1, desktop[0]);
                this.displayHeight = Math.max(1, desktop[1]);
            }
            retried = true;
            NativeWindow.create(this.displayWidth, this.displayHeight, title, !Main.is_MITE_DS, icons);
        }
        if (this.fullscreen && !retried) {
            NativeWindow.requestFullscreen(true);
        }

        OpenGlHelper.initializeTextures();
        this.guiAchievement = new GuiAchievement(self);
        this.metadataSerializer_.registerMetadataSectionType(new TextureMetadataSectionSerializer(), TextureMetadataSection.class);
        this.metadataSerializer_.registerMetadataSectionType(new FontMetadataSectionSerializer(), FontMetadataSection.class);
        this.metadataSerializer_.registerMetadataSectionType(new AnimationMetadataSectionSerializer(), AnimationMetadataSection.class);
        this.metadataSerializer_.registerMetadataSectionType(new PackMetadataSectionSerializer(), PackMetadataSection.class);
        this.metadataSerializer_.registerMetadataSectionType(new LanguageMetadataSectionSerializer(), LanguageMetadataSection.class);
        this.saveLoader = new AnvilSaveConverter(this.saves_dir_MITE);
        this.mcResourcePackRepository = new ResourcePackRepository(
            this.fileResourcepacks, this.mcDefaultResourcePack, this.metadataSerializer_, this.gameSettings
        );
        this.mcResourceManager = new SimpleReloadableResourceManager(this.metadataSerializer_);
        this.mcLanguageManager = new LanguageManager(this.metadataSerializer_, this.gameSettings.language);
        this.mcResourceManager.registerReloadListener(this.mcLanguageManager);
        this.refreshResources();
        this.renderEngine = new TextureManager(this.mcResourceManager);
        this.mcResourceManager.registerReloadListener(this.renderEngine);
        this.sndManager = new SoundManager(this.mcResourceManager, this.gameSettings, this.fileAssets);
        this.mcResourceManager.registerReloadListener(this.sndManager);
        this.loadScreen();
        this.fontRenderer = new FontRenderer(this.gameSettings, new ResourceLocation("textures/font/ascii.png"), this.renderEngine, false);
        if (this.gameSettings.language != null) {
            this.fontRenderer.setUnicodeFlag(this.mcLanguageManager.isCurrentLocaleUnicode());
            this.fontRenderer.setBidiFlag(this.mcLanguageManager.isCurrentLanguageBidirectional());
        }

        this.standardGalacticFontRenderer = new FontRenderer(this.gameSettings, new ResourceLocation("textures/font/ascii_sga.png"), this.renderEngine, false);
        this.mcResourceManager.registerReloadListener(this.fontRenderer);
        this.mcResourceManager.registerReloadListener(this.standardGalacticFontRenderer);
        this.mcResourceManager.registerReloadListener(new GrassColorReloadListener());
        this.mcResourceManager.registerReloadListener(new FoliageColorReloadListener());
        RenderManager.instance.itemRenderer = new ItemRenderer(self);
        this.entityRenderer = new EntityRenderer(self);
        this.statFileWriter = new StatFileWriter(this.session, new File(this.mcDataDir, "MITE"));
        AchievementList.openInventory.setStatStringFormatter(text -> {
            try {
                return String.format(text, GameSettings.getKeyDisplayString(self.gameSettings.keyBindInventory.keyCode));
            } catch (Exception e) {
                return "Error: " + e.getLocalizedMessage();
            }
        });
        this.mouseHelper = new MouseHelper();
        Frame.checkErrors("Pre startup");
        Draw.texturing(true);
        Draw.smoothShading(true);
        Draw.depthTest(true);
        Draw.depthFunc(DepthFunc.LEQUAL);
        Draw.alphaTest(true);
        Draw.alphaFunc(0.1F);
        Draw.cullFace(Face.BACK);
        Draw.projection().loadIdentity();
        Draw.pose().loadIdentity();
        Frame.checkErrors("Startup");
        this.renderGlobal = new RenderGlobal(self);
        this.renderEngine.loadTextureMap(TextureMap.locationBlocksTexture, new TextureMap(0, "textures/blocks"));
        this.renderEngine.loadTextureMap(TextureMap.locationItemsTexture, new TextureMap(1, "textures/items"));
        Frame.viewport(0, 0, this.displayWidth, this.displayHeight);
        this.effectRenderer = new EffectRenderer(this.theWorld, this.renderEngine);
        Frame.checkErrors("Post startup");
        this.ingameGUI = new GuiIngame(self);
        if (this.serverName != null) {
            this.displayGuiScreen(new GuiConnecting(new GuiMainMenu(), self, this.serverName, this.serverPort));
        } else {
            this.displayGuiScreen(new GuiMainMenu());
        }

        this.loadingScreen = new LoadingScreenRenderer(self);
        if (this.gameSettings.isFullscreen() && !this.fullscreen) {
            this.toggleFullscreen();
        } else {
            NativeWindow.setVSync(this.gameSettings.isVsyncEnabled());
        }

        ReferenceFileWriter.write();
    }

    /**
     * Switches to the desktop mode of the monitor through NativeWindow. The desktop mode is always an available mode of the
     * native window, so MITE's macOS retina fallback table (macDisplayModes) is not needed.
     *
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    private void updateDisplayMode() throws LWJGLException {
        DisplayMode desktop = NativeWindow.desktopDisplayMode();
        NativeWindow.setDisplayMode(desktop);
        this.displayWidth = desktop.getWidth();
        this.displayHeight = desktop.getHeight();
    }

    /**
     * Loading screen: clear, ortho 0..scaled size, MITE logo quads through the Tessellator, then NativeWindow.update().
     *
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    private void loadScreen() throws LWJGLException {
        ScaledResolution scaled = new ScaledResolution(this.gameSettings, this.displayWidth, this.displayHeight);
        Frame.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        Frame.clearColorDepth();
        Draw.projection().loadIdentity();
        Draw.projection().ortho(0.0, scaled.getScaledWidth_double(), scaled.getScaledHeight_double(), 0.0, 1000.0, 3000.0);
        Draw.pose().loadIdentity();
        Draw.pose().translate(0.0F, 0.0F, -2000.0F);
        Frame.viewport(0, 0, this.displayWidth, this.displayHeight);
        Draw.lighting(false);
        Draw.texturing(true);
        Draw.fog(false);
        this.renderEngine.bindTexture(locationMojangPng);
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        tess.setColorOpaque_I(16777215);
        tess.addVertexWithUV(0.0, this.displayHeight, 0.0, 0.0, 0.0);
        tess.addVertexWithUV(this.displayWidth, this.displayHeight, 0.0, 0.0, 0.0);
        tess.addVertexWithUV(this.displayWidth, 0.0, 0.0, 0.0, 0.0);
        tess.addVertexWithUV(0.0, 0.0, 0.0, 0.0, 0.0);
        tess.draw();
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        tess.setColorOpaque_I(16777215);
        short logoWidth = 256;
        short logoHeight = 256;
        this.scaledTessellator((scaled.getScaledWidth() - logoWidth) / 2, (scaled.getScaledHeight() - logoHeight) / 2, 0, 0, logoWidth, logoHeight);
        Draw.lighting(false);
        Draw.fog(false);
        Draw.alphaTest(true);
        Draw.alphaFunc(0.1F);
        NativeWindow.update();
    }
}
