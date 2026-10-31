package net.xiaoyu233.fml.vulkan.testbot.cmd.nr23;

import java.util.List;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-23 (owner: NR-23): {@code nr23check}. */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-23";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new Nr23CheckCommand());
    }

    /** {@code nr23check}: reports the GlShim fallback count and verifies that the 15 NR-23 mixin target classes load. */
    private static final class Nr23CheckCommand implements DevCommand {
        private static final String[] TARGETS = {
            "RenderArrow", "RenderBoat", "RenderEnchantmentTable", "RenderEnderCrystal", "RenderEntity",
            "RenderFallingSand", "RenderFireball", "RenderLeashKnot", "RenderMinecart", "RenderPainting",
            "RenderSnowball", "RenderTNTPrimed", "RenderTntMinecart", "RenderWitherSkull", "RenderXPOrb"
        };

        @Override
        public String name() {
            return "nr23check";
        }

        @Override
        public String description() {
            return "GlShim fallback count and load check of the 15 NR-23 mixin target classes";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            long fallbacks = GlShim.fallbackCount();
            s.check(fallbacks == 0, "GlShim fallbacks", "fallbackCount = " + fallbacks + " (expected 0)");
            for (String simple : TARGETS) {
                String fqn = "net.minecraft." + simple;
                try {
                    Class.forName(fqn, false, Nr23CheckCommand.class.getClassLoader());
                    s.ok(fqn);
                } catch (ClassNotFoundException | LinkageError e) {
                    s.fail(fqn, "not loadable: " + e);
                }
            }
        }
    }
}
