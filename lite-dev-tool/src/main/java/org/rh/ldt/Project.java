/*
 * Copyright (C) 2014 Riddle Hsu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.rh.ldt;

import com.almworks.sqlite4java.SQLiteException;
import com.android.ddmlib.Device;

import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.rh.ldt.util.AdbUtilEx;
import org.rh.ldt.util.DexUtilEx;
import org.rh.ldt.util.FileUtil;
import org.rh.ldt.util.StringUtil;
import org.rh.smaliex.LLog;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Properties;

public class Project {
    public static final String EXEC_BAT = Env.IS_WINDOWS ? ".bat" : "";
    public static final String EXEC_EXT = Env.IS_WINDOWS ? ".bat" : ".sh";
    public static final String TOOL_PATH = Env.getToolPath();
    public static final String ANT = TOOL_PATH + "/ant/bin/ant" + EXEC_BAT;
    public static final String DEX2JAR = TOOL_PATH + "/dex2jar/d2j-dex2jar-ex-oc" + EXEC_EXT;
    public static final String JAR2JACK = TOOL_PATH + "/jack/jill" + EXEC_EXT;
    public static final String HIDL_JAR = "hardware-hidl-%d.jar";
    public static final String HIDL_PATH = TOOL_PATH + "/apt/hidl/" + HIDL_JAR;

    public static final String BUILD_XML = "build.xml";
    public static final String DEVICE_FRAMEWORK_PATH = "/system/framework/";
    static final String FW_DEX_JARS_FOLDER = "framework-dex-jars";
    static final String FW_CLASS_JARS_FOLDER = "framework-class-jars";
    static final String FW_JACKS_FOLDER = "framework-jacks";
    static final String OUT_DEX_FILE = "classes.dex";
    static final String LIBS_POSTFIX = "-dex2jar";
    public static final String DIR_SRC = "src";
    static final String DIR_GEN = "gen";
    static final String DIR_BIN = "bin";

    public static final String PROP_FILE_BUILD = "build.properties";
    public static final String PROP_NAME = "name";
    public static final String PROP_BUILD_INFO = "build.info";
    public static final String PROP_API_LEVEL = "api.level";
    public static final String PROP_USE_JACK = "useJack";
    public static final String PROP_BOOT_JARS = "bootjars";
    public static final String PROP_SERVER_JARS = "serverjars";

    public static final String PROP_FILE_CLASSPATH = "classpath.properties";
    public static final String PROP_BOOT_LIBS = "bootlibs";
    public static final String PROP_EXT_LIBS = "extlibs";
    public static final String PROP_LIBS = "libs";
    private static boolean DO_NOTHING_IF_EXISTS = !Env.alwaysOverwriteWhenInitProject();

    public static final int API_LEVEL_N = 24;
    public static final int API_LEVEL_O = 26;

    public static final int STATUS_NONE = 0;
    public static final int STATUS_ONLINE = 0x1;
    public static final int STATUS_INITIALIZED = 0x2;

    private ArrayList<AppProject> mAppPrjs;
    private final HashSet<StatusChangeListener> mScl = new HashSet<>();
    private Device mDevice;
    private int mStatus;
    private SourceSelector mSrcSel;
    private DexReplacer.ReplaceInfo mLatestReplaceInfo;

    public final String name;
    public final String buildInfo;
    public final File folder;
    public final File dexJarsFolder;
    public final File libsFolder;
    public final File jacksFolder;
    public final File appsFolder;
    public final File replaceConfig;
    public final File propFile;

    private boolean useJack;
    private int apiLevel;
    private String[] bcp;
    private String[] scp;

    private Project(Properties prop, Device device) {
        if (device != null) {
            mDevice = device;
            mStatus = STATUS_ONLINE;
            name = device2Name(device);
            folder = new File(Env.getWorkspace(), name.replace(" ", "_"));
            apiLevel = StringUtil.toInt(device.getProperty(Device.PROP_BUILD_API_LEVEL));
            useJack = apiLevel >= API_LEVEL_N;
            if (!useJack) {
                String ver = device.getProperty("ro.build.id");
                if (ver != null && ver.length() > 0 && ver.charAt(0) >= 'N') {
                    apiLevel = API_LEVEL_N;
                    useJack = true;
                }
            }
            String tags = device.getProperty("ro.build.tags");
            String type = device.getProperty("ro.build.type");
            buildInfo = "tags:" + tags + " type:" + type;
            bcp = getBootJars(device);
            scp = getServerJars(device);
        } else {
            name = prop.getProperty(PROP_NAME);
            folder = new File(Env.getWorkspace(), name);
            buildInfo = prop.getProperty(PROP_BUILD_INFO);
            load(prop);
        }

        dexJarsFolder = new File(folder, FW_DEX_JARS_FOLDER);
        libsFolder = new File(folder, FW_CLASS_JARS_FOLDER);
        jacksFolder = new File(folder, FW_JACKS_FOLDER);
        propFile = new File(folder, PROP_FILE_BUILD);
        replaceConfig = new File(folder, DexReplacer.DEFAULT_CONFIG);
        appsFolder = new File(folder, "apps");
    }

    public void load(Properties prop) {
        apiLevel = StringUtil.toInt(prop.getProperty(PROP_API_LEVEL));
        bcp = prop.getProperty(PROP_BOOT_JARS).split(":");
        String svcJar = prop.getProperty(PROP_SERVER_JARS);
        scp = svcJar != null ? svcJar.split(":") : new String[0];
        useJack = Boolean.valueOf(prop.getProperty(PROP_USE_JACK,
                String.valueOf(apiLevel >= API_LEVEL_N)));
    }

    public Project(Properties prop) {
        this(prop, null);
        if (propFile.exists() && dexJarsFolder.exists()) {
            mStatus = STATUS_INITIALIZED;
        }
        loadApps();
    }

    public Project(Device device) {
        this(null, device);
        loadApps();
    }

    final void loadApps() {
        if (!appsFolder.isDirectory()) {
            return;
        }
        for (File app : FileUtil.listFiles(appsFolder)) {
            LLog.i(app.getName());
        }
    }

    static String[] getBootJars(Device device) {
        String[] bcp = AdbUtilEx.getBootClassPath(device);
        for (int i = 0; i < bcp.length; i++) {
            bcp[i] = StringUtil.getOnlyFilename(bcp[i], '/');
        }
        return bcp;
    }

    static String[] getServerJars(Device device) {
        String[] scp = AdbUtilEx.getPathByEnv(device, "SYSTEMSERVERCLASSPATH");
        for (int i = 0; i < scp.length; i++) {
            scp[i] = StringUtil.getOnlyFilename(scp[i], '/');
        }
        return scp;
    }

    public static String device2Name(Device device) {
        String project = device.getProperty("ro.build.project");
        if (project != null) {
            project = project.split(":")[0];
        } else {
            project = device.getProperty("ro.product.model");
        }
        String cl = device.getProperty("ro.build.changelist");
        if (cl == null) {
            cl = device.getProperty("ro.build.version");
            if (cl == null) {
                cl = device.getProperty("ro.build.id");
            }
        }
        return project + "-" + cl;
    }

    public static boolean antBuild(String buildfile, String target, Env.MessagePrinter printer) {
        return Env.exec(ANT + " -buildfile " + buildfile + " " + target, printer) == 0;
    }

    public boolean build(String buildTask) {
        if (useJack && !jacksFolder.exists()) {
            prepareJackLibs();
        }
        return antBuild(FileUtil.path(folder.getAbsolutePath(), BUILD_XML),
                buildTask, Env.STDOUT_PRINTER);
    }

    public boolean iValid() {
        return bcp != null && bcp.length > 0 && !name.startsWith("null");
    }

    public String[] getBootClassPath() {
        return bcp;
    }

    public boolean buildSrcToDex() {
        return build("buildex-skip-aidl");
    }

    public boolean buildDex() {
        // With jack, there is already dex from previous step.
        return useJack || build("dex");
    }

    public boolean buildSrcWithAidl() {
        return build("build");
    }

    public void addStatus(int s) {
        int oldStatus = mStatus;
        mStatus |= s;
        dispatchStatusChange(oldStatus);
    }

    public void removeStatus(int s) {
        int oldStatus = mStatus;
        mStatus &= ~s;
        dispatchStatusChange(oldStatus);
    }

    public int getStatus() {
        return mStatus;
    }

    public boolean isInitialized() {
        return (mStatus & STATUS_INITIALIZED) != 0;
    }

    public boolean isOnline() {
        return (mStatus & STATUS_ONLINE) != 0;
    }

    public boolean isGone() {
        return mStatus == STATUS_NONE;
    }

    public String getStatusString() {
        String str = "none";
        boolean with = false;
        if ((mStatus & STATUS_ONLINE) != 0) {
            str = "online";
            with = true;
        }
        if ((mStatus & STATUS_INITIALIZED) != 0) {
            str = with ? (str + ",initialized") : "initialized";
        }
        return str;
    }

    public void setDevice(Device device) {
        mDevice = device;
        addStatus(STATUS_ONLINE);
    }

    public Device getDevice() {
        return mDevice;
    }

    public void addStatusChangeListener(StatusChangeListener l) {
        mScl.add(l);
    }

    public void dispatchStatusChange(int oldStatus) {
        if (oldStatus != mStatus) {
            for (StatusChangeListener l : mScl) {
                l.onChange(mStatus);
            }
        }
    }

    public void delete() {
        removeStatus(STATUS_INITIALIZED);
        FileUtil.deleteFolder(folder);
    }

    public interface SourceSelector {
        void editReplaceInfo(DexReplacer.ReplaceInfo info);
    }

    public void setSourceSelector(SourceSelector sel) {
        mSrcSel = sel;
    }

    public void makeJar() {
        makeJar(null);
    }

    public void makeJar(Collection<File> srcFiles) {
        DLog.i("Start makeJar.");
        File dexFile = new File(folder, OUT_DEX_FILE);
        if (!dexFile.exists()) {
            DLog.i("makeJar: " + dexFile + " not found.");
            return;
        }

        try (DexDb db = new DexDb(dexJarsFolder.getAbsolutePath())) {
            DexBackedDexFile df = DexUtilEx.loadDex(dexFile);
            if (df == null) {
                DLog.i("makeJar: unable to load " + dexFile);
                return;
            }
            DexReplacer.ReplaceInfo info = new DexReplacer.ReplaceInfo();
            boolean buildPartial = srcFiles != null;
            if (buildPartial) {
                HashSet<String> existedJars = new HashSet<>();
                int baseLen = new File(folder, DIR_SRC).getAbsolutePath().length() + 1;
                for (File f : srcFiles) {
                    String type = f.getAbsolutePath();
                    type = type.substring(baseLen, type.length() - 5); // remove ".java"
                    if (Env.IS_WINDOWS) {
                        type = type.replace('\\', '/');
                    }
                    String jar = db.findTargetJarForType("L" + type + ";");
                    if (jar == null) {
                        // TODO pop ui for editing files without target jar
                        DLog.i("makeJar: cannot find proper jar for " + f);
                        continue;
                    }
                    String baseJar = FileUtil.path(DexReplacer.OUTPUT_FOLDER, jar);
                    if (!existedJars.contains(baseJar)) {
                        File baseJarFile = new File(folder, baseJar);
                        if (baseJarFile.exists()) {
                            existedJars.add(baseJar);
                        } else {
                            baseJar = FW_DEX_JARS_FOLDER + "/" + jar;
                        }
                    }
                    String cmd = OUT_DEX_FILE + ":" + baseJar;
                    info.addTarget(cmd, type);
                }
            } else {
                for (ClassDef cls : df.getClasses()) {
                    String jar = db.findTargetJarForType(cls.getType());
                    String cmd = OUT_DEX_FILE + ":" + FW_DEX_JARS_FOLDER + "/" + jar;
                    info.getTarget(cmd).add(DexUtilEx.classToSourceName(cls));
                }
            }

            if (!info.isAllEmpty()) {
                if (!buildPartial) {
                    if (mSrcSel != null) {
                        mSrcSel.editReplaceInfo(info);
                    }
                    info.saveTo(replaceConfig);
                } else if (!info.equals(mLatestReplaceInfo)) {
                    mLatestReplaceInfo = info;
                    info.saveTo(replaceConfig);
                }
                execDexReplacer(info, folder);
            } else {
                DLog.i("makeJar: nothing to do.");
            }

        } catch (IOException | SQLiteException ex) {
            DLog.ex(ex);
        }
    }

    public interface StatusChangeListener {
        void onChange(int status);
    }

    public static void execDexReplacer(DexReplacer.ReplaceInfo info, File folder) {
        if (Env.NEW_PROCESS) {
            Env.execVm(DexReplacer.class, folder.getAbsolutePath());
        } else {
            DexReplacer.execReplace(info, folder);
        }
    }

    public static void dex2jar(String input, String output, Env.MessagePrinter printer) {
        Env.exec(DEX2JAR + (output != null ? (" -o " + output) : "") + " " + input, printer);
    }

    public static void jar2jack(String input, String output, Env.MessagePrinter printer) {
        Env.exec(JAR2JACK + " --tolerant --verbose"
                + (output != null ? (" --output " + output) : "") + " " + input, printer);
    }

    public void initProject() {
        initProject(false);
    }

    public void initAppProject(File apk) {
        if (mAppPrjs == null) {
            mAppPrjs = new ArrayList<>();
            FileUtil.mkdirs(appsFolder);
        }
        mAppPrjs.add(new AppProject(apk));
    }

    public void initProject(boolean force) {
        FileUtil.mkdirs(folder);
        // TODO preprocess preopt rom

        DLog.i("Preparing project at " + folder);

        File buildXml = new File(folder, BUILD_XML);
        if (force || !buildXml.exists()) {
            try (DataInputStream dis = new DataInputStream(
                    Project.class.getResourceAsStream(BUILD_XML))) {
                try (FileOutputStream fos = new FileOutputStream(buildXml)) {
                    fos.write(FileUtil.readBytes(dis));
                }
            } catch (IOException ex) {
                DLog.ex(ex);
            }
        }

        if (force || !propFile.exists()) {
            Properties prop = Env.newProp();
            prop.put(PROP_NAME, name);
            prop.put(PROP_BUILD_INFO, buildInfo);
            prop.put(PROP_API_LEVEL, apiLevel);
            if (mDevice != null && mDevice.isOnline()) {
                prop.put(PROP_BOOT_JARS, StringUtil.join(getBootJars(mDevice), ":"));
                prop.put(PROP_SERVER_JARS, StringUtil.join(getServerJars(mDevice), ":"));
            } else {
                prop.put(PROP_BOOT_JARS, StringUtil.join(bcp, ":"));
                prop.put(PROP_SERVER_JARS, StringUtil.join(scp, ":"));
            }
            prop.put("tool.dir", Env.TOOL_PATH);
            prop.put("dx", "${tool.dir}//compiler/dx/dx");
            prop.put("javac", "${tool.dir}/compiler/javac/javac");
            prop.put("jack", "${tool.dir}/compiler/jack/jack");
            prop.put("jill", "${tool.dir}/compiler/jack/jill");
            prop.put("aidl", "${tool.dir}/apt/aidl");
            prop.put("framework.aidl", "${tool.dir}/apt/framework.aidl");
            prop.put("encoding", "UTF-8");
            prop.put(PROP_USE_JACK, useJack);
            String javaVer = apiLevel >= API_LEVEL_N ? "1.8" : "1.7";
            prop.put("java.source", javaVer);
            prop.put("java.target", javaVer);
            prop.put("fwclasspath", FW_CLASS_JARS_FOLDER);
            prop.put("fwjacks", FW_JACKS_FOLDER);
            prop.put("dexfile", OUT_DEX_FILE);
            prop.put("src", DIR_SRC);
            prop.put("gen", DIR_GEN);
            prop.put("bin", DIR_BIN);
            prop.put("verbose", "false");
            Env.saveProp(prop, propFile);
        }

        boolean initDb = false;
        if (force || !dexJarsFolder.exists()) {
            initDb = true;
            if (isOnline()) {
                if (!DO_NOTHING_IF_EXISTS) {
                    FileUtil.deleteFolder(dexJarsFolder);
                    FileUtil.mkdirs(dexJarsFolder);
                }
                for (String file : AdbUtilEx.getFileList(mDevice, DEVICE_FRAMEWORK_PATH)) {
                    if (file.endsWith(".jar")) {
                        String remoteFile = DEVICE_FRAMEWORK_PATH + file;
                        DLog.i("Pulling " + remoteFile);
                        if (DO_NOTHING_IF_EXISTS && new File(
                                dexJarsFolder.getAbsolutePath(),
                                StringUtil.getOnlyFilename(remoteFile, '/')).exists()) {
                            continue;
                        }
                        AdbUtilEx.pullFileToFolder(mDevice, remoteFile,
                                dexJarsFolder.getAbsolutePath());
                    }
                }
            }
        }

        if (force || !libsFolder.exists()) {
            if (!DO_NOTHING_IF_EXISTS) {
                FileUtil.deleteFolder(libsFolder);
                FileUtil.mkdirs(libsFolder);
            }
            String libsPath = libsFolder.getAbsolutePath();
            for (File d : FileUtil.listFiles(dexJarsFolder)) {
                if (!d.getName().endsWith(".jar") || !DexUtilEx.containsDex(d)) {
                    continue;
                }
                DLog.i("Converting dex to class jar: " + d);
                String outJar = FileUtil.path(libsPath,
                        StringUtil.appendTail(d.getName(), LIBS_POSTFIX));
                if (DO_NOTHING_IF_EXISTS && new File(outJar).exists()) {
                    continue;
                }
                dex2jar(d.getAbsolutePath(), outJar, Env.STDOUT_PRINTER);
            }
            prepareJackLibs();
        }
        writeClassPathProp(null, force);

        String dexPath = dexJarsFolder.getAbsolutePath();
        if (DO_NOTHING_IF_EXISTS && DexDb.getDbFile(dexPath).exists()) {
            DLog.i("Skip existed DB");
        } else if (initDb) {
            try (DexDb db = new DexDb(dexPath, true)) {
                db.setApiLevel(apiLevel);
                DLog.i("Creating class DB.");

                // Boot classes first, by the order from device.
                File[] bootDexFiles = new File[bcp.length];
                HashSet<File> added = new HashSet<>();
                for (int i = 0; i < bootDexFiles.length; i++) {
                    bootDexFiles[i] = new File(dexJarsFolder, bcp[i]);
                    added.add(bootDexFiles[i]);
                }
                db.addClassesToDb(bootDexFiles);

                HashSet<File> nonBoot = new HashSet<>();
                nonBoot.addAll(Arrays.asList(FileUtil.getFiles(dexPath, ".jar")));
                nonBoot.removeAll(added);
                db.addClassesToDb(nonBoot.toArray(bootDexFiles));
                DLog.i("DB saved at " + db.getDbFile());
            } catch (IOException | SQLiteException ex) {
                DLog.ex(ex);
            }
        }

        LinkedHashSet<String> allJars = new LinkedHashSet<>();
        allJars.addAll(Arrays.asList(bcp));
        allJars.addAll(Arrays.asList(scp));

        String[] libs = new String[allJars.size()];
        allJars.toArray(libs);
        int i = 0;
        for (String jar : allJars) {
            libs[i++] = FW_CLASS_JARS_FOLDER + "/" + StringUtil.appendTail(jar, LIBS_POSTFIX);
        }

        writeEclipseClassPath(folder.getAbsolutePath(), libs);
        writeEclipseProject(folder.getAbsolutePath(), name);
        FileUtil.mkdirs(new File(folder, DIR_SRC));
        FileUtil.mkdirs(new File(folder, DIR_GEN));
        FileUtil.mkdirs(new File(folder, DIR_BIN));
        addStatus(STATUS_INITIALIZED);
    }

    void prepareJackLibs() {
        if (!useJack) {
            return;
        }
        File[] jars = FileUtil.listFiles(libsFolder);
        if (jars.length > 0) {
            FileUtil.mkdirs(jacksFolder);
            String jacksPath = jacksFolder.getAbsolutePath();
            if (apiLevel >= API_LEVEL_O) {
                File hidlJar = new File(String.format(HIDL_PATH, apiLevel));
                if (hidlJar.exists()) {
                    jars = Arrays.copyOf(jars, jars.length + 1);
                    jars[jars.length - 1] = hidlJar;
                } else {
                    DLog.i("Hidl not found: " + hidlJar);
                }
            }
            for (File j : jars) {
                if (!j.getName().endsWith(".jar")) {
                    continue;
                }
                DLog.i("Converting jar to jack: " + j);
                String outJack = FileUtil.path(jacksPath,
                        FileUtil.getFilenameNoExt(j.getName()) + ".jack");
                if (DO_NOTHING_IF_EXISTS && new File(outJack).exists()) {
                    continue;
                }
                jar2jack(j.getAbsolutePath(), outJack, Env.STDOUT_PRINTER);
            }
        }
    }

    static String[] getLibs(String[] jars) {
        String[] libs = new String[jars.length];
        for (int i = 0; i < jars.length; i++) {
            libs[i] = StringUtil.appendTail(jars[i], LIBS_POSTFIX);
        }
        return libs;
    }

    // TODO Provide an UI to select lib manually and update to prop file, eclipse prj
    public void writeClassPathProp(String[] selectedLibs, boolean force) {
        File propFile = new File(folder, PROP_FILE_CLASSPATH);
        if (selectedLibs != null) {
            Properties prop = Env.loadProp(propFile);
            prop.put(PROP_LIBS, StringUtil.join(selectedLibs, ","));
            Env.saveProp(prop, propFile);
            return;
        }
        if (force || !propFile.exists()) {
            Properties prop = Env.newProp();
            prop.put(PROP_BOOT_LIBS, StringUtil.join(getLibs(bcp), ","));
            if (apiLevel >= API_LEVEL_O) {
                // O's hidl type definition was eliminated in compile time, so add here manually.
                prop.put(PROP_EXT_LIBS, String.format(
                        HIDL_JAR, apiLevel).replace(".jar", ".jack"));
            }
            // Default add system server classes.
            prop.put(PROP_LIBS, StringUtil.join(getLibs(scp), ","));
            Env.saveProp(prop, propFile);
        }
    }

    public static void writeEclipseClassPath(String folder, String[] jars) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<classpath>\n");
        sb.append("    <classpathentry kind=\"src\" path=\"" + DIR_GEN + "\"/>\n");
        sb.append("    <classpathentry kind=\"src\" path=\"" + DIR_SRC + "\"/>\n");
        sb.append("    <classpathentry kind=\"output\" path=\"" + DIR_BIN + "\"/>\n");
        for (String j : jars) {
            // path="lib/xxx.jar"
            sb.append("    <classpathentry kind=\"lib\" path=\"").append(j).append("\"/>\n");
        }
        sb.append("</classpath>\n");
        FileUtil.writeTextFile(FileUtil.path(folder, ".classpath"), sb.toString());
    }

    public static void writeEclipseProject(String folder, String name) {
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<projectDescription>\n"
                + "    <name>" + name + "</name>\n"
                + "    <comment></comment>\n"
                + "    <projects>\n"
                + "    </projects>\n"
                + "    <buildSpec>\n"
                + "        <buildCommand>\n"
                + "            <name>org.eclipse.jdt.core.javabuilder</name>\n"
                + "            <arguments>\n"
                + "            </arguments>\n"
                + "        </buildCommand>\n"
                + "    </buildSpec>\n"
                + "    <natures>\n"
                + "        <nature>org.eclipse.jdt.core.javanature</nature>\n"
                + "    </natures>\n"
                + "</projectDescription>\n";
        FileUtil.writeTextFile(FileUtil.path(folder, ".project"), content);
    }

    public static Collection<File> organizeSourceByPackage(
            File srcDir, Collection<File> srcFiles, boolean copy) {
        ArrayList<File> moved = new ArrayList<>();
        for (File src : srcFiles) {
            String srcName = src.getName();
            if (!srcName.endsWith(".java") && !srcName.endsWith(".aidl")) {
                DLog.i("Not handle " + srcName);
                continue;
            }
            try (BufferedReader br = new BufferedReader(new FileReader(src))) {
                String line;
                boolean handled = false;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("package")) {
                        line = line.split(" ")[1];
                        String packageName = line.substring(0, line.length() - 1);
                        packageName = packageName.replace(".", "/");
                        File targetFolder = new File(srcDir, packageName);
                        FileUtil.mkdirs(targetFolder);
                        File dest = new File(targetFolder, srcName);
                        if (src.getAbsolutePath().equals(dest.getAbsolutePath())) {
                            DLog.i("Same location " + src);
                            moved.add(dest);
                            handled = true;
                            break;
                        }
                        if (copy) {
                            DLog.i("COPY " + src + " to " + dest);
                            FileUtil.copy(src, dest);
                        } else {
                            DLog.i("MOVE " + src + " to " + dest);
                            FileUtil.move(src, dest);
                        }
                        moved.add(dest);
                        handled = true;
                        break;
                    }
                }
                if (!handled) {
                    DLog.i("No package for " + srcName);
                }
            } catch (IOException ex) {
                DLog.ex(ex);
            }
        }
        return moved;
    }

    static void toPlainList(File f, Collection<File> files) {
        if (f.isDirectory()) {
            for (File subFile : FileUtil.listFiles(f)) {
                if (subFile.isDirectory()) {
                    toPlainList(subFile, files);
                } else {
                    files.add(subFile);
                }
            }
        } else {
            files.add(f);
        }
    }

    public boolean autoBuildFromDropFiles(File[] files, boolean build) {
        if (!isInitialized()) {
            initProject();
        }
        ArrayList<File> fileList = new ArrayList<>();
        for (File f : files) {
            toPlainList(f, fileList);
        }
        Collection<File> srcFiles = organizeSourceByPackage(
                new File(folder, Project.DIR_SRC), fileList, true);
        if (build) {
            buildSourceAndMakeJar(srcFiles);
        }
        return fileList.size() == srcFiles.size();
    }

    public void buildSourceAndMakeJar() {
        buildSourceAndMakeJar(null);
    }

    public void buildSourceAndMakeJar(Collection<File> srcFiles) {
        if (buildSrcWithAidl()) {
            if (buildDex()) {
                makeJar(srcFiles);
            } else {
                DLog.i("Build dex failed.");
            }
        } else {
            DLog.i("Build java failed.");
        }
    }
}

class AppProject {
    // TODO
    public AppProject(File apk) {
    }

    public void initProject() {
        // read apk, detect sign key
        // write build.properties [sign what key, name, version]
        // build.xml classpath.properties
        // write .classpath .project
        // dex2jar
        // /app/package-name
        //        |-gen
        //        |-src R.java
        //        |-bin
        //        |-self-lib
        //        |
    }
}

