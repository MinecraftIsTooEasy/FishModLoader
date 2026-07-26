package net.xiaoyu233.fml.reload.transform.forge_compat;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.Vec3;
import net.minecraft.world.EnumGameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderEnd;
import net.minecraft.world.WorldProviderHell;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.client.IRenderHandler;
import net.minecraftforge.common.DimensionManager;
import net.xiaoyu233.fml.util.ReflectHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldProvider.class)
public class WorldProviderMixin {
    @Shadow
    public int dimensionId;
    @Shadow
    public World worldObj;
    @Shadow
    private net.minecraft.world.WorldType terrainType;
    @Shadow
    protected boolean hasNoSky;

    @Unique
    private IRenderHandler skyRenderer = null;
    @Unique
    private IRenderHandler cloudRenderer = null;

    @Unique
    public void setDimension(int dim) {
        this.dimensionId = dim;
    }

    @Unique
    public String getSaveFolder() {
        return this.dimensionId == 0 ? null : "DIM" + this.dimensionId;
    }

    @Unique
    public String getWelcomeMessage() {
        WorldProvider self = ReflectHelper.dyCast(this);
        if (self instanceof WorldProviderEnd) {
            return "Entering the End";
        } else if (self instanceof WorldProviderHell) {
            return "Entering the Nether";
        }
        return null;
    }

    @Unique
    public String getDepartMessage() {
        WorldProvider self = ReflectHelper.dyCast(this);
        if (self instanceof WorldProviderEnd) {
            return "Leaving the End";
        } else if (self instanceof WorldProviderHell) {
            return "Leaving the Nether";
        }
        return null;
    }

    @Unique
    public double getMovementFactor() {
        if (ReflectHelper.dyCast(this) instanceof WorldProviderHell) {
            return 8.0;
        }
        return 1.0;
    }

    @Unique
    @SideOnly(Side.CLIENT)
    public IRenderHandler getSkyRenderer() {
        return this.skyRenderer;
    }

    @Unique
    @SideOnly(Side.CLIENT)
    public void setSkyRenderer(IRenderHandler skyRenderer) {
        this.skyRenderer = skyRenderer;
    }

    @Unique
    @SideOnly(Side.CLIENT)
    public IRenderHandler getCloudRenderer() {
        return this.cloudRenderer;
    }

    @Unique
    @SideOnly(Side.CLIENT)
    public void setCloudRenderer(IRenderHandler renderer) {
        this.cloudRenderer = renderer;
    }

    @Unique
    public ChunkCoordinates getRandomizedSpawnPoint() {
        ChunkCoordinates chunkcoordinates = new ChunkCoordinates(this.worldObj.getSpawnPoint());
        boolean isAdventure = this.worldObj.getWorldInfo().getGameType() == EnumGameType.ADVENTURE;
        int spawnFuzz = 20; // MITE doesn't have WorldType.getSpawnFuzz(), use default
        int spawnFuzzHalf = spawnFuzz / 2;

        if (!this.hasNoSky && !isAdventure) {
            chunkcoordinates.posX += this.worldObj.rand.nextInt(spawnFuzz) - spawnFuzzHalf;
            chunkcoordinates.posZ += this.worldObj.rand.nextInt(spawnFuzz) - spawnFuzzHalf;
            chunkcoordinates.posY = this.worldObj.getTopSolidOrLiquidBlock(chunkcoordinates.posX, chunkcoordinates.posZ);
        }

        return chunkcoordinates;
    }

    @Unique
    public boolean shouldMapSpin(String entity, double x, double y, double z) {
        return this.dimensionId < 0;
    }

    @Unique
    public int getRespawnDimension(EntityPlayerMP player) {
        return 0;
    }

    /* ===== Methods moved from World ===== */

    @Unique
    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        return this.worldObj.getBiomeGenForCoords(x, z);
    }

    @Unique
    public boolean isDaytime() {
        return this.worldObj.skylightSubtracted < 4;
    }

    @Unique
    @SideOnly(Side.CLIENT)
    public Vec3 getSkyColor(Entity cameraEntity, float partialTicks) {
        return this.worldObj.getSkyColor(cameraEntity, partialTicks);
    }

    @Unique
    @SideOnly(Side.CLIENT)
    public Vec3 drawClouds(float partialTicks) {
        return this.worldObj.getCloudColour(partialTicks);
    }

    @Unique
    @SideOnly(Side.CLIENT)
    public float getStarBrightness(float par1) {
        return this.worldObj.getStarBrightness(par1);
    }

    @Unique
    public long getSeed() {
        return this.worldObj.worldInfo.getSeed();
    }

    @Unique
    public ChunkCoordinates getSpawnPoint() {
        WorldInfo info = this.worldObj.worldInfo;
        return new ChunkCoordinates(info.getSpawnX(), info.getSpawnY(), info.getSpawnZ());
    }

    @Unique
    public void setSpawnPoint(int x, int y, int z) {
        this.worldObj.worldInfo.setSpawnPosition(x, y, z);
    }

    @Unique
    public boolean canMineBlock(EntityPlayer player, int x, int y, int z) {
        return true;
    }

    @Unique
    public boolean isBlockHighHumidity(int x, int y, int z) {
        return this.worldObj.getBiomeGenForCoords(x, z).isHighHumidity();
    }

    @Unique
    public int getHeight() {
        return 256;
    }

    @Unique
    public int getActualHeight() {
        return this.hasNoSky ? 128 : 256;
    }

    @Unique
    public boolean canDoLightning(Chunk chunk) {
        return true;
    }

    @Unique
    public boolean canDoRainSnowIce(Chunk chunk) {
        return true;
    }

    /* ===== Injection hooks ===== */

    @Inject(method = "getProviderForDimension", at = @At("HEAD"), cancellable = true)
    private static void fmlForgeGetProviderForDimension(int par0, CallbackInfoReturnable<WorldProvider> cir) {
        cir.setReturnValue(DimensionManager.createProviderFor(par0));
        cir.cancel();
    }

    @Inject(method = "getCloudHeight", at = @At("HEAD"), cancellable = true)
    private void fmlForgeGetCloudHeight(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(128.0F);
        cir.cancel();
    }
}
