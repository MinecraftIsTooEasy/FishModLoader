package net.xiaoyu233.fml.reload.transform.forge_compat;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.world.CaveNetworkStub;
import net.minecraft.world.World;
import net.minecraft.world.WorldGenPlants;
import net.minecraft.world.biome.BiomeDecorator;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.feature.WorldGenDeadBush;
import net.minecraft.world.gen.feature.WorldGenFlowers;
import net.minecraft.world.gen.feature.WorldGenLiquids;
import net.minecraft.world.gen.feature.WorldGenBigTree;
import net.minecraft.world.gen.feature.WorldGenMinable;
import net.minecraft.world.gen.feature.WorldGenPumpkin;
import net.minecraft.world.gen.feature.WorldGenerator;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.DecorateBiomeEvent;
import net.minecraftforge.event.terraingen.OreGenEvent;
import net.minecraftforge.event.terraingen.TerrainGen;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IBiomeBigTreeAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

import static net.minecraftforge.event.terraingen.DecorateBiomeEvent.Decorate.EventType.*;
import static net.minecraftforge.event.terraingen.OreGenEvent.GenerateMinable.EventType.*;

@Mixin(BiomeDecorator.class)
public abstract class BiomeDecoratorMixin {
    @Shadow protected World currentWorld;
    @Shadow protected Random randomGenerator;
    @Shadow protected int chunk_X;
    @Shadow protected int chunk_Z;
    @Shadow protected int sandPerChunk2;
    @Shadow protected WorldGenerator sandGen;
    @Shadow protected int sandPerChunk;
    @Shadow protected WorldGenerator clayGen;
    @Shadow protected int treesPerChunk;
    @Shadow protected BiomeGenBase biome;
    @Shadow protected int bigMushroomsPerChunk;
    @Shadow public WorldGenerator bigMushroomGen;
    @Shadow protected int flowersPerChunk;
    @Shadow protected WorldGenerator plantYellowGen;
    @Shadow protected WorldGenFlowers plantRedGen;
    @Shadow protected int grassPerChunk;
    @Shadow protected int deadBushPerChunk;
    @Shadow protected int waterlilyPerChunk;
    @Shadow protected WorldGenerator waterlilyGen;
    @Shadow protected int surface_mushrooms_per_chunk;
    @Shadow protected WorldGenerator mushroomRedGen;
    @Shadow protected WorldGenerator mushroomBrownGen;
    @Shadow protected int reedsPerChunk;
    @Shadow protected WorldGenerator reedGen;
    @Shadow protected int cactiPerChunk;
    @Shadow protected WorldGenerator cactusGen;
    @Shadow protected int bush_patches_per_chunk_tenths;
    @Shadow protected WorldGenPlants bush_gen;
    @Shadow public boolean generateLakes;
    @Shadow protected WorldGenMinable dirtGen;
    @Shadow protected WorldGenMinable gravelGen;
    @Shadow protected WorldGenMinable coalGen;
    @Shadow protected WorldGenMinable ironGen;
    @Shadow protected WorldGenMinable goldGen;
    @Shadow protected WorldGenMinable redstoneGen;
    @Shadow protected WorldGenMinable diamondGen;
    @Shadow protected WorldGenMinable lapisGen;
    @Shadow protected WorldGenMinable adamantiteGen;
    @Shadow protected WorldGenMinable mithrilGen;
    @Shadow protected WorldGenMinable silverfishGen;
    @Shadow protected WorldGenMinable copperGen;
    @Shadow protected abstract void generateOres();
    
    @Shadow
    protected WorldGenMinable silverGen;
    
    @Overwrite
    protected void decorate() {
        MinecraftForge.EVENT_BUS.post(new DecorateBiomeEvent.Pre(currentWorld, randomGenerator, chunk_X, chunk_Z));
        this.currentWorld.decorating = true;
        this.generateOres();

        int i;
        int j;
        int var3;
        boolean doGen = TerrainGen.decorate(currentWorld, randomGenerator, chunk_X, chunk_Z, SAND);
        for (i = 0; doGen && i < this.sandPerChunk2; ++i) {
            j = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var3 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
            this.sandGen.generate(this.currentWorld, this.randomGenerator, j, this.currentWorld.getTopSolidOrLiquidBlock(j, var3), var3);
        }

        doGen = TerrainGen.decorate(currentWorld, randomGenerator, chunk_X, chunk_Z, SAND_PASS2);
        for (i = 0; doGen && i < this.sandPerChunk; ++i) {
            j = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var3 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
            this.clayGen.generate(this.currentWorld, this.randomGenerator, j, this.currentWorld.getTopSolidOrLiquidBlock(j, var3), var3);
        }

        for(i = 0; i < this.sandPerChunk; ++i) {
            j = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var3 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
            this.sandGen.generate(this.currentWorld, this.randomGenerator, j, this.currentWorld.getTopSolidOrLiquidBlock(j, var3), var3);
        }

        i = this.treesPerChunk;
        if (this.randomGenerator.nextInt(10) == 0) {
            ++i;
        }

        int var4;
        doGen = TerrainGen.decorate(currentWorld, randomGenerator, chunk_X, chunk_Z, TREE);
        for (j = 0; doGen && j < i; ++j) {
            var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var4 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
            WorldGenerator var5 = this.biome.getRandomWorldGenForTrees(this.randomGenerator);
            var5.setScale(1.0, 1.0, 1.0);
            var5.generate(this.currentWorld, this.randomGenerator, var3, this.currentWorld.getHeightValue(var3, var4), var4);
        }

        int var7;
        if (this.biome == BiomeGenBase.plains && this.randomGenerator.nextInt(400) == 0) {
            // BiomeDecorator has no such field; use the single generator owned by its biome.
            WorldGenBigTree varBigTree = ((IBiomeBigTreeAccessor) this.biome).fmlGetWorldGeneratorBigTree();
            var7 = varBigTree.heightLimit;
            var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var4 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
            varBigTree.setHeightLimit(10 + this.randomGenerator.nextInt(5));
            WorldGenerator var5 = varBigTree;
            var5.setScale(1.0, 1.0, 1.0);
            var5.generate(this.currentWorld, this.randomGenerator, var3, this.currentWorld.getHeightValue(var3, var4), var4);
            varBigTree.setHeightLimit(var7);
        }

        doGen = TerrainGen.decorate(currentWorld, randomGenerator, chunk_X, chunk_Z, BIG_SHROOM);
        for (j = 0; doGen && j < this.bigMushroomsPerChunk; ++j) {
            var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var4 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
            this.bigMushroomGen.generate(this.currentWorld, this.randomGenerator, var3, this.currentWorld.getHeightValue(var3, var4), var4);
        }

        int bush_patches_per_chunk;
        doGen = TerrainGen.decorate(currentWorld, randomGenerator, chunk_X, chunk_Z, FLOWERS);
        for (j = 0; doGen && j < this.flowersPerChunk; ++j) {
            var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var4 = this.randomGenerator.nextInt(128);
            var7 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
            this.plantYellowGen.generate(this.currentWorld, this.randomGenerator, var3, var4, var7);
            if (this.randomGenerator.nextInt(this.biome.isSwampBiome() ? 3 : 2) == 0) {
                var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
                var4 = this.randomGenerator.nextInt(128);
                var7 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
                bush_patches_per_chunk = Block.plantRed.getRandomSubtypeForBiome(this.randomGenerator, this.biome);
                if (bush_patches_per_chunk >= 0) {
                    this.plantRedGen.setMetadata(bush_patches_per_chunk);
                    this.plantRedGen.generate(this.currentWorld, this.randomGenerator, var3, var4, var7);
                }
            }
        }

        doGen = TerrainGen.decorate(currentWorld, randomGenerator, chunk_X, chunk_Z, GRASS);
        for (j = 0; doGen && j < this.grassPerChunk; ++j) {
            var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var4 = this.randomGenerator.nextInt(128);
            var7 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
            WorldGenerator var6 = this.biome.getRandomWorldGenForGrass(this.randomGenerator);
            var6.generate(this.currentWorld, this.randomGenerator, var3, var4, var7);
        }

        doGen = TerrainGen.decorate(currentWorld, randomGenerator, chunk_X, chunk_Z, DEAD_BUSH);
        for (j = 0; doGen && j < this.deadBushPerChunk; ++j) {
            var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var4 = this.randomGenerator.nextInt(128);
            var7 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
            (new WorldGenDeadBush(Block.deadBush.blockID)).generate(this.currentWorld, this.randomGenerator, var3, var4, var7);
        }

        doGen = TerrainGen.decorate(currentWorld, randomGenerator, chunk_X, chunk_Z, LILYPAD);
        for (j = 0; doGen && j < this.waterlilyPerChunk; ++j)
        {
            var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var4 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;

            for(var7 = this.randomGenerator.nextInt(128); var7 > 0 && this.currentWorld.getBlockId(var3, var7 - 1, var4) == 0; --var7) {
            }

            this.waterlilyGen.generate(this.currentWorld, this.randomGenerator, var3, var7, var4);
        }

        doGen = TerrainGen.decorate(currentWorld, randomGenerator, chunk_X, chunk_Z, SHROOM);
        for (j = 0; doGen && j < this.surface_mushrooms_per_chunk; ++j)
        {
            if (this.randomGenerator.nextInt(6) == 0) {
                var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
                var4 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
                var7 = this.randomGenerator.nextInt(128);
                this.mushroomRedGen.generate(this.currentWorld, this.randomGenerator, var3, var7, var4);
            }
        }

        if (doGen && this.randomGenerator.nextInt(6) == 0) {
            j = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var3 = this.randomGenerator.nextInt(128);
            var4 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
            this.mushroomRedGen.generate(this.currentWorld, this.randomGenerator, j, var3, var4);
        }

        for(bush_patches_per_chunk = 0; bush_patches_per_chunk < 4; ++bush_patches_per_chunk) {
            if (this.currentWorld.isUnderworld()) {
                if (this.randomGenerator.nextInt(4) == 0) {
                    j = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
                    var3 = this.randomGenerator.nextInt(128);
                    var4 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
                    this.mushroomBrownGen.generate(this.currentWorld, this.randomGenerator, j, var3, var4);
                }
                break;
            }

            if (doGen && this.randomGenerator.nextInt(4) == 0) {
                j = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
                var3 = this.randomGenerator.nextInt(32) + 48;
                var4 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
                this.mushroomBrownGen.generate(this.currentWorld, this.randomGenerator, j, var3, var4);
            }

            if (doGen && this.randomGenerator.nextInt(4) == 0) {
                j = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
                var3 = this.randomGenerator.nextInt(128);
                var4 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
                this.mushroomBrownGen.generate(this.currentWorld, this.randomGenerator, j, var3, var4);
            }
        }

        if (this.biome.temperature >= 0.3F) {
            doGen = TerrainGen.decorate(currentWorld, randomGenerator, chunk_X, chunk_Z, REED);
            for (j = 0; doGen && j < this.reedsPerChunk; ++j) {
                var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
                var4 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
                var7 = this.randomGenerator.nextInt(128);
                this.reedGen.generate(this.currentWorld, this.randomGenerator, var3, var7, var4);
            }

            for(j = 0; doGen && j < 10; ++j) {
                var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
                var4 = this.randomGenerator.nextInt(128);
                var7 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
                this.reedGen.generate(this.currentWorld, this.randomGenerator, var3, var4, var7);
            }
        }

        doGen = TerrainGen.decorate(currentWorld, randomGenerator, chunk_X, chunk_Z, PUMPKIN);
        if (doGen && this.randomGenerator.nextInt(32) == 0) {
            j = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var3 = this.randomGenerator.nextInt(128);
            var4 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
            (new WorldGenPumpkin()).generate(this.currentWorld, this.randomGenerator, j, var3, var4);
        }

        doGen = TerrainGen.decorate(currentWorld, randomGenerator, chunk_X, chunk_Z, CACTUS);
        for (j = 0; doGen && j < this.cactiPerChunk; ++j) {
            var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var4 = this.randomGenerator.nextInt(128);
            var7 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
            this.cactusGen.generate(this.currentWorld, this.randomGenerator, var3, var4, var7);
        }

        bush_patches_per_chunk = this.bush_patches_per_chunk_tenths / 10 + (this.randomGenerator.nextInt(10) < this.bush_patches_per_chunk_tenths % 10 ? 1 : 0);

        for(j = 0; j < bush_patches_per_chunk; ++j) {
            var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
            var4 = this.randomGenerator.nextInt(128);
            var7 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
            this.bush_gen.setMetadata(BlockBush.getMetadataForBushWithBerries(0));
            this.bush_gen.generate(this.currentWorld, this.randomGenerator, var3, var4, var7);
        }

        doGen = TerrainGen.decorate(currentWorld, randomGenerator, chunk_X, chunk_Z, LAKE);
        if (doGen && this.generateLakes) {
            for(int chunk_dx = -1; chunk_dx <= 1; ++chunk_dx) {
                for(int chunk_dz = -1; chunk_dz <= 1; ++chunk_dz) {
                    Chunk chunk = this.currentWorld.getChunkFromBlockCoordsIfItExists(this.chunk_X + chunk_dx * 16, this.chunk_Z + chunk_dz * 16);
                    if (chunk != null && chunk.getHadNaturallyOccurringMycelium()) {
                        this.currentWorld.decorating = false;
                        return;
                    }
                }
            }

            for(j = 0; j < 70; ++j) {
                var3 = this.chunk_X + this.randomGenerator.nextInt(16) + 8;
                var4 = this.randomGenerator.nextInt(this.randomGenerator.nextInt(120) + 8);
                var7 = this.chunk_Z + this.randomGenerator.nextInt(16) + 8;
                CaveNetworkStub stub = this.currentWorld.getAsWorldServer().getCaveNetworkStubAt(var3 >> 4, var7 >> 4);
                boolean prevent_water = false;
                boolean prevent_lava = false;
                if (stub != null) {
                    if (stub.hasMycelium() || stub.preventsAllLiquids() || this.randomGenerator.nextFloat() < 0.67F) {
                        continue;
                    }

                    prevent_water = !stub.allowsWater();
                    prevent_lava = !stub.allowsLava();
                }

                int liquid_block_id;
                if (this.randomGenerator.nextInt(32) + 16 < var4) {
                    if (prevent_water) {
                        continue;
                    }

                    liquid_block_id = Block.waterMoving.blockID;
                } else if (this.randomGenerator.nextFloat() < 0.95F) {
                    if (prevent_lava) {
                        continue;
                    }

                    liquid_block_id = Block.lavaMoving.blockID;
                } else {
                    if (prevent_water) {
                        continue;
                    }

                    liquid_block_id = Block.waterMoving.blockID;
                }

                WorldGenLiquids.generate(this.currentWorld, this.randomGenerator, liquid_block_id, var3, var4 + this.currentWorld.underworld_y_offset, var7);
            }
        }
        MinecraftForge.EVENT_BUS.post(new DecorateBiomeEvent.Post(currentWorld, randomGenerator, chunk_X, chunk_Z));
        this.currentWorld.decorating = false;
    }

    @Inject(method = "generateOres", at = @At("HEAD"))
    private void fmlForgeOnGenerateOresPre(CallbackInfo ci) {
        MinecraftForge.ORE_GEN_BUS.post(new OreGenEvent.Pre(currentWorld, randomGenerator, chunk_X, chunk_Z));
    }

    @Inject(method = "generateOres", at = @At("RETURN"))
    private void fmlForgeOnGenerateOresPost(CallbackInfo ci) {
        MinecraftForge.ORE_GEN_BUS.post(new OreGenEvent.Post(currentWorld, randomGenerator, chunk_X, chunk_Z));
    }

// ============ Overworld ============
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;)V", ordinal = 0))
    private boolean fmlDirt(int count, WorldGenMinable gen) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.dirtGen, chunk_X, chunk_Z, DIRT);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;)V", ordinal = 1))
    private boolean fmlGravelOverworld(int count, WorldGenMinable gen) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.gravelGen, chunk_X, chunk_Z, GRAVEL);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;)V", ordinal = 2))
    private boolean fmlCoal(int count, WorldGenMinable gen) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.coalGen, chunk_X, chunk_Z, COAL);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;Z)V", ordinal = 0))
    private boolean fmlCopperOverworld(int count, WorldGenMinable gen, boolean flag) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.copperGen, chunk_X, chunk_Z, COPPER);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;Z)V", ordinal = 1))
    private boolean fmlSilverOverworld(int count, WorldGenMinable gen, boolean flag) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.silverGen, chunk_X, chunk_Z, SILVER);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;Z)V", ordinal = 2))
    private boolean fmlGoldOverworld(int count, WorldGenMinable gen, boolean flag) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.goldGen, chunk_X, chunk_Z, GOLD);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;Z)V", ordinal = 3))
    private boolean fmlIronOverworld(int count, WorldGenMinable gen, boolean flag) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.ironGen, chunk_X, chunk_Z, IRON);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;Z)V", ordinal = 4))
    private boolean fmlMithrilOverworld(int count, WorldGenMinable gen, boolean flag) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.mithrilGen, chunk_X, chunk_Z, MITHRIL);
    }

    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;)V", ordinal = 3))
    private boolean fmlRedstoneOverworld(int count, WorldGenMinable gen) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.redstoneGen, chunk_X, chunk_Z, REDSTONE);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;)V", ordinal = 4))
    private boolean fmlDiamondOverworld(int count, WorldGenMinable gen) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.diamondGen, chunk_X, chunk_Z, DIAMOND);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;)V", ordinal = 5))
    private boolean fmlLapisOverworld(int count, WorldGenMinable gen) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.lapisGen, chunk_X, chunk_Z, LAPIS);
    }

// ============ Underworld ============
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;)V", ordinal = 6))
    private boolean fmlGravelUnderworld(int count, WorldGenMinable gen) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.gravelGen, chunk_X, chunk_Z, GRAVEL);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;Z)V", ordinal = 5))
    private boolean fmlCopperUnderworld(int count, WorldGenMinable gen, boolean flag) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.copperGen, chunk_X, chunk_Z, COPPER);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;Z)V", ordinal = 6))
    private boolean fmlSilverUnderworld(int count, WorldGenMinable gen, boolean flag) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.silverGen, chunk_X, chunk_Z, SILVER);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;Z)V", ordinal = 7))
    private boolean fmlGoldUnderworld(int count, WorldGenMinable gen, boolean flag) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.goldGen, chunk_X, chunk_Z, GOLD);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;Z)V", ordinal = 8))
    private boolean fmlIronUnderworld(int count, WorldGenMinable gen, boolean flag) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.ironGen, chunk_X, chunk_Z, IRON);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;Z)V", ordinal = 9))
    private boolean fmlMithrilUnderworld(int count, WorldGenMinable gen, boolean flag) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.mithrilGen, chunk_X, chunk_Z, MITHRIL);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;Z)V", ordinal = 10))
    private boolean fmlAdamantiumUnderworld(int count, WorldGenMinable gen, boolean flag) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.adamantiteGen, chunk_X, chunk_Z, ADAMANTIUM);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;)V", ordinal = 7))
    private boolean fmlRedstoneUnderworld(int count, WorldGenMinable gen) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.redstoneGen, chunk_X, chunk_Z, REDSTONE);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;)V", ordinal = 8))
    private boolean fmlDiamondUnderworld(int count, WorldGenMinable gen) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.diamondGen, chunk_X, chunk_Z, DIAMOND);
    }
    
    @WrapWithCondition(method = "generateOres",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/biome/BiomeDecorator;genMinable(ILnet/minecraft/world/gen/feature/WorldGenMinable;)V", ordinal = 9))
    private boolean fmlLapisUnderworld(int count, WorldGenMinable gen) {
        return TerrainGen.generateOre(currentWorld, randomGenerator, this.lapisGen, chunk_X, chunk_Z, LAPIS);
    }
}
