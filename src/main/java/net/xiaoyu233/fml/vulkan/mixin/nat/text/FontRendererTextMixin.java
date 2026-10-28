package net.xiaoyu233.fml.vulkan.mixin.nat.text;

import java.util.Random;
import net.minecraft.ChatAllowedCharacters;
import net.minecraft.EnumChatFormatting;
import net.minecraft.FontRenderer;
import net.minecraft.Minecraft;
import net.minecraft.ResourceLocation;
import net.minecraft.TextureManager;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.text.TextRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Native area "text" (ARCHITECTURE-NATIVE 3.8): the GL render methods of {@code FontRenderer} emit glyph / solid quads through
 * {@link TextRenderer}; metrics, colour codes, random sequence, bold/italic/shadow arithmetic are MITE's. Non-render methods untouched.
 */
@Mixin(FontRenderer.class)
public abstract class FontRendererTextMixin {
    @Shadow public int FONT_HEIGHT;
    @Shadow public Random fontRandom;
    @Shadow private int[] charWidth;
    @Shadow private byte[] glyphWidth;
    @Shadow @Final private ResourceLocation locationFontTexture;
    @Shadow @Final private TextureManager renderEngine;
    @Shadow private float posX;
    @Shadow private float posY;
    @Shadow private boolean unicodeFlag;
    @Shadow private float red;
    @Shadow private float blue;
    @Shadow private float green;
    @Shadow private float alpha;
    @Shadow private int textColor;
    @Shadow private boolean randomStyle;
    @Shadow private boolean boldStyle;
    @Shadow private boolean italicStyle;
    @Shadow private boolean underlineStyle;
    @Shadow private boolean strikethroughStyle;

    @Shadow private ResourceLocation getUnicodePageLocation(int page) { throw new AbstractMethodError(); }
    @Shadow private void loadGlyphTexture(int page) { throw new AbstractMethodError(); }
    @Shadow private float renderCharAtPos(int index, char c, boolean italic) { throw new AbstractMethodError(); }

    /**
     * @author fml-native
     * @reason native renderer: glyph quad through TextRenderer instead of GL immediate mode
     */
    @Overwrite
    private float renderDefaultChar(int par1, boolean par2) {
        float var3 = par1 % 16 * 8;
        float var4 = par1 / 16 * 8;
        float var5 = par2 ? 1.0F : 0.0F;
        this.renderEngine.bindTexture(this.locationFontTexture);
        float var6 = this.charWidth[par1] - 0.01F;
        TextRenderer.glyph(Draw.boundTexture(0), this.posX, this.posY, var6 - 1.0F, 7.99F,
                var3 / 128.0F, var4 / 128.0F, (var3 + var6 - 1.0F) / 128.0F, (var4 + 7.99F) / 128.0F, var5);
        return this.charWidth[par1];
    }

    /**
     * @author fml-native
     * @reason native renderer: glyph quad through TextRenderer instead of GL immediate mode
     */
    @Overwrite
    private float renderUnicodeChar(char par1, boolean par2) {
        if (this.glyphWidth[par1] == 0) {
            return 0.0F;
        }

        int var3 = par1 / 256;
        this.loadGlyphTexture(var3);
        int var4 = this.glyphWidth[par1] >>> 4;
        int var5 = this.glyphWidth[par1] & 15;
        float var6 = var4;
        float var7 = var5 + 1;
        float var8 = par1 % 16 * 16 + var6;
        float var9 = (par1 & 255) / 16 * 16;
        float var10 = var7 - var6 - 0.02F;
        float var11 = par2 ? 1.0F : 0.0F;
        TextRenderer.glyph(Draw.boundTexture(0), this.posX, this.posY, var10 / 2.0F, 7.99F,
                var8 / 256.0F, var9 / 256.0F, (var8 + var10) / 256.0F, (var9 + 15.98F) / 256.0F, var11);
        return (var7 - var6) / 2.0F + 1.0F;
    }

    /**
     * @author fml-native
     * @reason native renderer: colour through Draw.color instead of glColor4f
     */
    @Overwrite
    private int renderString(String par1Str, int par2, int par3, int par4, boolean par5) {
        if (par1Str == null) {
            return 0;
        }

        if ((par4 & -67108864) == 0) {
            par4 |= -16777216;
        }

        if (par5) {
            par4 = (par4 & 16579836) >> 2 | par4 & 0xFF000000;
        }

        this.red = (par4 >> 16 & 0xFF) / 255.0F;
        this.blue = (par4 >> 8 & 0xFF) / 255.0F;
        this.green = (par4 & 0xFF) / 255.0F;
        this.alpha = (par4 >> 24 & 0xFF) / 255.0F;
        Draw.color(this.red, this.blue, this.green, this.alpha);
        this.posX = par2;
        this.posY = par3;
        this.renderStringAtPos(par1Str, par5);
        return (int)this.posX;
    }

    /**
     * @author fml-native
     * @reason native renderer: colour via Draw.color, underline/strikethrough via TextRenderer.solidQuad (structure and indexOf kept)
     */
    @Overwrite
    private void renderStringAtPos(String par1Str, boolean par2) {
        // pending decorations belong to the previous string's pose / colour: emit them before this string starts
        TextRenderer.flushSolids();
        for (int var3 = 0; var3 < par1Str.length(); var3++) {
            char var4 = par1Str.charAt(var3);
            if (var4 == 167 && var3 + 1 < par1Str.length()) {
                // a formatting code may change colour or decoration: emit pending underline/strikethrough first
                TextRenderer.flushSolids();
                EnumChatFormatting enum_chat_formatting = EnumChatFormatting.getByChar(Character.toLowerCase(par1Str.charAt(var3 + 1)));
                if (enum_chat_formatting == null) {
                    enum_chat_formatting = EnumChatFormatting.WHITE;
                }

                if (enum_chat_formatting.isColor()) {
                    this.randomStyle = false;
                    this.boldStyle = false;
                    this.strikethroughStyle = false;
                    this.underlineStyle = false;
                    this.italicStyle = false;
                    int var6;
                    if (Minecraft.theMinecraft.gameSettings.anaglyph) {
                        var6 = par2 ? enum_chat_formatting.rgb_anaglyph_shadow : enum_chat_formatting.rgb_anaglyph;
                    } else {
                        var6 = par2 ? enum_chat_formatting.rgb_shadow : enum_chat_formatting.rgb;
                    }

                    this.textColor = var6;
                    Draw.color((var6 >> 16) / 255.0F, (var6 >> 8 & 0xFF) / 255.0F, (var6 & 0xFF) / 255.0F, this.alpha);
                } else if (enum_chat_formatting == EnumChatFormatting.OBFUSCATED) {
                    this.randomStyle = true;
                } else if (enum_chat_formatting == EnumChatFormatting.BOLD) {
                    this.boldStyle = true;
                } else if (enum_chat_formatting == EnumChatFormatting.STRIKETHROUGH) {
                    this.strikethroughStyle = true;
                } else if (enum_chat_formatting == EnumChatFormatting.UNDERLINE) {
                    this.underlineStyle = true;
                } else if (enum_chat_formatting == EnumChatFormatting.ITALIC) {
                    this.italicStyle = true;
                } else if (enum_chat_formatting == EnumChatFormatting.RESET) {
                    this.randomStyle = false;
                    this.boldStyle = false;
                    this.strikethroughStyle = false;
                    this.underlineStyle = false;
                    this.italicStyle = false;
                    Draw.color(this.red, this.blue, this.green, this.alpha);
                }

                var3++;
            } else {
                int var5 = ChatAllowedCharacters.allowedCharacters.indexOf(var4);
                if (this.randomStyle && var5 > 0) {
                    int var6;
                    do {
                        var6 = this.fontRandom.nextInt(ChatAllowedCharacters.allowedCharacters.length());
                    } while (this.charWidth[var5 + 32] != this.charWidth[var6 + 32]);

                    var5 = var6;
                }

                float var11 = this.unicodeFlag ? 0.5F : 1.0F;
                boolean var7 = (var5 <= 0 || this.unicodeFlag) && par2;
                if (var7) {
                    this.posX -= var11;
                    this.posY -= var11;
                }

                float var8 = this.renderCharAtPos(var5, var4, this.italicStyle);
                if (var7) {
                    this.posX += var11;
                    this.posY += var11;
                }

                if (this.boldStyle) {
                    this.posX += var11;
                    if (var7) {
                        this.posX -= var11;
                        this.posY -= var11;
                    }

                    this.renderCharAtPos(var5, var4, this.italicStyle);
                    this.posX -= var11;
                    if (var7) {
                        this.posX += var11;
                        this.posY += var11;
                    }

                    var8++;
                }

                if (this.strikethroughStyle) {
                    TextRenderer.solidQuad(this.posX, this.posY + this.FONT_HEIGHT / 2 - 1.0F, this.posX + var8,
                            this.posY + this.FONT_HEIGHT / 2);
                }

                if (this.underlineStyle) {
                    int var10 = this.underlineStyle ? -1 : 0;
                    TextRenderer.solidQuad(this.posX + var10, this.posY + this.FONT_HEIGHT - 1.0F, this.posX + var8,
                            this.posY + this.FONT_HEIGHT);
                }

                this.posX += (int)var8;
            }
        }
        // the string ends: its decorations are emitted now, while its pose and colour are still current
        TextRenderer.flushSolids();
    }
}
