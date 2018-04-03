/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.Device;
import com.android.ddmlib.IShellOutputReceiver;

public class SystraceTask implements Runnable {
    private final Device mDevice;
    private final String mOptions;

    private volatile boolean mCancel;

    private final Object mLock = new Object();
    private String errorMessage;
    private boolean mTraceComplete;
    private byte[] mBuffer = new byte[1024];
    private int mDataLength = 0;

    public SystraceTask(Device device, String options) {
        mDevice = device;
        mOptions = options;
    }

    @Override
    public void run() {
        try {
            mDevice.executeShellCommand("atrace " + mOptions, new Receiver(), 0, TimeUnit.SECONDS);
        } catch (Exception e) {
            synchronized (mLock) {
                errorMessage = "Unexpected error while running atrace on device: " + e;
            }
        }
    }

    public void cancel() {
        mCancel = true;
    }

    public String getError() {
        synchronized (mLock) {
            return errorMessage;
        }
    }

    public byte[] getAtraceOutput() {
        synchronized (mLock) {
            return mTraceComplete ? mBuffer : null;
        }
    }

    private class Receiver implements IShellOutputReceiver {
        @Override
        public void addOutput(byte[] data, int offset, int length) {
            synchronized (mLock) {
                if (mDataLength + length > mBuffer.length) {
                    mBuffer = ensureCapacity(mBuffer, mDataLength + length + 1, 1024);
                }

                System.arraycopy(data, offset, mBuffer, mDataLength, length);
                mDataLength += length;
            }
        }

        @Override
        public void flush() {
            synchronized (mLock) {
                // trim mBuffer to its final size
                byte[] copy = new byte[mDataLength];
                System.arraycopy(mBuffer, 0, copy, 0, mDataLength);
                mBuffer = copy;

                mTraceComplete = true;
            }
        }

        @Override
        public boolean isCancelled() {
            return mCancel;
        }
    }

    public static byte[] ensureCapacity(byte[] array, int minLength, int padding) {
        return (array.length < minLength) ? copyOf(array, minLength + padding) : array;
    }

    private static byte[] copyOf(byte[] original, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(original, 0, copy, 0, Math.min(original.length, length));
        return copy;
    }

    public static List<SystraceTag> getTags(Device device) {
        TagGetter tagGetter = new TagGetter(device);
        tagGetter.run();
        return tagGetter.getTags();
    }

    public static class SystraceOptions {

        String mTraceApp;
        int mTraceDuration;
        int mTraceBufferSize;
        boolean mCompress;

        public SystraceOptions(String processName, int duration, int bufferSize, boolean compress) {
            mTraceApp = processName;
            mTraceDuration = duration;
            mTraceBufferSize = bufferSize;
            mCompress = compress;
        }

        public SystraceOptions(String processName, int duration) {
            this(processName, duration, 20480, true);
        }

        public String getOptions(List<SystraceTag> supportedTags, Set<String> enabledTags) {
            StringBuilder sb = new StringBuilder(5 * supportedTags.size());

            if (mCompress) {
                sb.append("-z ");
            }

            if (mTraceApp != null) {
                sb.append("-a ");
                sb.append(mTraceApp);
                sb.append(' ');
            }

            if (mTraceDuration > 0) {
                sb.append("-t ");
                sb.append(mTraceDuration);
                sb.append(' ');
            }

            if (mTraceBufferSize > 0) {
                sb.append("-b ");
                sb.append(mTraceBufferSize);
                sb.append(' ');
            }

            for (String s : enabledTags) {
                sb.append(s);
                sb.append(' ');
            }

            return sb.toString().trim();
        }
    }

    public static class SystraceTag {
        public final String tag;
        public final String info;

        public SystraceTag(String tagName, String details) {
            tag = tagName;
            info = details;
        }
    }

    public static class TagGetter implements Runnable {

        private final Device mDevice;
        private List<SystraceTag> mTags;

        public TagGetter(Device device) {
            mDevice = device;
        }

        @Override
        public void run() {

            CountDownLatch setTagLatch = new CountDownLatch(1);
            CollectingOutputReceiver receiver = new CollectingOutputReceiver(setTagLatch);
            try {
                String cmd = "atrace --list_categories";
                mDevice.executeShellCommand(cmd, receiver, 5, TimeUnit.SECONDS);
                setTagLatch.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                e.printStackTrace();
            }

            String shellOutput = receiver.getOutput();
            mTags = parseSupportedTags(shellOutput);
        }

        public List<SystraceTag> getTags() {
            return mTags;
        }

        private static List<SystraceTag> parseSupportedTags(String listCategoriesOutput) {
            if (listCategoriesOutput == null) {
                return null;
            }

            if (listCategoriesOutput.contains("unknown option")) {
                return null;
            }

            String[] categories = listCategoriesOutput.split("\n");
            List<SystraceTag> tags = new ArrayList<>(categories.length);

            Pattern p = Pattern.compile("([^-]+) - (.*)"); //$NON-NLS-1$
            for (String category : categories) {
                Matcher m = p.matcher(category);
                if (m.find()) {
                    tags.add(new SystraceTag(m.group(1).trim(), m.group(2).trim()));
                }
            }

            return tags;
        }

    }
}
