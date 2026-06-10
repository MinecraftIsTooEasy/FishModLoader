package net.minecraftforge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;

/**
 * STUB. The original GuiIngameForge replaces vanilla's HUD renderer with
 * one that emits {@link net.minecraftforge.client.event.RenderGameOverlayEvent}
 * around each element. MITE rewrote most of the client HUD pipeline (private
 * fields, removed methods, AttributeInstance API changes), so a faithful
 * port would have to rewrite this file end-to-end against MITE's HUD code.
 *
 * <p>This shell preserves the public type so Forge mods can {@code instanceof}
 * check it; actual overlay events will be re-introduced in a follow-up
 * pass that hooks MITE's {@code GuiIngame} via Mixin.
 */
public class GuiIngameForge extends GuiIngame {
    public static boolean renderHelmet      = true;
    public static boolean renderArmor       = true;
    public static boolean renderPortal      = true;
    public static boolean renderHotbar      = true;
    public static boolean renderAir         = true;
    public static boolean renderHealth      = true;
    public static boolean renderHealthMount = true;
    public static boolean renderFood        = true;
    public static boolean renderExperience  = true;
    public static boolean renderJumpBar     = true;

    public GuiIngameForge(Minecraft mc) {
        super(mc);
    }
}
