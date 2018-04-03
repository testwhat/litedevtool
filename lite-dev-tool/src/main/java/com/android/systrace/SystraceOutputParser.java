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

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

// sdk/eclipse/plugins/com.android.ide.eclipse.ddms/src/com/android/ide/eclipse/ddms/views/

/** {@link SystraceOutputParser} receives the output of atrace command run on the device,
 * parses it and generates html based on the trace */
public class SystraceOutputParser {
    private static final String TRACE_START = "TRACE:\n";
    private static final String TRACE_TEXT_HEADER = "# tracer";
    final static Charset UTF_8 = Charset.forName("UTF-8");

    private boolean mUncompress;
    private final String mSystraceHtml;
    private final String mHtmlPrefix;
    private final String mHtmlSuffix;

    private byte[] mAtraceOutput;
    private int mAtraceLength;
    private int mSystraceIndex = -1;

    /**
     * Constructs a atrace output parser.
     * @param compressedStream Is the input stream compressed using zlib?
     * @param systraceHtml contents of systrace_trace_viewer.html
     * @param htmlPrefix contents of prefix.html
     * @param htmlSuffix contents of suffix.html
     */
    public SystraceOutputParser(boolean compressedStream, String systraceHtml, String htmlPrefix,
            String htmlSuffix) {
        mUncompress = compressedStream;
        mSystraceHtml = systraceHtml;
        mHtmlPrefix = htmlPrefix;
        mHtmlSuffix = htmlSuffix;
    }

    public SystraceOutputParser(File assetsFolder) {
        this(false, getSystraceHtml(assetsFolder),
                getHtmlPrefix(assetsFolder), getHtmlSuffix(assetsFolder));
    }

    /**
     * Parses the atrace output for systrace content.
     * @param atraceOutput output bytes from atrace
     */
    public void parse(byte[] atraceOutput) {
        mAtraceOutput = atraceOutput;
        mAtraceLength = atraceOutput.length;

        removeCrLf();

        // locate the trace start marker within the first hundred bytes
        String header = new String(mAtraceOutput, 0, Math.min(100, mAtraceLength));
        if (!header.contains(TRACE_TEXT_HEADER)) {
            mUncompress = true;
        }
        mSystraceIndex = locateSystraceData(header);

        if (mSystraceIndex < 0) {
            throw new RuntimeException("Unable to find trace start marker 'TRACE:':\n" + header);
        }
    }

    /** Replaces \r\n with \n in {@link #mAtraceOutput}. */
    private void removeCrLf() {
        int dst = 0;
        for (int src = 0; src < mAtraceLength - 1; src++, dst++) {
            byte copy;
            if (mAtraceOutput[src] == '\r' && mAtraceOutput[src + 1] == '\n') {
                copy = '\n';
                src++;
            } else {
                copy = mAtraceOutput[src];
            }
            mAtraceOutput[dst] = copy;
        }

        mAtraceLength = dst;
    }

    private static int locateSystraceData(String header) {
        int index = header.indexOf(TRACE_START);
        if (index < 0) {
            return -1;
        } else {
            return index + TRACE_START.length();
        }
    }

    public String getSystraceHtml() {
        if (mSystraceIndex < 0) {
            return "";
        }

        String trace;
        if (mUncompress) {
            Inflater decompressor = new Inflater();
            decompressor.setInput(mAtraceOutput, mSystraceIndex, mAtraceLength - mSystraceIndex);

            byte[] buf = new byte[4096];
            int n;
            StringBuilder sb = new StringBuilder(1000);
            try {
                while ((n = decompressor.inflate(buf)) > 0) {
                    sb.append(new String(buf, 0, n));
                }
            } catch (DataFormatException e) {
                throw new RuntimeException(e);
            }
            decompressor.end();

            trace = sb.toString();
        } else {
            trace = new String(mAtraceOutput, mSystraceIndex, mAtraceLength - mSystraceIndex);
        }

        return mHtmlPrefix.replace("{{SYSTRACE_TRACE_VIEWER_HTML}}", mSystraceHtml)
                + "<!-- BEGIN TRACE -->\n"
                + "  <script class=\"trace-data\" type=\"application/text\">\n"
                + trace
                + "  </script>\n<!-- END TRACE -->\n" + mHtmlSuffix;
    }

    static String toString(File file, Charset charset) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), charset);
    }

    public static String getSystraceHtml(File assetsFolder) {
        return getHtmlTemplate(assetsFolder, "systrace_trace_viewer.html");
    }

    public static String getHtmlPrefix(File assetsFolder) {
        return getHtmlTemplate(assetsFolder, "prefix.html");
    }

    public static String getHtmlSuffix(File assetsFolder) {
        return getHtmlTemplate(assetsFolder, "suffix.html");
    }

    private static String getHtmlTemplate(File assetsFolder, String htmlFileName) {
        try {
            return toString(new File(assetsFolder, htmlFileName), UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
