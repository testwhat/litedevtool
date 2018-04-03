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

import org.rh.ldt.util.FileUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.WeakHashMap;

import javax.annotation.Nonnull;
import javax.swing.text.JTextComponent;

public class Env {

    public static final String APP_NAME = "ldt";
    public static final boolean IS_WINDOWS = System.getProperty("os.name", "").contains("Windows");
    public static final boolean NEW_PROCESS = System.getProperty(APP_NAME + ".new_process", "1").equals("1");
    public static final boolean VERBOSE = System.getProperty(APP_NAME + ".verbose", "1").equals("1");
    public static final boolean NO_ADB = System.getProperty(APP_NAME + ".no_adb", "0").equals("1");
    public static final String MY_DIR = System.getProperty("user.dir");
    public static final String CLASSPATH = System.getProperty("java.class.path");
    public static final String JAVA_HOME = System.getProperty("java.home");
    public static final String MY_CLASSPATH = !CLASSPATH.contains(File.separator) ?
            MY_DIR + File.separator + CLASSPATH : CLASSPATH;
    //public static final String PROP_WIN_X = "window-pos-x";
    //public static final String PROP_WIN_Y = "window-pos-y";
    public static final String PROP_WIN_W = "window-width";
    public static final String PROP_WIN_H = "window-height";
    public static final String PROP_TAB_INDEX = "tab-index";
    public static final String PROP_TOOL_PATH = "tool-path";
    public static final String PROP_WORKSPACE = "workspace";
    public static final String PROP_OVERWRITE_INIT_PROJECT = "overwrite-init-project";
    public static final String DEFAULT_TOOL_PATH =
            new File(Env.MY_DIR, "tools").getAbsolutePath();
    public static final String DEFAULT_WORKSPACE =
            new File(Env.MY_DIR, "workspace").getAbsolutePath();
    public static final String TOOL_PATH;
    public static final String APT_PATH; // android platform tool

    private static final WeakHashMap<String, JTextComponent> sAutoSaveFields = new WeakHashMap<>();
    private static final File sPropFile = new File(
            MY_DIR, APP_NAME + ".properties");
    private static final Properties sProps = new LinkedProperties();
    private static boolean sChanged;

    static {
        DLog.enableVerbose(VERBOSE);
        try {
            if (!sPropFile.exists() && sPropFile.createNewFile()) {
                set(PROP_OVERWRITE_INIT_PROJECT, "true");
                save();
            }
            try (FileInputStream fis = new FileInputStream(sPropFile)) {
                sProps.load(fis);
            }
        } catch (IOException e) {
            DLog.ex(e);
        }
        TOOL_PATH = getToolPath();
        APT_PATH = FileUtil.path(TOOL_PATH, "apt");

        //java.util.prefs.Preferences root = java.util.prefs.Preferences.userRoot();
        //final java.util.prefs.Preferences node = root.node("/dext");
    }

    public static class LinkedProperties extends Properties {

        private final LinkedHashSet<Object> keys = new LinkedHashSet<>();

        @Override
        public Enumeration<Object> keys() {
            return Collections.enumeration(keys);
        }

        @Override
        public Object put(Object key, Object value) {
            keys.add(key);
            return super.put(key, String.valueOf(value));
        }
    }

    public static Properties newProp() {
        return new LinkedProperties();
    }

    public static Properties loadProp(File propFile) {
        Properties prop = new LinkedProperties();
        try (FileReader fr = new FileReader(propFile)) {
            prop.load(fr);
        } catch (IOException e) {
            DLog.ex(e);
        }
        return prop;
    }

    public static void saveProp(Properties prop, File propFile) {
        try (FileOutputStream fis = new FileOutputStream(propFile)) {
            prop.store(fis, null);
        } catch (IOException ex) {
            DLog.ex(ex);
        }
    }

    public synchronized static void set(@Nonnull String key, @Nonnull String value) {
        if (value.equals(get(key))) {
            return;
        }
        sProps.setProperty(key, value);
        sChanged = true;
    }

    public static void set(@Nonnull String key, int value) {
        if (value == getInt(key, 0)) {
            return;
        }
        set(key, String.valueOf(value));
    }

    public synchronized static String get(@Nonnull String key) {
        return sProps.getProperty(key);
    }

    public synchronized static String get(@Nonnull String key, String defaultValue) {
        return sProps.getProperty(key, defaultValue);
    }

    public static int getInt(@Nonnull String key, int defaultValue) {
        try {
            String val = get(key);
            if (val != null) {
                return Integer.valueOf(val);
            }
        } catch (Exception e) {
            DLog.e(e.getMessage());
        }
        return defaultValue;
    }

    public synchronized static void save() {
        sAutoSaveFields.keySet().stream().filter(java.util.Objects::nonNull).forEach(key -> {
            JTextComponent comp = sAutoSaveFields.get(key);
            if (comp != null) {
                set(key, comp.getText());
            }
        });

        if (sChanged) {
            try {
                sProps.store(new java.io.FileOutputStream(sPropFile), null);
            } catch (IOException ex) {
                DLog.ex(ex);
            }
        }
    }

    public synchronized static void addAutoSave(String key, JTextComponent comp) {
        sAutoSaveFields.put(key, comp);
    }

    public static String getToolPath() {
        return get(PROP_TOOL_PATH, DEFAULT_TOOL_PATH);
    }

    public static boolean alwaysOverwriteWhenInitProject() {
        return get(PROP_OVERWRITE_INIT_PROJECT, "true").equals("true");
    }

    public static String getWorkspace() {
        return get(Env.PROP_WORKSPACE, DEFAULT_WORKSPACE);
    }

    public static void openPcFileBrowser(String path, boolean select) {
        try {
            String command;
            if (Env.IS_WINDOWS) {
                command = "explorer.exe " + (select ? "/select, " : "") + path;
            } else {
                //xdg-open
                command = "gnome-open " + path;
            }
            Runtime.getRuntime().exec(command);
        } catch (Exception e1) {
            DLog.ex(e1);
        }
    }

    public interface MessagePrinter {
        void println(String str);
    }

    public final static MessagePrinter STDOUT_PRINTER = str -> System.out.println(str);

    public static int exec(String cmd) {
        return exec(cmd, STDOUT_PRINTER);
    }

    public static int exec(String cmd, MessagePrinter printer) {
        DLog.v("exec: " + cmd);
        StringTokenizer st = new StringTokenizer(cmd);
        String[] cmdArray = new String[st.countTokens()];
        for (int i = 0; st.hasMoreTokens(); i++) {
            cmdArray[i] = st.nextToken();
        }
        ProcessBuilder processBuilder = new ProcessBuilder(cmdArray);
        processBuilder.redirectErrorStream(true);
        Process process = null;
        try {
            process = processBuilder.start();
            if (printer != null) {
                readStream(process.getInputStream(), printer);
            } else {
                try {
                    process.waitFor();
                } catch (InterruptedException ex) {
                    DLog.ex(ex);
                }
            }
        } catch (IOException e) {
            DLog.ex(e);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return exitValue(process);
    }

    public static int execVm(String clsName, String userDir, MessagePrinter printer, String... args) {
        ArrayList<String> commands = new ArrayList<>();
        commands.add(JAVA_HOME + File.separator + "bin" + File.separator + "java");
        if (userDir != null) {
            commands.add("-Duser.dir=" + userDir);
        }
        commands.add("-Xmx1024m");
        commands.add("-cp");
        commands.add(MY_CLASSPATH);
        commands.add(clsName);
        if (args != null && args.length > 0) {
            Collections.addAll(commands, args);
        }
        DLog.v("execVm: " + commands);

        ProcessBuilder processBuilder = new ProcessBuilder(commands);
        processBuilder.redirectErrorStream(true);
        Process process = null;
        try {
            process = processBuilder.start();
            if (printer != null) {
                readStream(process.getInputStream(), printer);
            } else {
                process.waitFor();
            }
        } catch (InterruptedException | IOException e) {
            DLog.ex(e);
        }
        return exitValue(process);
    }

    public static int execVm(Class<?> cls, String userDir, String... args) {
        return execVm(cls.getCanonicalName(), userDir, STDOUT_PRINTER, args);
    }

    private static void readStream(InputStream stream, MessagePrinter printer)
            throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream/*, "utf-8"*/))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() > 0) {
                    printer.println(line);
                    //System.out.println("### " + line);
                }
            }
        }
    }

    private static int exitValue(Process process) {
        if (process != null) {
            final long timeout = 5000;
            long startTime = System.currentTimeMillis();
            long remain;
            do {
                try {
                    return process.exitValue();
                } catch (IllegalThreadStateException ignoredEx) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                    }
                }
                remain = timeout - (System.currentTimeMillis() - startTime);
            } while (remain > 0);
        }
        return -1;
    }
}
