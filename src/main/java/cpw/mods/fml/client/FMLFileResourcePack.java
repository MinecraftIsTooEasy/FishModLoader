package cpw.mods.fml.client;

import cpw.mods.fml.common.ModContainer;
import net.minecraft.client.resources.FileResourcePack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;

public class FMLFileResourcePack extends FileResourcePack {

    private ModContainer container;

    public FMLFileResourcePack(ModContainer container)
    {
        super(container.getSource());
        this.container = container;
    }

    @Override
    public String getPackName() {
        return "FMLFileResourcePack:" + container.getName();
    }


//    @Override
//    protected InputStream getInputStreamByName(String resourceName)
//    {
//        try
//        {
//            return super.getInputStreamByName(resourceName);
//        }
//        catch (IOException ioe)
//        {
//            if ("pack.mcmeta".equals(resourceName))
//            {
//                FMLLog.log(container.getName(), Level.WARNING, "Mod %s is missing a pack.mcmeta file, things may not work well", container.getName());
//                return new ByteArrayInputStream(("{\n" +
//                        " \"pack\": {\n"+
//                        "   \"description\": \"dummy FML pack for "+container.getName()+"\",\n"+
//                        "   \"pack_format\": 1\n"+
//                        "}\n" +
//                        "}").getBytes(Charsets.UTF_8));
//            }
//            else throw ioe;
//        }
//    }




    @Override
    public BufferedImage getPackImage()
    {
        try {
            return ImageIO.read(getInputStreamByName(container.getMetadata().logoFile));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
