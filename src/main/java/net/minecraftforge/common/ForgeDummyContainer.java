package net.minecraftforge.common;

import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModMetadata;

import java.util.Arrays;

/**
 * STUB. The original ForgeDummyContainer registers Forge as a "mod", wires
 * its packet handler, etc. We keep a minimal placeholder — Forge identifies
 * itself but does not ship a network handler (stage 6 will fix).
 */
public class ForgeDummyContainer extends DummyModContainer implements ModContainer {

    public ForgeDummyContainer() {
        super(new ModMetadata());
        ModMetadata meta = getMetadata();
        meta.modId = "Forge";
        meta.name = "Minecraft Forge";
        meta.version = ForgeVersion.getVersion();
        meta.credits = "Made possible with help from many people";
        meta.authorList = Arrays.asList("LexManos", "Eloraam", "Spacetoad");
        meta.description = "Minecraft Forge for FishModLoader (stub).";
        meta.url = "https://minecraftforge.net";
    }
}
