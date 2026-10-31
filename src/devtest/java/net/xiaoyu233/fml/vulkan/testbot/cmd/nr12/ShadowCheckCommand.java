package net.xiaoyu233.fml.vulkan.testbot.cmd.nr12;

import net.minecraft.Entity;
import net.minecraft.EntityDragon;
import net.minecraft.EntityDragonPart;
import net.minecraft.EntityLiving;
import net.minecraft.EntityPlayer;
import net.minecraft.ICamera;
import net.minecraft.MathHelper;
import net.minecraft.Minecraft;
import net.minecraft.RaycastCollision;
import net.minecraft.Vec3;
import net.xiaoyu233.fml.vulkan.render.world.ShadowCheck;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.Stage;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code shadowCheck [frames=600]}: installs a {@link ShadowCheck.Reference} holding MITE's ORIGINAL per-entity predicate chain of
 * {@code RenderGlobal.renderEntities} (copied verbatim from RenderGlobal.java:358-390) and a recomputation of {@code getMouseOver}'s hash, runs a
 * camera route (the player turns on the spot and moves around the museum) for the given number of frames and reports
 * {@link ShadowCheck#mismatches()} (gate 0). Requires {@code -Dfml.vulkan.shadowCheck=true}; the per frame hash is logged by
 * {@code ShadowCheck.endFrame()} (production side) and the final mismatch counter is reported here.
 */
final class ShadowCheckCommand implements DevCommand {
    @Override
    public String name() {
        return "shadowCheck";
    }

    @Override
    public String description() {
        return "ShadowCheck with MITE's original renderEntities predicate chain + getMouseOver hash over a camera route; gate: 0 mismatches";
    }

    @Override
    public void run(Report.Section s, String[] args) throws Exception {
        int frames = args.length > 0 ? Integer.parseInt(args[0]) : 600;
        TestBot bot = TestBot.get();
        if (!ShadowCheck.enabled()) {
            s.warn("shadowCheck", "-Dfml.vulkan.shadowCheck=true is not set: the production side records nothing, the counter stays 0 (vacuous)");
        }
        if (bot == null || bot.minecraft().theWorld == null || bot.minecraft().thePlayer == null) {
            s.fail("shadowCheck", "needs a running world (testbot) to drive the camera route");
            return;
        }
        long before = ShadowCheck.mismatches();
        ShadowCheck.Reference prev = ShadowCheck.reference();
        ShadowCheck.setReference(new MiteReference());
        try {
            Stage stage = bot.stage();
            double[] c = stage != null ? stage.ensureMuseum() : new double[] {bot.minecraft().thePlayer.posX, bot.minecraft().thePlayer.posY, bot.minecraft().thePlayer.posZ};
            int done = 0;
            int step = 0;
            while (done < frames) {
                // route: 8 stations on a circle of radius 10 around the museum, 12 yaws x 3 pitches each, 4 frames per pose
                int station = (step / 36) % 8;
                int look = step % 36;
                double ang = station * Math.PI / 4;
                float yaw = (look % 12) * 30.0F - 180.0F;
                float pitch = (look / 12) * 40.0F - 40.0F;
                if (stage != null) {
                    stage.place(c[0] + 10 * Math.cos(ang), c[1], c[2] + 10 * Math.sin(ang), yaw, pitch);
                }
                int n = Math.min(4, frames - done);
                bot.frames(n);
                done += n;
                step++;
            }
        } finally {
            ShadowCheck.setReference(prev);
        }
        long mism = ShadowCheck.mismatches() - before;
        s.info("frames", frames + " frames over " + "the camera route; per frame hash lines are logged by ShadowCheck.endFrame()");
        s.check(mism == 0, "ShadowCheck mismatches == 0 over " + frames + " frames (Packet87 set and entity draw set)", mism + " mismatch(es), see the log of ShadowCheck");
    }

    /** MITE's original behaviour. */
    private static final class MiteReference implements ShadowCheck.Reference {
        @Override
        public boolean entityPasses(Entity var7, Vec3 par1Vec3, ICamera par2ICamera, Minecraft mc, boolean renderOverrideIgnored) {
            // ---- verbatim from RenderGlobal.renderEntities (R196) ----
            boolean render_override = var7 != mc.thePlayer && var7.getDistanceSqToEntity(mc.thePlayer) < 16.0;
            if (var7 instanceof EntityDragon || var7 instanceof EntityDragonPart) {
                render_override = true;
            }

            boolean var8 = render_override
                ? true
                : var7.isInRangeToRenderVec3D(par1Vec3)
                    && (var7.ignoreFrustumCheck
                        || par2ICamera.isBoundingBoxInFrustum(var7.boundingBox)
                        || var7.riddenByEntity == mc.thePlayer
                        || var7 instanceof EntityPlayer && var7 != mc.thePlayer);
            if (!var8 && var7 instanceof EntityLiving) {
                EntityLiving var9 = (EntityLiving) var7;
                if (var9.getLeashed() && var9.getLeashedToEntity() != null) {
                    Entity var10 = var9.getLeashedToEntity();
                    var8 = par2ICamera.isBoundingBoxInFrustum(var10.boundingBox);
                }
            }

            return var8
                && (var7 != mc.renderViewEntity || mc.gameSettings.thirdPersonView != 0 || mc.renderViewEntity.inBed())
                && (mc.theWorld.blockExists(MathHelper.floor_double(var7.posX), 0, MathHelper.floor_double(var7.posZ)) || render_override);
            // ---- end verbatim ----
        }

        @Override
        public RaycastCollision mouseOver(Minecraft mc, float partial) {
            // EntityRenderer.getMouseOver: rc = player.getSelectedObject(partial, false)
            if (mc.renderViewEntity == null || mc.theWorld == null || !(mc.renderViewEntity instanceof EntityPlayer player)) {
                return null;
            }
            return player.getSelectedObject(partial, false);
        }
    }
}
