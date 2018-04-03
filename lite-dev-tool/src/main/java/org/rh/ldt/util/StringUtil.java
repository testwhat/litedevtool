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

import org.rh.ldt.DLog;

import javax.activation.DataHandler;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import javax.swing.text.rtf.RTFEditorKit;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Set;

public class StringUtil {

    public static String getOnlyFilename(String filename) {
        return getOnlyFilename(filename, File.separatorChar);
    }

    public static String getOnlyFilename(String filename, char sep) {
        int lastSep = filename.lastIndexOf(sep);
        if (lastSep < 0) {
            return filename;
        }
        return filename.substring(lastSep + 1);
    }

    public static String appendTail(String targetFile, String tail) {
        String name = getOnlyFilename(targetFile);
        name = FileUtil.getFilenameNoExt(name)
                + tail + "." + FileUtil.getFilenameExt(name);
        return FileUtil.path(getFileDirPath(targetFile), name);
    }

    public static String getFileDirPath(String path) {
        return path.substring(0, path.lastIndexOf(java.io.File.separatorChar) + 1);
    }

    public static void copyToClipboard(String str) {
        StringSelection stringSelection = new StringSelection(str);
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(stringSelection, null);
    }

    public static void copyToClipboardWithStyle(JTextComponent text) {
        try {
            Clipboard clpbrd = Toolkit.getDefaultToolkit().getSystemClipboard();
            RTFEditorKit rtfek = new RTFEditorKit();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            rtfek.write(baos, text.getDocument(),
                    text.getSelectionStart(), text.getSelectionEnd());
            baos.flush();
            DataHandler dh = new DataHandler(baos.toByteArray(), rtfek.getContentType());
            clpbrd.setContents(dh, null);
        } catch (IOException | BadLocationException ex) {
            DLog.ex(ex);
        }
    }

    public static int toInt(String str) {
        int p = 0;
        if (str != null) {
            int len = str.length();
            char[] sb = new char[len];
            for (int i = 0; i < len; i++) {
                char c = str.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-') {
                    sb[p++] = c;
                }
            }
            if (p > 0) {
                return Integer.parseInt(new String(sb, 0, p));
            }
        }
        return p;
    }

    public static String join(final String[] strs, final String delimiter) {
        if (strs == null || strs.length == 0) {
            return "";
        }
        if (strs.length < 2) {
            return strs[0];
        }
        StringBuilder buffer = new StringBuilder(strs[0]);
        for (int i = 1; i < strs.length; i++) {
            buffer.append(delimiter).append(strs[i]);
        }
        return buffer.toString();
    }

    public static final class ContainStringChecker {
        final AhoCorasick mAc;

        public static ContainStringChecker create(Set<String> data) {
            return data == null || data.isEmpty() ? null
                    : new ContainStringChecker(data);
        }

        public ContainStringChecker(Set<String> data) {
            mAc = new AhoCorasick(data.toArray(new String[data.size()]));
        }

        public boolean contains(String text) {
            return mAc.contains(text);
        }
    }

    public static final class AhoCorasick {
        final static int FIRST_VISIBLE_ASCII = 32;
        final static int LAST_VISIBLE_ASCII = 126;
        final static int TRIE_SIZE = LAST_VISIBLE_ASCII - FIRST_VISIBLE_ASCII + 1;

        private final static int TRIE_ROOT = 1;
        private final static int TRIE_PATTERN = 2;

        static class Trie {

            Trie[] mNext = new Trie[TRIE_SIZE];
            Trie mFailLink;
            IntArray mPatternIdx = new IntArray(1);
            int mVal;
        }

        Trie mRoot;
        String[] mPatterns;

        public AhoCorasick(String[] patterns) {
            rebuild(patterns);
        }

        public void rebuild(String[] patterns) {
            mRoot = new Trie();
            mRoot.mVal = TRIE_ROOT;
            mPatterns = patterns;

            for (int i = 0; i < patterns.length; i++) {
                Trie r = mRoot, t;
                String p = patterns[i];
                for (int j = 0; j < p.length(); j++) {
                    int index = p.charAt(j) - FIRST_VISIBLE_ASCII;
                    t = r.mNext[index];
                    if (t == null) {
                        t = new Trie();
                    }
                    t.mVal = TRIE_PATTERN;
                    r.mNext[index] = t;
                    r = t;
                }
                r.mPatternIdx.add(i);
            }

            LinkedList<Trie> q = new LinkedList<>();
            for (int i = 0; i < TRIE_SIZE; i++) {
                if (mRoot.mNext[i] != null && mRoot.mNext[i].mVal > 0) {
                    mRoot.mNext[i].mFailLink = mRoot;
                    q.add(mRoot.mNext[i]);
                } else {
                    mRoot.mNext[i] = mRoot;
                }
            }

            Trie head;
            while ((head = q.poll()) != null) {
                for (int i = 0; i < TRIE_SIZE; i++) {
                    if (head.mNext[i] != null && (head.mNext[i].mVal > 0)) {
                        q.add(head.mNext[i]);
                        Trie f = head.mFailLink;

                        while ((f.mNext[i] != null && f.mNext[i].mVal == 0) || f.mNext[i] == null) {
                            f = f.mFailLink;
                        }

                        head.mNext[i].mFailLink = f.mNext[i];
                        if (f.mNext[i].mPatternIdx.size() > 0) {
                            for (int j = 0; j < f.mNext[i].mPatternIdx.size(); j++) {
                                head.mNext[i].mPatternIdx.add(f.mNext[i].mPatternIdx.get(j));
                            }
                        }
                    }
                }
            }
        }

        public boolean contains(String text) {
            return getMatchedPatternIndex(text, true) != null;
        }

        public int[] getMatchedPatternIndex(String text) {
            return getMatchedPatternIndex(text, false);
        }

        private static final int[] FOUND = new int[0];

        private int[] getMatchedPatternIndex(String text, boolean findAny) {
            IntArray res = null;
            Trie p = mRoot;
            for (int i = 0; i < text.length(); i++) {
                int c = text.charAt(i) - FIRST_VISIBLE_ASCII;
                if (c >= TRIE_SIZE) {
                    continue;
                }
                while ((p.mNext[c] != null && (p.mNext[c].mVal == 0)) || (p.mNext[c] == null)) {
                    p = p.mFailLink;
                }
                p = p.mNext[c];
                for (int j = 0; j < p.mPatternIdx.size(); j++) {
                    if (findAny) {
                        return FOUND;
                    }
                    if (res == null) {
                        res = new IntArray(mPatterns.length);
                    }
                    res.add(p.mPatternIdx.get(j));
                }
            }
            return res == null ? null : res.toArray();
        }
    }
}
