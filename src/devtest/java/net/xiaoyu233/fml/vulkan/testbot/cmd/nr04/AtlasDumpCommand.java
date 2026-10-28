package net.xiaoyu233.fml.vulkan.testbot.cmd.nr04;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import javax.imageio.ImageIO;
import net.minecraft.Minecraft;
import net.minecraft.TextureMap;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.render.texture.AtlasInfo;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.texture.TextureTestSupport;

/**
 * {@code atlasDump [blocks|items]}: writes level 0 of an atlas to {@code <captureDir>/atlas_<name>_<w>x<h>.png} and reports its SHA-256
 * (of the BGRA bytes) so a run with {@code fml.vulkan.native=false} (phase-1 loader) can be compared with a native run by hash; also
 * reports the opacity map and the atlas reload time of the log line. The PNG is the same one {@code AutoScript dumptex} would write.
 */
final class AtlasDumpCommand implements DevCommand {
    @Override
    public String name() {
        return "atlasDump";
    }

    @Override
    public String description() {
        return "dump the blocks/items atlas to vk-captures with a SHA-256 of its pixels (compare native vs native=false)";
    }

    @Override
    public void run(Report.Section section, String[] args) throws Exception {
        boolean items = args.length > 0 && args[0].equalsIgnoreCase("items");
        String atlasName = items ? "items" : "blocks";
        Object[] out = RenderThreadTasks.call(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            int id = mc.getTextureManager().getTexture(items ? TextureMap.locationItemsTexture : TextureMap.locationBlocksTexture)
                .getGlTextureId();
            int w = Textures.width(id);
            int h = Textures.height(id);
            int[] pixels = TextureTestSupport.readCurrent(id);
            AtlasInfo atlas = Textures.atlas(id);
            String animated = pixels == null ? "n/a" : animatedReport(mc.getTextureManager().getTexture(items ? TextureMap.locationItemsTexture
                : TextureMap.locationBlocksTexture), pixels, w, h);
            return new Object[] {id, w, h, pixels, atlas == null ? -1 : atlas.opacityVersion(), atlas == null ? "none" : atlas.width() + "x" + atlas.height(),
                animated};
        }, 120_000);
        int id = (Integer) out[0];
        int w = (Integer) out[1];
        int h = (Integer) out[2];
        int[] pixels = (int[]) out[3];
        if (pixels == null || w <= 0) {
            section.fail("atlasDump " + atlasName, "atlas texture " + id + " has no image");
            return;
        }
        ByteBuffer bytes = ByteBuffer.allocate(pixels.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asIntBuffer().put(pixels);
        String sha = hex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, pixels, 0, w);
        Path dir = VulkanConfig.captureDir();
        Files.createDirectories(dir);
        File file = dir.resolve("atlas_" + atlasName + "_" + w + "x" + h + ".png").toFile();
        ImageIO.write(image, "png", file);
        section.info("atlasDump " + atlasName, "id " + id + " " + w + "x" + h + " sha256 " + sha + " -> " + file + "; opacity map "
            + out[5] + " version " + out[4]);
        section.info("atlasDump " + atlasName + " animation", String.valueOf(out[6]));
        section.ok("atlasDump " + atlasName);
    }

    /**
     * Separates the animated sprites from the static atlas: hashes the atlas with every animated sprite rectangle zeroed (this "static
     * SHA" must be equal in every mode and run) and checks that each animated rectangle holds one of the sprite's frames (so a
     * difference between two runs can only be a different animation tick, never a wrong upload).
     */
    @SuppressWarnings("unchecked")
    private static String animatedReport(Object textureMap, int[] pixels, int w, int h) {
        try {
            java.lang.reflect.Field f = TextureMap.class.getDeclaredField("listAnimatedSprites");
            f.setAccessible(true);
            java.util.List<net.minecraft.TextureAtlasSprite> list = (java.util.List<net.minecraft.TextureAtlasSprite>) f.get(textureMap);
            int[] masked = pixels.clone();
            int matched = 0;
            StringBuilder bad = new StringBuilder();
            for (net.minecraft.TextureAtlasSprite sprite : list) {
                int sw = sprite.getIconWidth();
                int sh = sprite.getIconHeight();
                int ox = sprite.getOriginX();
                int oy = sprite.getOriginY();
                int[] cell = new int[sw * sh];
                for (int y = 0; y < sh; y++) {
                    for (int x = 0; x < sw; x++) {
                        cell[y * sw + x] = pixels[(oy + y) * w + ox + x];
                        masked[(oy + y) * w + ox + x] = 0;
                    }
                }
                boolean found = false;
                for (int frame = 0; frame < sprite.getFrameCount() && !found; frame++) {
                    int[] data = sprite.getFrameTextureData(frame);
                    found = data != null && java.util.Arrays.equals(data, cell);
                }
                if (found) {
                    matched++;
                } else {
                    bad.append(' ').append(sprite.getIconName()).append('@').append(ox).append(',').append(oy);
                }
            }
            ByteBuffer bytes = ByteBuffer.allocate(masked.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            bytes.asIntBuffer().put(masked);
            String staticSha = hex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
            return list.size() + " animated sprites, " + matched + " hold one of their own frames, static-sha256 (animated cells zeroed) " + staticSha
                + (bad.length() == 0 ? "" : "; NOT a frame:" + bad);
        } catch (Throwable t) {
            return "animated report failed: " + t;
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
