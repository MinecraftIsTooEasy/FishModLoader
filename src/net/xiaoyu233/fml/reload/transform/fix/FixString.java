package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.io.DataInput;
import java.io.IOException;

@Mixin(Packet.class)
public class FixString {
    @Overwrite
    public static String readString(DataInput par0DataInput, int par1) throws IOException {
        int var2 = par0DataInput.readShort();
        if (var2 < 0) {
            throw new IOException("Received string length is less than zero! Weird string!");
        }
        StringBuilder var3 = new StringBuilder();
        for (int var4 = 0; var4 < var2; ++var4) {
            var3.append(par0DataInput.readChar());
        }
        return var3.toString();
    }
}
