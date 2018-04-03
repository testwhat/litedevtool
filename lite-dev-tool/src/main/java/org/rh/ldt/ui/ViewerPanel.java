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

import org.rh.ldt.DLog;
import org.rh.ldt.util.IntArray;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent.EventType;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.EditorKit;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.TextAction;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ViewerPanel extends JPanel {

    public static abstract class VDocumentFilter extends DocumentFilter {
        public abstract void setTextSource(JTextComponent textPane);
    }

    public interface ContentChangeListener {
        void onChange(boolean hasChanged);
    }

    private final static int DEFAULT_FONT_SIZE = 13;
    private final static ScheduledExecutorService sSchedExecSvc = Executors.newScheduledThreadPool(1);
    private final JTextComponent mTextPane;
    private final StyledUndoManager mUndoMgr;
    private AbstractDocument mDocument;
    private final JTextField mSearchTF;
    private final TextSearch mTextSearch;
    private final TextLineNumber mTln;
    private final JLabel mSearchStatus;
    private int mCurrentSearchResultIndex;
    private String mContentDesc;
    private ContentChangeListener mChangeListener;
    private Runnable mOnSaveAction;
    private int mChangeCount;

    public ViewerPanel(String text) {
        this(null, null, text);
    }

    public ViewerPanel(EditorKit editorKit, VDocumentFilter f, String text) {
        this(editorKit, f);
        setText(text);
    }

    public String getText() {
        return mTextPane.getText();
    }

    private void insertPartial(final int offset, final String text) {
        try {
            mDocument.insertString(offset, text, null);
        } catch (BadLocationException ex) {
            ex.printStackTrace();
        }
    }

    public void setText(final String str) {
        final String text = str == null ? "" : str;
        final int partialLoad = 20000;
        final int length = text.length();
        final int rounds = length / partialLoad;
        final long s = System.currentTimeMillis();
        if (rounds > 0 && (mTextPane instanceof JEditorPane)) {
            final ScheduledFuture[] ftask = new ScheduledFuture[1];
            ftask[0] = sSchedExecSvc.scheduleAtFixedRate(new Runnable() {
                int i = 0;
                @Override
                public void run() {
                    if (i < rounds) {
                        int offset = i * partialLoad;
                        String t = text.substring(offset, offset + partialLoad);
                        DLog.i("Syntax painting " + (offset + partialLoad) + " / " +length);
                        insertPartial(offset, t);
                        refreshLine();
                        i++;
                    } else {
                        int offset = rounds * partialLoad;
                        String t = text.substring(offset, offset + (length % partialLoad));
                        DLog.i("Syntax painting "
                                + (offset + (length % partialLoad)) + " / " +length);
                        insertPartial(offset, t);
                        refreshLine();
                        startUndoable();
                        ftask[0].cancel(false);
                        DLog.i("Complete in " + (System.currentTimeMillis() - s) + "ms");
                    }
                }

            }, 0, 30, TimeUnit.MILLISECONDS);

        } else {
            mTextPane.setText(text);
            //System.out.println("c " + (System.currentTimeMillis() - s));
            refreshLine();
            startUndoable();
        }
    }

    private void startUndoable() {
        if (mDocument.getUndoableEditListeners().length == 0) {
            SwingUtilities.invokeLater(() -> mDocument.addUndoableEditListener(evt -> {
                if (mChangeListener != null) {
                    UndoableEdit edit = evt.getEdit();
                    if (edit instanceof AbstractDocument.DefaultDocumentEvent) {
                        AbstractDocument.DefaultDocumentEvent event =
                                (AbstractDocument.DefaultDocumentEvent) edit;
                        if (event.getType() == EventType.INSERT
                                || event.getType() == EventType.REMOVE) {
                            mChangeCount++;
                            mChangeListener.onChange(true);
                        }
                    }
                }
                mUndoMgr.addEdit(evt.getEdit());
            }));
        }
    }

    public void setDocumentFilter(VDocumentFilter f) {
        mDocument.setDocumentFilter(f);
    }

    public void setContentChangeListenr(ContentChangeListener l) {
        mChangeListener = l;
    }

    void notifyChange() {
        if (mChangeListener != null) {
            mChangeListener.onChange(mChangeCount != 0);
        }
    }

    public ViewerPanel(EditorKit editorKit, VDocumentFilter filter) {
        super(new BorderLayout());
        if (editorKit != null) {
            mTextPane = new JEditorPane();
            ((JEditorPane) mTextPane).setEditorKit(editorKit);
        } else {
            mTextPane = new JTextArea();
        }

        mDocument = (AbstractDocument) mTextPane.getDocument();
        if (filter != null) {
            filter.setTextSource(mTextPane);
            setDocumentFilter(filter);
        }
        mUndoMgr = new StyledUndoManager();
        mTextPane.getActionMap().put("Undo", new AbstractAction("Undo") {
            @Override
            public void actionPerformed(ActionEvent evt) {
                if (mUndoMgr.tryUndo()) {
                    mChangeCount--;
                    notifyChange();
                }
            }
        });
        mTextPane.getInputMap().put(KeyStroke.getKeyStroke("control Z"), "Undo");
        mTextPane.getActionMap().put("Redo", new AbstractAction("Redo") {
            @Override
            public void actionPerformed(ActionEvent evt) {
                if (mUndoMgr.tryRedo()) {
                    mChangeCount++;
                    notifyChange();
                }
            }
        });
        mTextPane.getInputMap().put(KeyStroke.getKeyStroke("control Y"), "Redo");

        mTextPane.getActionMap().put("Save", new AbstractAction("Save") {
            @Override
            public void actionPerformed(ActionEvent evt) {
                if (mOnSaveAction != null && mChangeCount != 0) {
                    mOnSaveAction.run();
                    mChangeListener.onChange(false);
                    mChangeCount = 0;
                    notifyChange();
                }
            }
        });
        mTextPane.getInputMap().put(KeyStroke.getKeyStroke("control S"), "Save");

        JScrollPane scp = new JScrollPane(mTextPane);
        mTln = new TextLineNumber(mTextPane);
        scp.setRowHeaderView(mTln);
        mTextPane.setFont(new Font("Monospaced", Font.PLAIN, DEFAULT_FONT_SIZE));

        Box hb = Box.createHorizontalBox();
        hb.add(Box.createHorizontalStrut(10));
        hb.add(new JLabel("Search"));
        mSearchTF = new JTextField(10);
        mTextSearch = new TextSearch(mTextPane);
        mSearchTF.addActionListener(e -> performSearch());

        hb.add(mSearchTF);
        mSearchStatus = new JLabel("");
        mSearchStatus.setPreferredSize(new Dimension(60, 10));
        hb.add(mSearchStatus);

        JButton searchResultPrevBtn = new JButton("<");
        searchResultPrevBtn.setToolTipText("Next search result");
        hb.add(searchResultPrevBtn);

        JButton searchResultNextBtn = new JButton(">");
        hb.add(searchResultNextBtn);
        searchResultNextBtn.setToolTipText("Previous search result");

        JButton searchResultListBtn = new JButton("L");
        hb.add(searchResultListBtn);
        searchResultListBtn.setToolTipText("List all search results in new window");

        searchResultPrevBtn.addActionListener(e -> updateSearchFocus(-1));
        searchResultNextBtn.addActionListener(e -> updateSearchFocus(1));
        searchResultListBtn.addActionListener(e -> popSearchResultWindow());

        // Filter list, black list
        hb.add(Box.createHorizontalStrut(10));

        hb.add(new JLabel("Go #"));
        final JTextField gotoTF = new JTextField(4);
        gotoTF.addActionListener(e -> {
            String str = gotoTF.getText();
            int index = -1;
            try {
                index = Integer.parseInt(str);
            } catch (NumberFormatException ex) {
                ex.printStackTrace();
            }
            if (index < 0) {
                return;
            }
            gotoLine(index);
        });
        hb.add(gotoTF);
        hb.add(Box.createHorizontalStrut(10));

        final JPopupMenu optMenu = new JPopupMenu();
        final JMenu fontTypeMenu = new JMenu("Font type");
        final ButtonGroup fontTypeBg = new ButtonGroup();
        final JMenu fontSizeMenu = new JMenu("Font size");
        final ButtonGroup fontSizeBg = new ButtonGroup();
        optMenu.add(fontTypeMenu);
        optMenu.add(fontSizeMenu);
        JMenuItem jmi = new JMenuItem("Undo");
        jmi.setAction(mTextPane.getActionMap().get("Undo"));
        jmi.setAccelerator(KeyStroke.getKeyStroke("control Z"));
        optMenu.add(jmi);
        jmi = new JMenuItem("Redo");
        jmi.setAction(mTextPane.getActionMap().get("Redo"));
        jmi.setAccelerator(KeyStroke.getKeyStroke("control Y"));
        optMenu.add(jmi);
        jmi = new JMenuItem("Save");
        jmi.setAction(mTextPane.getActionMap().get("Save"));
        jmi.setAccelerator(KeyStroke.getKeyStroke("control S"));
        optMenu.add(jmi);

        ActionListener fsListener = event -> {
            String c = event.getActionCommand();
            Font f;
            if (Character.isDigit(c.charAt(0))) {
                f = new Font(mTextPane.getFont().getFontName(), mTextPane.getFont().getStyle(),
                        Integer.parseInt(c));
            } else {
                f = new Font(c, mTextPane.getFont().getStyle(), mTextPane.getFont().getSize());
            }
            mTextPane.setFont(f);
            mTln.updateFont(f);
        };
        String[] fonts = {"Monospaced", "Arial", "Courier New"};
        for (String f : fonts) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(f);
            item.addActionListener(fsListener);
            fontTypeBg.add(item);
            fontTypeMenu.add(item);
        }
        ((JRadioButtonMenuItem) fontTypeMenu.getMenuComponent(0)).setSelected(true);
        int sel = 0;
        for (int i = 12; i < 33; i++) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem("" + i);
            item.addActionListener(fsListener);
            fontSizeBg.add(item);
            fontSizeMenu.add(item);
            if (i == DEFAULT_FONT_SIZE) {
                sel = i - 12;
            }
        }
        ((JRadioButtonMenuItem) fontSizeMenu.getMenuComponent(sel)).setSelected(true);

        JButton optionsBtn = new JButton("Menu");
        optionsBtn.addActionListener(e -> optMenu.show((Component) e.getSource(), 3, 24));
        hb.add(optionsBtn);

        add(hb, BorderLayout.NORTH);
        add(scp, BorderLayout.CENTER);

        initContextMenu();
    }

    public static int[] getRowBeginEnd(final JTextComponent c, final int offs)
            throws BadLocationException {
        final Rectangle r = c.modelToView(offs);
        Rectangle p = r;
        final int[] startEnd = new int[2];
        int lastOffs = offs, begin = offs;
        final int y = p.y;
        while ((p != null) && (y == p.y)) {
            if (p.height != 0) {
                begin = lastOffs;
            }
            lastOffs -= 1;
            p = (lastOffs >= 0) ? c.modelToView(lastOffs) : null;
        }
        startEnd[0] = begin;

        p = r;
        final int n = c.getDocument().getLength();
        int end = offs;
        lastOffs = offs;
        while ((p != null) && (y == p.y)) {
            if (p.height != 0) {
                end = lastOffs;
            }
            lastOffs += 1;
            p = (lastOffs <= n) ? c.modelToView(lastOffs) : null;
        }
        startEnd[1] = end;
        return startEnd;
    }

    private void performSearch() {
        if (mSearchTF.getText() == null || mSearchTF.getText().length() < 1) {
            return;
        }
        mTextSearch.search(mSearchTF.getText());
        IntArray res = mTextSearch.getLastDataResult();
        mCurrentSearchResultIndex = 0;
        if (res == null || res.size() == 0) {
            mSearchStatus.setText("(0/0)");
            return;
        }
        updateSearchFocus(0);
    }

    private void updateSearchFocus(int offset) {
        if (!checkMaySearch()) {
            return;
        }
        IntArray indexes = mTextSearch.getLastDataResult();
        if (mCurrentSearchResultIndex + offset < 0
                || mCurrentSearchResultIndex + offset >= indexes.size()) {
            return;
        }
        mCurrentSearchResultIndex += offset;
        mSearchStatus.setText("(" + (mCurrentSearchResultIndex + 1) + "/" + indexes.size() + ")");
        mTextPane.setCaretPosition(indexes.get(mCurrentSearchResultIndex));
        mTextPane.requestFocusInWindow();
    }

    private void popSearchResultWindow() {
        if (!checkMaySearch()) {
            return;
        }
        ViewerPanel vp = new ViewerPanel(null);
        StringBuilder sb = new StringBuilder(256);
        IntArray lines = mTextSearch.getLastLineResult();
        IntArray indexes = mTextSearch.getLastDataResult();
        //System.out.println(lines.size() + " " + indexes.size());
        for (int i = 0; i < indexes.size(); i++) {
            try {
                int[] rowBeginEnd = getRowBeginEnd(mTextPane, indexes.get(i));
                sb.append("#").append((lines.get(i) + 1)).append(": ");
                sb.append(mTextPane.getDocument().getText(
                        rowBeginEnd[0], rowBeginEnd[1] - rowBeginEnd[0]));
                sb.append("\n");
            } catch (BadLocationException badLocEx) {
                badLocEx.printStackTrace();
            }
        }
        vp.mTextPane.setText(sb.toString());
        vp.refreshLine();

        vp.mTextPane.setCaretPosition(0);
        vp.mTextSearch.highlightText(mSearchTF.getText());
        final JFrame frame = new JFrame(
                (mContentDesc != null ? (mContentDesc + " ") : "") + mSearchTF.getText());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.add(vp);
        Point pt = new Point(getLocation());
        SwingUtilities.convertPointToScreen(pt, this);
        frame.setBounds(pt.x, pt.y, 900, 600);
        frame.setVisible(true);
    }

    private boolean checkMaySearch() {
        IntArray indexes = mTextSearch.getLastLineResult();
        if (indexes == null || (!mTextSearch.getLastSearchText().equals(mSearchTF.getText()))) {
            performSearch();
            indexes = mTextSearch.getLastLineResult();
        }
        return indexes != null && indexes.size() > 0;
    }

    public void gotoLine(int lineNum) {
        Element rootE = mTextPane.getDocument().getDefaultRootElement();
        lineNum--;
        if (lineNum >= rootE.getElementCount()) {
            lineNum = rootE.getElementCount() - 1;
        }
        mTextPane.setCaretPosition(rootE.getElement(lineNum).getStartOffset());
        mTextPane.requestFocusInWindow();
    }

    public void append(String str) {
        try {
            mDocument.insertString(mDocument.getLength(), str, null);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    public void appendAndMoveToBottom(String str) {
        append(str);
        mTextPane.setCaretPosition(mDocument.getLength());
    }

    private void initContextMenu() {
        final JPopupMenu popMenu = new JPopupMenu();
        final JMenu hlAddMenu = new JMenu("Add style");
        final JMenu hlDelMenu = new JMenu("Del style");
        for (int c : TextSearch.Highlights) {
            final JMenuItem addHl = new JMenuItem("HighLight Color");
            final JMenuItem delHl = new JMenuItem("HighLight Color");
            addHl.setBorderPainted(true);
            Color color = new Color(c);
            addHl.setBorder(BorderFactory.createLineBorder(color, 3));
            final TextSearch.ColorHighlightPainter cPainter = new TextSearch.ColorHighlightPainter(color);
            addHl.setAction(new TextAction("-- highlight --") {
                private static final long serialVersionUID = -4928342272856286833L;

                @Override
                public void actionPerformed(ActionEvent e) {
                    String selText = mTextPane.getSelectedText();
                    if (selText != null && selText.length() > 0) {
                        addHl.setText(selText);
                        delHl.setText(selText);
                        mTextSearch.highlightText(selText, cPainter);
                    }
                }
            });
            hlAddMenu.add(addHl);

            delHl.setBorderPainted(true);
            delHl.setBorder(BorderFactory.createLineBorder(color, 3));
            delHl.setAction(new TextAction("-- highlight --") {
                private static final long serialVersionUID = -5065305252189159622L;

                @Override
                public void actionPerformed(ActionEvent e) {
                    mTextSearch.removeHighlight(cPainter);
                    delHl.setText("-- highlight --");
                    addHl.setText("-- highlight --");
                }
            });
            hlDelMenu.add(delHl);
        }
        popMenu.add(hlAddMenu);
        popMenu.add(hlDelMenu);

        addMenuItem(popMenu, "Clear all style", e -> {
            mTextSearch.clearHighlighter();
            for (Component c : hlAddMenu.getMenuComponents()) {
                JMenuItem m = (JMenuItem) c;
                m.setText(m.getAction().getValue(Action.NAME).toString());
            }
        });

        mTextPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON3) {
                    popMenu.show(mTextPane, e.getX(), e.getY());
                }
            }
        });

        popMenu.add(new JPopupMenu.Separator());
        addMenuItem(popMenu, "Cut", e -> mTextPane.cut());
        addMenuItem(popMenu, "Copy", e -> mTextPane.copy());
        addMenuItem(popMenu, "Paste", e -> mTextPane.paste());

        // TODO
//        addMenuItem(popMenu, "Only show selection", new ActionListener() {
//            @Override
//            public void actionPerformed(ActionEvent e) {
//            }
//        });
//        addMenuItem(popMenu, "Exclude selection", new ActionListener() {
//            @Override
//            public void actionPerformed(ActionEvent e) {
//            }
//        });
    }

    private void addMenuItem(JComponent menu, String text, ActionListener action) {
        JMenuItem mi = new JMenuItem(text);
        mi.addActionListener(action);
        menu.add(mi);
    }

    public void setOnSaveAction(Runnable action) {
        mOnSaveAction = action;
    }

    public void setContentDesc(String desc) {
        mContentDesc = desc;
    }

    public void refreshLine() {
        SwingUtilities.invokeLater(() -> mTln.updateFont(mTextPane.getFont()));
    }
}

class StyledUndoManager extends UndoManager {

    public boolean isDocumentChange() {
        UndoableEdit edit = editToBeUndone();
        if (edit instanceof AbstractDocument.DefaultDocumentEvent) {
            AbstractDocument.DefaultDocumentEvent event =
                    (AbstractDocument.DefaultDocumentEvent) edit;
            return event.getType() == EventType.CHANGE;
        }
        return false;
    }

    public synchronized boolean tryUndo() {
        do {
            if (isDocumentChange()) {
                undo();
                continue;
            }
            break;
        } while (true);
        if (canUndo()) {
            undo();
            return true;
        }
        return false;
    }

    public synchronized boolean tryRedo() {
        if (canRedo()) {
            redo();
        }
        do {
            if (isDocumentChange() && canRedo()) {
                redo();
                continue;
            }
            break;
        } while (true);
        return canRedo();
    }

}