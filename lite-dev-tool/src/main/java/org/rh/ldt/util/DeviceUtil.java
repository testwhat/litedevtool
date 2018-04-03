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

package org.rh.ldt.util;

import com.android.ddmlib.Device;
import com.android.systrace.SystraceOutputParser;
import com.android.systrace.SystraceTask;

import org.rh.ldt.DLog;
import org.rh.ldt.Env;
import org.rh.smaliex.MiscUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class DeviceUtil {

    private final static String ANDROID_IMAGE_KITCHEN_FOLDER = "aik";
    private final static String AIK_BASE_PATH =  FileUtil.path(Env.TOOL_PATH,
            ANDROID_IMAGE_KITCHEN_FOLDER, (Env.IS_WINDOWS ? "win" : "linux"));
    private final static String EXT = Env.IS_WINDOWS ? ".bat" : ".sh";

    final static String AIK_unpackimg = FileUtil.path(AIK_BASE_PATH, "unpackimg" + EXT);
    final static String AIK_repackimg = FileUtil.path(AIK_BASE_PATH, "repackimg" + EXT);
    final static String AIK_cleanup = FileUtil.path(AIK_BASE_PATH, "cleanup" + EXT);
    final static String FASTBOOT = FileUtil.path(Env.APT_PATH,
            "fastboot" + (Env.IS_WINDOWS ? ".exe" : ""));

    public static void changeClassPath(Device device, String bootCp, String serverCp) {
        String img = getBootImage(device, null);
        if (img != null) {
            String newImg = changeClassPath(img, bootCp, serverCp);
            if (newImg != null) {
                flashBootImage(device, newImg);
            } else {
                DLog.i("Make new image failed");
            }
        } else {
            DLog.i("Cannot get boot image from device " + device);
        }
    }

    public static String getBootImage(Device device, String outPath) {
        String img = outPath != null ? outPath : FileUtil.path(Env.MY_DIR, "boot.img");
        return AdbUtilEx.getBootImage(device, img) ? img : null;
    }

    public static void flashBootImage(Device device, String imagePath) {
        AdbUtilEx.rebootToBootloader(device);
        DLog.i("Entering bootloader...");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
        }
        Env.exec(FASTBOOT + " flash boot " + imagePath);
        Env.exec(FASTBOOT + " reboot ");
    }

    public static void unpackBootImage(String bootImg) {
        Env.exec(AIK_cleanup);
        Env.exec(AIK_unpackimg + " " + bootImg);
    }

    public static String changeClassPath(String bootImg, String bootCp, String serverCp) {
        unpackBootImage(bootImg);

        File environ = new File(AIK_BASE_PATH, "ramdisk" + File.separator + "init.environ.rc");
        final String exportBcp = "    export BOOTCLASSPATH ";
        final String exportScp = "    export SYSTEMSERVERCLASSPATH ";
        ArrayList<String> content = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(environ)))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(exportBcp) && bootCp != null) {
                    line = exportBcp + bootCp;
                } else if (line.startsWith(exportScp) && serverCp != null) {
                    line = exportScp + serverCp;
                }
                content.add(line);
            }
        } catch (IOException ex) {
            DLog.ex(ex);
            return null;
        }

        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(environ)))) {
            for (String line : content) {
                w.append(line);
                w.append("\n");
            }
        } catch (IOException ex) {
            DLog.ex(ex);
            return null;
        }
        Env.exec(AIK_repackimg);
        String newImg = AIK_BASE_PATH + File.separator + "image-new.img";
        DLog.i("Output: " + newImg);
        return newImg;
    }

    public static void getSystrace(Device device) {
        System.out.println(device.getName());
        List<SystraceTask.SystraceTag> supportedTags = SystraceTask.getTags(device);
        HashSet<String> selTags = new HashSet<>();
        for (SystraceTask.SystraceTag t : supportedTags) {
            selTags.add(t.tag);
        }

        SystraceTask.SystraceOptions so = new SystraceTask.SystraceOptions("system_server", 5);
        String opt = so.getOptions(supportedTags, selTags);
        System.out.println(opt);

        SystraceTask task = new SystraceTask(device, opt);
        task.run();
        File sysAssetPath = new File(Env.TOOL_PATH, "systrace");
        SystraceOutputParser parser = new SystraceOutputParser(sysAssetPath);
        parser.parse(task.getAtraceOutput());
        String html = parser.getSystraceHtml();
        FileUtil.writeTextFile(new File("out_trace.html"), html);
    }

    public static File convertSystrace(String input) {
        File sysAssetPath = new File(Env.TOOL_PATH, "systrace");
        File inputFile = new File(input);
        File output = MiscUtil.changeExt(inputFile, "html");
        SystraceOutputParser sop = new SystraceOutputParser(sysAssetPath);
        try {
            sop.parse(Files.readAllBytes(inputFile.toPath()));
        } catch (IOException ex) {
            DLog.ex(ex);
        }
        FileUtil.writeTextFile(output, sop.getSystraceHtml());
        return output;
    }
}
