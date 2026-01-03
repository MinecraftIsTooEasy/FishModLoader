package net.xiaoyu233.fmlinstaller;

import java.io.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class FileUtil {

    private static int BUFFERSIZE = 8192;

    public static void copyNoClose(InputStream source, OutputStream target) throws IOException {
        byte[] buf = new byte[8192];

        int n;
        while ((n = source.read(buf)) > 0) {
            target.write(buf, 0, n);
        }
    }

    public static File createJar(String root, String to, String mainClass) throws IOException {
        if (!new File(root).exists()) {
            return null;
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", mainClass);//指定Main Class


        final File jarFile = new File(to);

        JarOutputStream out = new JarOutputStream(new FileOutputStream(jarFile), manifest);
        createTempJarInner(out, new File(root), "");
        out.flush();
        out.close();
        return jarFile;
    }

    private static void createTempJarInner(JarOutputStream out, File f,
            String base) throws IOException {
        if (f.isDirectory()) {
            File[] fl = f.listFiles();
            if (base.length() > 0) {
                base = base + "/";
            }
            for (File file : fl) {
                createTempJarInner(out, file, base + file.getName().replace("a_u_x","aux"));
            }
        } else {
            out.putNextEntry(new JarEntry(base));
            FileInputStream in = new FileInputStream(f);
            byte[] buffer = new byte[1024];
            int n = in.read(buffer);
            while (n != -1) {
                out.write(buffer, 0, n);
                n = in.read(buffer);
            }
            in.close();
        }
    }

    public static boolean delete(String path){
        File file = new File(path);
        if(!file.exists()){
            return false;
        }
        if(file.isFile()){
            return file.delete();
        }
        File[] files = file.listFiles();
        for (File f : files) {
            if(f.isFile()){
                if(!f.delete()){
                    System.out.println(f.getAbsolutePath()+" delete error!");
                    return false;
                }
            }else{
                if(!delete(f.getAbsolutePath())){
                    return false;
                }
            }
        }
        return file.delete();
    }
    /**
     * 解压缩
     *
     * @param fileName
     * @param path
     */
    public static List<String> unzip(String fileName, String path) {
        FileOutputStream fos = null;
        InputStream is = null;
        List<String> filePaths = new ArrayList<String>();
        try {
            ZipFile zf = new ZipFile(new File(fileName));
            Enumeration<?> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry zn = (ZipEntry) en.nextElement();
                if (!zn.isDirectory()) {
                    is = zf.getInputStream(zn);
                    File f = new File(path + zn.getName());
                    File file = f.getParentFile();
                    file.mkdirs();
                    fos = new FileOutputStream(path + zn.getName());
                    int len;
                    byte[] bufer = new byte[BUFFERSIZE];
                    while (-1 != (len = is.read(bufer))) {
                        fos.write(bufer, 0, len);
                    }
                    fos.close();
                    filePaths.add(path + zn.getName());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (null != is) {
                    is.close();
                }
                if (null != fos) {
                    fos.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return filePaths;
    }

    public static void unzip(InputStream in, String path) throws IOException {
        System.out.println(path);
        try (ZipInputStream zipInputStream = new ZipInputStream(in)) {
            ZipEntry entry;
            FileOutputStream fileOutputStream;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                File file = new File(path +File.separator+ entry.getName().replace("aux","a_u_x"));
                if (entry.isDirectory()){
                    file.mkdirs();
                }else {
                    file.getParentFile().mkdirs();
                    file.createNewFile();
                    fileOutputStream = new FileOutputStream(file);
                    copyNoClose(zipInputStream, fileOutputStream);
                    zipInputStream.closeEntry();
                    fileOutputStream.close();
                }
            }
        }
    }

    public static Properties readProperties(String fileName){
        try {
            Properties properties = new Properties();
            InputStream in = FileUtil.class.getResourceAsStream("/"+fileName);
            BufferedReader bf = new BufferedReader(new InputStreamReader(in));
            properties.load(bf);
            return properties;
        }catch (IOException e){
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 压缩
     *
     * @param paths
     * @param fileName
     */
    public static void zip(String fileName, File paths) {
        JarOutputStream zos = null;
        try {
            zos = new JarOutputStream(new FileOutputStream(fileName));
            for (File filePath : paths.listFiles()) {
                // 递归压缩文件
                String relativePath = filePath.getName();
                if (filePath.isDirectory()) {
                    relativePath += File.separator;
                }
                zipFile(filePath, relativePath, zos);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (zos != null) {
                    zos.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void zipFile(File file, String relativePath, JarOutputStream zos) {
        InputStream is = null;
        try {
            if (!file.isDirectory()) {
                JarEntry zp = new JarEntry(relativePath);
                zos.putNextEntry(zp);
                is = new FileInputStream(file);
                byte[] buffer = new byte[BUFFERSIZE];
                int length;
                while ((length = is.read(buffer)) >= 0) {
                    zos.write(buffer, 0, length);
                }
                zos.flush();
                zos.closeEntry();
            } else {
                String tempPath;
                for (File f : file.listFiles()) {
                    tempPath = relativePath + f.getName();
                    if (f.isDirectory()) {
                        tempPath += File.separator;
                    }
                    zipFile(f, tempPath, zos);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}