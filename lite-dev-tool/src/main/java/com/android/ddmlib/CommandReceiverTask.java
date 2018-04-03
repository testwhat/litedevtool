/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ddmlib;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.android.annotations.NonNull;

public class CommandReceiverTask implements Runnable {
    private static final int DEVICE_POLL_INTERVAL_MSEC = 2000;

    private final Device mDevice;
    private final LogCatOutputReceiver mReceiver;
    private final AtomicBoolean mCancelled;
    private final String mCommand;

    public CommandReceiverTask(@NonNull Device device, @NonNull String cmd,
            @NonNull Logger logger) {
        mDevice = device;
        mCancelled = new AtomicBoolean();
        mReceiver = new LogCatOutputReceiver(logger, mCancelled);
        mCommand = cmd;
    }

    @Override
    public void run() {
        // wait while device comes online
        while (!mDevice.isOnline()) {
            try {
                Thread.sleep(DEVICE_POLL_INTERVAL_MSEC);
            } catch (InterruptedException e) {
                return;
            }
        }

        try {
            mDevice.executeShellCommand(mCommand, mReceiver, 0, TimeUnit.SECONDS);
        } catch (AdbHelper.TimeoutException e) {
            mReceiver.onStop("Connection timed out");
        } catch (AdbHelper.AdbCommandRejectedException ignored) {
            // will not be thrown as long as the shell supports logcat
        } catch (AdbHelper.ShellCommandUnresponsiveException ignored) {
            // this will not be thrown since the last argument is 0
            mReceiver.onStop("Shell command unresponsive");
        } catch (IOException e) {
            mReceiver.onStop("Connection error");
        }
        mReceiver.onStop("Device disconnected: 1");
    }

    public void setCancelled(boolean cancelled) {
        mCancelled.set(cancelled);
    }

    public void stop() {
        setCancelled(true);
    }
    
    public interface Logger {
        void onLog(String[] logs);
        void onStop(String reason);
    }

    public static class LogCatOutputReceiver extends MultiLineReceiver {
        private final AtomicBoolean mCancelled;
        private final Logger mLogger;

        public LogCatOutputReceiver(Logger logger, AtomicBoolean cancel) {
            mLogger = logger;
            mCancelled = cancel;
            setTrimLine(false);
        }

        /** Implements {@link IShellOutputReceiver#isCancelled() }. */
        @Override
        public boolean isCancelled() {
            return mCancelled.get();
        }

        @Override
        public void processNewLines(String[] lines) {
            if (isCancelled()) {
                return;
            }
            mLogger.onLog(lines);
        }

        public void onStop(String reason) {
            mLogger.onStop(reason);
        }
    }
}
