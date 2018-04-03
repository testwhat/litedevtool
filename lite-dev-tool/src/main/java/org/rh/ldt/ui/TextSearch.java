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

package org.rh.ldt.ui;

import org.rh.ldt.util.IntArray;

import java.awt.Color;
import java.awt.Cursor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.Highlighter;
import javax.swing.text.Highlighter.HighlightPainter;
import javax.swing.text.JTextComponent;
import javax.swing.text.Segment;

public class TextSearch {
    private static Cursor sWaitCursor;
    private static ExecutorService sEs;

    private final JTextComponent mTextArea;
    private final ColorHighlightPainter mSearchPainter;
    private final HashMap<HighlightPainter, ArrayList<Object>> mHighlightTags = new HashMap<>();
    private String mLastSearchText;
    private ResultIndexes mLastResult;

    public final static int[] Highlights = {
        0x9BFFFF, 0xFFCD9B, 0xCD9BFF, 0x9BCD9B, 0xE1909B, 0x7DC2FF, 0xE1C29B};

    public TextSearch(JTextComponent content) {
        mTextArea = content;
        mSearchPainter = new ColorHighlightPainter(Color.YELLOW);
        if (sWaitCursor == null) {
            sWaitCursor = new Cursor(Cursor.WAIT_CURSOR);
            sEs = Executors.newSingleThreadExecutor();
        }
    }

    public void setColor(Color c) {
        mSearchPainter.hColor = c;
    }

    public static class ColorHighlightPainter extends DefaultHighlighter.DefaultHighlightPainter {
        private Color hColor;

        public ColorHighlightPainter(Color c) {
            super(null);
            hColor = c;
        }

        @Override
        public Color getColor() {
            return hColor;
        }
    }

    public void clearHighlighter() {
        mTextArea.getHighlighter().removeAllHighlights();
    }

    public boolean search(String target) {
        return search(target, mSearchPainter);
    }

    public boolean search(String target, ColorHighlightPainter highlightPainter) {
        if (target == null || target.equals(mLastSearchText)) {
            return false;
        }
        if (highlightPainter != null) {
            removeHighlight(highlightPainter);
        }
        mLastResult = highlightText(target, highlightPainter);
        mLastSearchText = target;
        return true;
    }

    public String getLastSearchText() {
        return mLastSearchText;
    }

    public IntArray getLastDataResult() {
        return mLastResult == null ? null : mLastResult.dataOffsets;
    }

    public IntArray getLastLineResult() {
        return mLastResult == null ? null : mLastResult.lineIndexes;
    }

    public ResultIndexes highlightText(String searchString) {
        return highlightText(new SearchParam(searchString), mSearchPainter);
    }

    public ResultIndexes highlightText(String searchString, HighlightPainter painter) {
        return highlightText(new SearchParam(searchString), painter);
    }

    public ResultIndexes highlightText(SearchParam sparam, HighlightPainter painter) {
        Cursor startCursor = mTextArea.getCursor();

        Highlighter highlighter = mTextArea.getHighlighter();
        Search search = new Search(mTextArea.getDocument(), sparam);

        mTextArea.setEditable(false);
        mTextArea.setCursor(sWaitCursor);

        ResultIndexes results = null;
        try {
            results = sEs.submit(search).get();
            final int s = results.dataOffsets.size();
            if (s > 0 && painter != null) {
                ArrayList<Object> ps = mHighlightTags.computeIfAbsent(
                        painter, k -> new ArrayList<>(64));
                for (int i = 0; i < s; i++) {
                    int start = results.dataOffsets.get(i);
                    ps.add(highlighter.addHighlight(start,
                            start + sparam.searchString.length(), painter));
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        mTextArea.setEditable(true);
        mTextArea.setCursor(startCursor);
        return results;
    }

    public void removeHighlight(HighlightPainter painter) {
        ArrayList<Object> ps = mHighlightTags.get(painter);
        if (ps != null) {
            mHighlightTags.remove(painter);
            for (Object p : ps) {
                mTextArea.getHighlighter().removeHighlight(p);
            }
        }
    }

    public static class SearchParam {
        String searchString;
        boolean ignoreCase = true;
        boolean fullString;// = true;
        public SearchParam(String str) {
            searchString = str;
        }
    }

    public static class ResultIndexes {
        public final IntArray dataOffsets;
        public final IntArray lineIndexes;
        public ResultIndexes(IntArray d, IntArray l) {
            dataOffsets = d;
            lineIndexes = l;
        }
    }

    private static class Search implements Callable<ResultIndexes> {

        private final Document document;
        SearchParam param;

        public Search(Document d, SearchParam s) {
            document = d;
            param = s;
        }

        @Override
        public ResultIndexes call() throws Exception {
            return search();
        }

        private ResultIndexes search() {
            IntArray lineOffsets = new IntArray(64);
            IntArray lineIndexes = new IntArray(64);
            IntArray dataOffsets = new IntArray(64);
            final Element element = document.getDefaultRootElement();
            final int elementCount = element.getElementCount();

            for (int i = 0; i < elementCount; i++) {
                lineOffsets.add(element.getElement(i).getStartOffset());
            }
            lineOffsets.add(element.getElement(element.getElementCount() - 1).getEndOffset());

            int count = 0;
            int lsOffset, leOffset;
            final int totalLine = lineOffsets.size() - 1;

            final boolean fullString = param.fullString;
            final String searchString = param.ignoreCase ?
                    param.searchString.toLowerCase() : param.searchString;
            final Segment seg = new Segment();
            while (count < totalLine) {
                lsOffset = lineOffsets.get(count);
                leOffset = lineOffsets.get(count + 1);

                try {
                    document.getText(lsOffset, leOffset - lsOffset, seg);
                } catch (BadLocationException e) {
                    e.printStackTrace();
                }

                String line = param.ignoreCase ? seg.toString().toLowerCase() : seg.toString();
                int mark = 0;//, lastAddedCount = -1;

                while ((mark = line.indexOf(searchString, mark)) > -1) {
                    if (fullString && mark > 0) {
                        if (!Character.isLetter(line.charAt(mark - 1)) &&
                                !Character.isLetter(line.charAt(mark + searchString.length()))) {
                            dataOffsets.add(lsOffset + mark);
                            //if (lastAddedCount != count) {
                                lineIndexes.add(count);
                            //}
                            //lastAddedCount = count;
                        }
                    } else {
                        dataOffsets.add(lsOffset + mark);
                        //if (lastAddedCount != count) {
                            lineIndexes.add(count);
                        //}
                        //lastAddedCount = count;
                    }
                    mark += searchString.length();
                }
                count++;
            }
            return new ResultIndexes(dataOffsets, lineIndexes);
        }
    }
}
