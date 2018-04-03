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

import java.io.IOException;

import org.rh.ldt.DLog;
import org.rh.ldt.Env;
import org.rh.smaliex.AdbUtil;

import com.android.ddmlib.AdbHelper;
import com.android.ddmlib.AdbHelper.AdbCommandRejectedException;
import com.android.ddmlib.AdbHelper.TimeoutException;
import com.android.ddmlib.AdbHelperEx;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.Device;
import com.android.ddmlib.SyncService;
import com.android.ddmlib.SyncService.SyncException;

public class AdbUtilEx extends AdbUtil {

    public static AndroidDebugBridge startAdb(final AdbUtil.DeviceListener listener) {
        AdbHelperEx.enableDdmLog("warn");
        try {
            Runtime.getRuntime().exec("adb start-server");
            return AdbUtil.startAdb(listener);
        } catch (IOException ex) {
            DLog.i(ex + ", env has no adb, try tool folder");
        }
        String adbPath = FileUtil.path(Env.APT_PATH, "adb");
        return AdbUtil.startAdb(adbPath, listener);
    }

    public static void stopAdb() {
        AndroidDebugBridge.terminate();
    }

    public static void pullFile(Device device, String remote, String local) {
        try {
            device.pullFile(remote, local);
        } catch (IOException | AdbCommandRejectedException | TimeoutException | SyncException ex) {
            DLog.ex(ex);
        }
    }

    public static void pullFile(Device device, String remote, String local,
                                SyncProgressMonitor progress) {
        try (SyncService ss = device.getSyncService()) {
            if (ss != null) {
                ss.pullFile(remote, local, progress);
            }
        } catch (AdbCommandRejectedException | TimeoutException | IOException | SyncException ex) {
            DLog.ex(ex);
        }
    }

    public static void pushFile(Device device, String local, String remote) {
        DLog.i("Pushing " + local + " to " + remote + " @ " + device.getName());
        try (SyncService ss = device.getSyncService()) {
            if (ss != null) {
                ss.pushFile(local, remote, SyncService.getNullProgressMonitor());
            }
        } catch (AdbCommandRejectedException | TimeoutException | IOException | SyncException ex) {
            DLog.ex(ex);
        }
        DLog.i("Push done.");
    }

    public static boolean isFileExist(Device device, String filePath) {
        String result = AdbUtil.shellSync(device, "ls " + filePath,
                new String[]{""});
        return result.equals(filePath);
    }

    public static String getFileSize(Device device, String filePath) {
        String[] result = new String[]{"0"};
        AdbUtil.shellSync(device, "ls -l " + filePath, result);
        String[] segs = result[0].split("[\t ]+");
        //for (String k : segs) {
        //    System.out.println("h " + k);
        //}
        if (segs.length > 3) {
            result[0] = segs[3];
        }
        return result[0];
    }

    public static boolean isOnline(Device device) {
        int wait = 5;
        while (wait-- > 0 && device.isOffline()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
            }
        }
        return !(wait == 0 && device.isOffline());
    }

    public static void reboot(Device device, String into) {
        try {
            device.reboot(into);
        } catch (TimeoutException | AdbCommandRejectedException | IOException ex) {
            DLog.ex(ex);
        }
    }

    public static void rebootToBootloader(Device device) {
        reboot(device, "bootloader");
    }

    public static String root(Device device) {
        try {
            return AdbHelperEx.cmd(device, "root:");
        } catch (AdbHelper.TimeoutException |
                AdbHelper.AdbCommandRejectedException | IOException ex) {
            DLog.ex(ex);
        }
        return null;
    }

    public static void remount(Device device) {
        try {
            /*String ret = */
            AdbHelperEx.cmd(device, "remount:");
            //LLog.i(ret);
        } catch (AdbHelper.TimeoutException |
                AdbHelper.AdbCommandRejectedException | IOException ex) {
            DLog.ex(ex);
        }
    }

    public static boolean getBootImage(Device device, String into) {
        String mmcblk = null;
        for (String line : AdbUtil.shell(device, "cat /proc/emmc").split("\n")) {
            if (line.contains("\"boot\"")) {
                int pos = line.indexOf(':');
                if (pos > 0) {
                    DLog.i("Boot found: " + line);
                    mmcblk = line.substring(0, pos);
                    break;
                }
            }
        }
        if (mmcblk == null) {
            for (String line : AdbUtil.shell(
                    device, "ls -l /dev/block/platform/*/*/by-name").split("\n")) {
                if (line.contains(" boot -> ")) {
                    int pos = line.lastIndexOf('/');
                    if (pos >= 0) {
                        mmcblk = line.substring(pos + 1);
                    }
                    break;
                }
            }
        }
        if (mmcblk == null) {
            DLog.i("Cannot find boot mmcblk");
            return false;
        }
        String o = AdbUtil.shell(device, "dd if=/dev/block/" + mmcblk
                + " of=/sdcard/boot.img bs=4096");
        DLog.i("Copying to /sdcard/: " + o);
        pullFile(device, "/sdcard/boot.img", into);
        DLog.i("Saved to " + into);
        return true;
    }

    public static class SyncProgressMonitor implements SyncService.ISyncProgressMonitor {

        @Override
        public void start(int totalWork) {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isCanceled() {
            return false;
        }

        @Override
        public void startSubTask(String name) {
        }

        @Override
        public void advance(int work) {
        }
    }
}
