package net.xiaoyu233.fml.vulkan.testbot.cmd.nr28;

import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-28 (owner: NR-28): the integration gates. */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-28";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new Cmd("nr28Route", "args: rd=<far|normal|short|tiny>: set-up of the integration route scenes", Commands::route),
            new Cmd("mixinAudit", "every native mixin applied", Gates::mixinAudit),
            new Cmd("counterGate", "GLAudit/GlShim/validation/inlineCopies counters are 0", Gates::counterGate),
            new Cmd("pipelineGate", "warm run: 0 render-thread pipeline creations and misses", Gates::pipelineGate),
            new Cmd("seedRefresh", "args: <seedPath>: merge the pipeline journal into the seed list", Gates::seedRefresh),
            new Cmd("scanGate", "args: <gl-sites-transformed.json>: transformed scan must be 0", Gates::scanGate),
            new Cmd("staticAudit", "args: [root] [docs]: native mixin configs vs source tree, Appendix A vs inventory (no game launch)", AuditStatic::staticAudit));
    }

    /** Set-up command of the route scenes: render distance via the (private) GameSettings field. dim= is not supported (no safe dimension switch in the museum runner). */
    private static void route(Report.Section s, String[] args) throws Exception {
        net.xiaoyu233.fml.vulkan.testbot.TestBot bot = net.xiaoyu233.fml.vulkan.testbot.TestBot.get();
        if (bot == null) {
            s.warn("nr28Route", "test bot not running");
            return;
        }
        for (String a : args) {
            if (a.startsWith("rd=")) {
                int v = switch (a.substring(3)) { case "far" -> 0; case "normal" -> 1; case "short" -> 2; case "tiny" -> 3; default -> -1; };
                s.check(v >= 0, "nr28Route " + a, "unknown render distance");
                if (v >= 0) {
                    // Public API instead of reflection on the private field: setOptionValue adds a delta modulo 4 (the stored value
                    // is 0..3), so the delta to the target is computed on the client thread from the current value. Game types stay
                    // inside the lambda, so loading this provider does not touch net.minecraft.
                    bot.client(() -> {
                        net.minecraft.GameSettings opts = bot.minecraft().gameSettings;
                        int delta = (v - opts.getRenderDistance()) & 3;
                        opts.setOptionValue(net.minecraft.EnumOptions.RENDER_DISTANCE, delta);
                        return null;
                    });
                    bot.frames(20);
                }
            } else {
                s.warn("nr28Route " + a, "NOT SUPPORTED: dimension switch is not implemented; this scene captures the museum world");
            }
        }
    }

    private interface Body {
        void run(Report.Section s, String[] args) throws Exception;
    }

    private record Cmd(String name, String description, Body body) implements DevCommand {
        @Override
        public void run(Report.Section section, String[] args) throws Exception {
            body.run(section, args);
        }
    }
}
