package net.xiaoyu233.fmlinstaller;

import com.google.gson.*;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Objects;

public class InstallFrame extends JFrame {
    private static final Font BIG_FONT = new Font("Default", Font.PLAIN, 19);
    private static final Font MID_FONT = new Font("Default", Font.PLAIN, 14);
    private static final String VERSION = FileUtil.readProperties("version.properties").getProperty("version");
    private final JTextField fileTextField = new JTextField("");
    private final JLabel fileTip = new JLabel("客户端路径(.minecraft文件夹):");
    private boolean forClient = true;

    public InstallFrame() {
        JLabel versionTip = new JLabel("当前版本:" + VERSION);
        JTextArea clientLauncherTip = new JTextArea("安装完成后请使用现在较流行的启动器(支持Forge或Optifine的,如HMCL)\n启动游戏,有些整合包提供的启动器无法正常启动!!");
        JTextArea serverLaunchTip = new JTextArea("安装完成后如果使用的是命令行启动\n请把批处理文件中的net.minecraft.server.MinecraftServer\n换为net.xiaoyu233.fml.relaunch.server.JarMain\n(此处可复制)\n安装完成后产生的带FML后缀的Jar即为独立Jar,可放至任意地方打开即用");
        clientLauncherTip.setBackground(new Color(238, 238, 238));
        serverLaunchTip.setBackground(new Color(238, 238, 238));
        clientLauncherTip.setForeground(Color.RED);
        serverLaunchTip.setForeground(Color.RED);
        clientLauncherTip.setEditable(false);
        serverLaunchTip.setEditable(false);
        clientLauncherTip.setFont(MID_FONT);
        serverLaunchTip.setFont(MID_FONT);
        try {
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
        } catch (UnsupportedLookAndFeelException | ClassNotFoundException | InstantiationException |
                 IllegalAccessException e) {
            e.printStackTrace();
        }
        this.setLayout(null);
        this.setResizable(false);
        serverLaunchTip.setVisible(false);
        JRadioButton installForClient = new JRadioButton("为客户端安装");
        ButtonGroup buttonGroup = new ButtonGroup();
        buttonGroup.add(installForClient);
        JRadioButton installForServer = new JRadioButton("为服务端安装");
        buttonGroup.add(installForServer);
        this.setTitle("FishModLoader-" + VERSION + " 安装程序");
        buttonGroup.setSelected(installForClient.getModel(), true);
        installForClient.addActionListener((e) -> {
            this.fileTip.setText("客户端路径(.minecraft文件夹):");
            fileTip.setBounds(150, 80, 200, 30);
            clientLauncherTip.setVisible(true);
            serverLaunchTip.setVisible(false);
            forClient = true;
        });
        installForServer.addActionListener((e) -> {
            this.fileTip.setText("服务端Jar核心文件路径(1.6.4-MITE-HDS.jar):");
            fileTip.setBounds(90, 80, 260, 30);
            clientLauncherTip.setVisible(false);
            serverLaunchTip.setVisible(true);
            forClient = false;
        });
        JButton choosePath = new JButton("...");
        choosePath.addActionListener(event -> showFileChoose());
        JButton installButton = new JButton("安装");
        installButton.addActionListener(event -> {
            new Thread(() -> {
                try {
                    installButton.setEnabled(false);
                    startInstall();
                    JOptionPane.showMessageDialog(this, "安装成功!");
                    installButton.setEnabled(true);
                } catch (IOException | InstallException e) {
                    JOptionPane.showMessageDialog(this, e.getLocalizedMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    installButton.setEnabled(true);
                    e.printStackTrace();
                }
            }).start();
        });
        installForServer.setBounds(190, 40, 200, 30);
        installForClient.setBounds(190, 10, 200, 30);
        clientLauncherTip.setBounds(40, 150, 500, 60);
        serverLaunchTip.setBounds(60, 150, 500, 100);
        fileTextField.setBounds(80, 120, 300, 30);
        fileTip.setBounds(150, 80, 200, 30);
        choosePath.setBounds(380, 122, 20, 25);
        installButton.setBounds(160, 250, 150, 70);
        versionTip.setBounds(160, 320, 240, 30);
        installForClient.setVerticalAlignment(SwingConstants.CENTER);
        installForServer.setVerticalAlignment(SwingConstants.CENTER);
        versionTip.setFont(BIG_FONT);
        this.add(fileTextField);
        this.add(fileTip);
        this.add(choosePath);
        this.add(installForClient);
        this.add(installForServer);
        this.add(installButton);
        this.add(versionTip);
        this.add(serverLaunchTip);
        this.add(clientLauncherTip);
        this.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        this.setBounds((int) (this.getToolkit().getScreenSize().getWidth() / 2) - 250,
                (int) (this.getToolkit().getScreenSize().getHeight() / 2) - 250, 500, 400);
    }

    public static void copy(InputStream source, OutputStream target) throws IOException {
        byte[] buf = new byte[8192];

        int n;
        while ((n = source.read(buf)) > 0) {
            target.write(buf, 0, n);
        }
        source.close();
        target.flush();
        target.close();
    }

    private void copyJarToLibs(File minecraftDir, String targetLibDir, String libOnJar) throws IOException,
            InstallException {
        File libDir = new File(minecraftDir, targetLibDir);
        if (libDir.exists()) {
            copy(this.getClass().getResourceAsStream(libOnJar), Files.newOutputStream(new File(libDir, libOnJar).toPath()));

        } else {
            boolean created = libDir.mkdirs();
            if (created) {
                copy(this.getClass().getResourceAsStream(libOnJar), Files.newOutputStream(new File(libDir, libOnJar).toPath()));
            } else {
                throwInstallException("创建库文件夹失败");
            }
        }
    }

    private void copyJarToLibs(File minecraftDir, String targetLibDir, String libOnJar, String toName) throws IOException,
            InstallException {
        File libDir = new File(minecraftDir, targetLibDir);
        if (libDir.exists()) {
            copy(this.getClass().getResourceAsStream(libOnJar), Files.newOutputStream(new File(libDir, toName).toPath()));

        } else {
            boolean created = libDir.mkdirs();
            if (created) {
                copy(this.getClass().getResourceAsStream(libOnJar), Files.newOutputStream(new File(libDir, toName).toPath()));
            } else {
                throwInstallException("创建库文件夹失败");
            }
        }
    }

    private void extractJarToLibrary(File minecraftDir) throws IOException, InstallException {
        copyJarToLibs(minecraftDir, "libraries\\net\\xiaoyu233\\fishmodloader\\fishmodloader\\".replace("\\", File.separator) + VERSION,
                "FishModLoader.jar","FishModLoader-" + VERSION + ".jar");
//        copyJarToLibs(minecraftDir, "libraries\\com\\google\\guava\\guava\\28.0".replace("\\",File.separator), "guava-28.0.jar");
    }
//
//    private void modifyVersionJSON(File minecraftDir) throws IOException, InstallException {
//        File file = new File(minecraftDir, "versions/1.6.4-MITE/1.6.4-MITE.json");
//        if (file.exists()) {
//            FileReader reader = new FileReader(file);
//            JsonElement versionJson = new JsonParser().parse(reader);
//            reader.close();
//            if (versionJson instanceof JsonObject) {
//                JsonObject versionObject = ((JsonObject) versionJson);
//                if (versionObject.has("libraries")) {
//                    JsonElement array = versionObject.get("libraries");
//                    if (array instanceof JsonArray) {
//                        JsonArray libs = ((JsonArray) array);
//                        boolean fmlLibSet = isFmlLibSet(libs);
//                        if (!fmlLibSet) {
//                            JsonObject fmlLibObject = new JsonObject();
//                            fmlLibObject.addProperty("name", "net.xiaoyu233.fishmodloader:fishmodloader:" + VERSION);
//                            libs.add(fmlLibObject);
//                        }
//                        if (versionObject.has("mainClass")) {
//                            if (!versionObject.get("mainClass").getAsString().equals("net.xiaoyu233.fml.relaunch.client.Main")) {
//                                versionObject.remove("mainClass");
//                                versionObject.addProperty("mainClass", "net.xiaoyu233.fml.relaunch.client.Main");
//                            }
//                        } else {
//                            throwInstallException("版本配置文件错误,请重新安装1.6.4-MITE后重试");
//                        }
//                        FileWriter writer = new FileWriter(file);
//                        writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(versionObject));
//                        writer.flush();
//                        writer.close();
//                    } else {
//                        throwInstallException("版本配置文件错误,请重新安装1.6.4-MITE后重试");
//                    }
//                } else {
//                    throwInstallException("版本配置文件错误,请重新安装1.6.4-MITE后重试");
//                }
//            } else {
//                throwInstallException("版本配置文件错误,请重新安装1.6.4-MITE后重试");
//            }
//        } else {
//            throwInstallException("无法找到版本启动配置文件,请确认是否正确安装了MITE1.6.4,文件目标:" + file.getAbsolutePath());
//        }
//    }

    private void modifyVersionJSON(File minecraftDir) throws IOException, InstallException {
        try {
            String name = "1.6.4-MITE";
            File dir = new File(minecraftDir, "versions" + "/" + name);
            if (!dir.exists()) {
                Files.createDirectories(dir.toPath());
            }
            String miteVersion = "1.6.4-MITE";

            String string = getString(this.getClass().getResourceAsStream("/config.json"));
            string = string.replace("${miteVersion}", miteVersion);
            Files.write(new File(dir, name + ".json").toPath(), string.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            exception.printStackTrace();
            JOptionPane.showMessageDialog(null, exception);
        }
    }

    private String getString(InputStream inputStream) throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));

        String line;
        while ((line = bufferedReader.readLine()) != null) {
            stringBuilder.append(line).append("\n");
        }
        return stringBuilder.toString();
    }


    private static boolean isFmlLibSet(JsonArray libs) {
        boolean fmlLibSet = false;
        for (Iterator<JsonElement> iterator = libs.iterator(); iterator.hasNext(); ) {
            JsonElement lib = iterator.next();
            if (lib instanceof JsonObject) {
                JsonObject libObject = lib.getAsJsonObject();
                String libName = libObject.get("name").getAsString();
                if (libName.contains("com.google.guava")) {
                    libObject.remove("name");
                    libObject.addProperty("name", "com.google.guava:guava:28.0");
                }
                if (libName.contains("net.xiaoyu233.fishmodloader")) {
                    if (fmlLibSet) {
                        iterator.remove();
                    } else {
                        libObject.remove("name");
                        libObject.addProperty("name", "net.xiaoyu233.fishmodloader:fishmodloader:" + VERSION);
                    }
                    fmlLibSet = true;
                }
            }
        }
        return fmlLibSet;
    }

    private void showFileChoose() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(this.forClient ? new File(this.fileTextField.getText()) : new File(this.fileTextField.getText()).getParentFile());
        fileChooser.setDialogTitle("请选择" + this.fileTip.getText());
        fileChooser.setApproveButtonText("确定");
        fileChooser.setFileSelectionMode(this.forClient ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        if (!this.forClient) {
            fileChooser.setFileFilter(new FileFilter() {
                @Override
                public boolean accept(File f) {
                    return f.getName().endsWith(".jar") || f.isDirectory();
                }

                @Override
                public String getDescription() {
                    return "服务端核心文件(*.jar)";
                }
            });
        }
        int result = fileChooser.showOpenDialog(this);
        if (JFileChooser.APPROVE_OPTION == result) {
            this.fileTextField.setText(fileChooser.getSelectedFile().getPath());
            //            System.out.println("path: "+path);
        }
    }

    private void startInstall() throws IOException, InstallException {
        if (!this.fileTextField.getText().isEmpty()) {
            if (forClient) {
                File minecraftDir = new File(this.fileTextField.getText());
                if (minecraftDir.exists() && minecraftDir.isDirectory()) {
                    extractJarToLibrary(minecraftDir);
                    modifyVersionJSON(minecraftDir);
                } else {
                    throwInstallException("所选的文件夹不存在或不是文件夹");
                }
            } else {
                File file = new File(this.fileTextField.getText());
                if (file.exists() && file.isFile()) {
                    if (file.getName().endsWith(".jar")) {
                        JLabel label = new JLabel("安装中...");
                        label.setFont(BIG_FONT);
                        JDialog dialog = new JDialog(this);
                        dialog.setLayout(new FlowLayout());
                        dialog.add(label);
                        dialog.setBounds((int) this.getLocation().getX() + 100,(int)this.getLocation().getY() + 20,360,80);
                        dialog.setTitle("安装进度");
                        dialog.setModal(true);
                        dialog.setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
                        new Thread(()-> dialog.setVisible(true)).start();
                        String tempDir = Files.createTempDirectory("").toFile().getAbsolutePath();
                        label.setText("正在解压FishModLoader-" + VERSION + "...");
                        FileUtil.unzip(this.getClass().getResourceAsStream("FishModLoader.jar"),tempDir);
//                        label.setText("正在提取自解压程序...");
//                        copy(Objects.requireNonNull(this.getClass().getResourceAsStream("net/xiaoyu233/fmlinstaller/SelfExtractor.class")), Files.newOutputStream(Paths.get(tempDir, "net/xiaoyu233/fmlinstaller/SelfExtractor.class".replace("/", File.separator))));
                        label.setText("正在嵌入MITE服务器核心...");
                        copy(Objects.requireNonNull(Files.newInputStream(file.toPath())), Files.newOutputStream(Paths.get(tempDir, "MITE-HDS.jar")));
                        label.setText("正在解压guava-28.0...");
                        FileUtil.unzip(this.getClass().getResourceAsStream("guava-28.0.jar"),tempDir);
                        label.setText("正在修改MANIFEST.MF文件");
                        File manifest = new File(tempDir,"META-INF\\MANIFEST.MF".replace("\\",File.separator));
                        if (manifest.exists() && !manifest.delete()){
                            throwInstallException("修改MANIFEST.MF文件失败");
                        }
                        label.setText("正在打包...");
                        FileUtil.createJar(tempDir,this.fileTextField.getText().replace(".jar","_FML" + VERSION + ".jar"),"net.xiaoyu233.fml.relaunch.server.JarMain");
                        label.setText("正在清理缓存");
                        if (!FileUtil.delete(tempDir)){
                            throwInstallException("清理缓存失败(不影响游戏启动,但会占用磁盘空间,可之后用其他软件另行清理)");
                        }
                        dialog.setVisible(false);
                    } else {
                        throwInstallException("所选的文件不是Jar文件");
                    }
                } else {
                    throwInstallException("所选的文件不存在或不是文件");
                }
            }
        } else {
            if (forClient) {
                throwInstallException("请选择客户端(.minecraft文件夹)");
            } else {
                throwInstallException("请选择服务端Jar核心文件");
            }
        }
    }

    private void throwInstallException(String msg) throws InstallException {
        throw new InstallException(msg);
    }
}
