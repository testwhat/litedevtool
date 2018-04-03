package com.android.ddmlib;

import static com.android.ddmlib.AdbHelper.formAdbRequest;
import static com.android.ddmlib.AdbHelper.readAdbResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class AdbHelperEx {

    public static String cmd(Device device, String cmd) throws AdbHelper.TimeoutException,
            AdbHelper.AdbCommandRejectedException, IOException {
        byte[] request = formAdbRequest(cmd);
        try (SocketChannel adbChan = SocketChannel.open(AndroidDebugBridge.getSocketAddress())) {
            adbChan.configureBlocking(false);
            AdbHelper.setDevice(adbChan, device);
            AdbHelper.write(adbChan, request);
            AdbHelper.AdbResponse resp = readAdbResponse(adbChan, false);
            if (!resp.okay) {
                Log.w("cmd", cmd + " fail: " + resp.message);
                return resp.message;
            } else {
                StringBuilder sb = new StringBuilder(128);
                byte[] data = new byte[128];
                ByteBuffer buf = ByteBuffer.wrap(data);
                int read;
                while ((read = adbChan.read(buf)) > 0) {
                    sb.append(new String(data, 0, read));
                }
                return sb.toString().trim();
            }
        }
    }

    public static void enableDdmLog(String level) {
        DdmPreferences.setLogLevel(level);
    }
}
