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

import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.ClassDef;
import org.rh.ldt.util.DexUtilEx;
import org.rh.ldt.util.SmaliUtil;
import org.rh.smaliex.LLog;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Document;
import javax.swing.text.EditorKit;
import javax.swing.text.Element;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledEditorKit;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceDragEvent;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.dnd.DragSourceEvent;
import java.awt.dnd.DragSourceListener;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.dnd.InvalidDnDOperationException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.HashMap;

class SmaliViewer extends JFrame {
    private static final Icon CLOSE_TAB_ICON_R =
            new ImageIcon(SmaliViewer.class.getResource("close_tab_button_rest.png"));
    private static final Icon CLOSE_TAB_ICON =
            new ImageIcon(SmaliViewer.class.getResource("close_tab_button.png"));
    private static final Icon TAB_ICON =
            new ImageIcon(SmaliViewer.class.getResource("page_edit.png"));
    private static final Icon TAB_ICON_CHANGED =
            new ImageIcon(SmaliViewer.class.getResource("page_edit_mod.png"));
    private final JTabbedPane mTabPane = new DnDTabbedPane();
            //new JTabbedPane();

    private static final EditorKit sStyledEditorKit = new StyledEditorKit() {
        @Override
        public Document createDefaultDocument() {
            return new SmaliDocument();
        }
    };

    SmaliViewer() {
        setTitle("Smali Viewer");
        getContentPane().add(mTabPane);
        //mTabPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        addWindowListener(new WindowAdapter() {

            @Override
            public void windowClosing(WindowEvent e) {
                mTabPane.removeAll();
            }
        });
    }

    public void open(final NodeClass cls, final NodeDex dex) {
        String title = cls.toTitle();
        for (int i = mTabPane.getTabCount() - 1; i >=0 ; i--) {
            if (mTabPane.getTitleAt(i).equals(title)) {
                mTabPane.setSelectedIndex(i);
                return;
            }
        }
        Runnable onTabClose = () -> {
            if (mTabPane.getTabCount() == 0) {
                setVisible(false);
            }
        };
        EditorKit editorKit = sStyledEditorKit;
        if (cls.smaliContent.length() > 200000) {
            int sel = JOptionPane.showConfirmDialog(null,
                    "The content is big. Do you still want to apply syntax highlight?");
            if (sel == JOptionPane.NO_OPTION) {
                editorKit = null;
            } else if (sel != JOptionPane.OK_OPTION) {
                return;
            }
        }

        final ViewerPanel vp = new ViewerPanel(editorKit, null, cls.smaliContent);
        vp.setOnSaveAction(() -> {
            ClassDef clsDef = SmaliUtil.assembleSmali(
                    vp.getText(), cls.classDef.dexFile.getOpcodes().api);
            if (clsDef != null) {
                DexUtilEx.SingleClassReplacer mdex;
                if (dex.modifiedDex == null) {
                    mdex = new DexUtilEx.SingleClassReplacer(dex.dexFile);
                    dex.modifiedDex = mdex;
                } else {
                    mdex = (DexUtilEx.SingleClassReplacer) dex.modifiedDex;
                }
                mdex.addReplaceClass(clsDef);
                LLog.i("Stored to memory: " + cls.simpleName);
            } else {
                LLog.i("Assemble failed: " + cls.simpleName);
            }
        });
        vp.setContentDesc(cls.simpleName);
        //mTabPane.addTab(cls.simpleName, vp);
        final TabTitle tabTitle = addTab(mTabPane, vp, title, onTabClose);
        mTabPane.indexOfTabComponent(vp);

        vp.setContentChangeListenr(new ViewerPanel.ContentChangeListener() {
            boolean curStatus = false;
            @Override
            public void onChange(boolean hasChanged) {
                if (curStatus != hasChanged) {
                    tabTitle.titleLabel.setIcon(hasChanged ? TAB_ICON_CHANGED : TAB_ICON);
                    curStatus = hasChanged;
                }
            }
        });

        vp.requestFocus();
        vp.gotoLine(1);
        setVisible(true);
    }

    static class TabTitle extends JPanel {
        JLabel titleLabel;
        JButton closeBtn;

        TabTitle(String title, Icon icon, Icon close, Icon closeOn) {
            super(new FlowLayout(FlowLayout.CENTER, 5, 0));
            setOpaque(false);
            titleLabel = new JLabel(title);
            titleLabel.setIcon(icon);

            closeBtn = new JButton();
            closeBtn.setOpaque(false);
            closeBtn.setRolloverIcon(close);
            closeBtn.setRolloverEnabled(true);
            closeBtn.setIcon(closeOn);
            closeBtn.setBorder(null);
            closeBtn.setFocusable(false);

            add(titleLabel);
            add(closeBtn);
            setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        }
    }

    private static TabTitle addTab(final JTabbedPane tabbedPane, final JComponent content,
                                   final String title, final Runnable onTabClose) {
        tabbedPane.addTab(null, content);
        int pos = tabbedPane.indexOfComponent(content);
        TabTitle tabTitle = new TabTitle(title, TAB_ICON, CLOSE_TAB_ICON, CLOSE_TAB_ICON_R);
        tabbedPane.setTabComponentAt(pos, tabTitle);
        tabbedPane.setTitleAt(pos, title);

        final ActionListener closeListener = e -> {
            tabbedPane.remove(content);
            onTabClose.run();
        };
        tabTitle.closeBtn.addActionListener(closeListener);
        tabbedPane.setSelectedComponent(content);

        AbstractAction closeTabAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                closeListener.actionPerformed(e);
            }
        };

        KeyStroke controlW = KeyStroke.getKeyStroke("control W");
        InputMap inputMap = content.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        inputMap.put(controlW, "closeTab");
        content.getActionMap().put("closeTab", closeTabAction);

        return tabTitle;
    }
}

// http://www.dound.com/src/MultiSyntaxDocument.java
class SmaliDocument extends DefaultStyledDocument {

    private static final HashMap<String, MutableAttributeSet> keywords;
    private static final SimpleAttributeSet STYLE_DEFAULT;
    private static final SimpleAttributeSet STYLE_COMMENT;
    private static final SimpleAttributeSet STYLE_STRING;
    private Element rootElement;

    static {
        keywords = new HashMap<>();
        String[] kw = (".method .annotation .end .line .prologue .implements .super .class .source "
                + ".locals .parameter .field .local .restart").split(" ");
        initStyle(kw, new Color(30, 60, 190));
        Opcode[] opcodes = Opcode.values();
        String[] instr = new String[opcodes.length];
        for (int i = 0; i < opcodes.length; i++) {
            instr[i] = opcodes[i].name;
        }

        initStyle(instr, new Color(140, 110, 40));
        String[] acc = "public annotation method protected static final field private synthetic local".split(" ");
        initStyle(acc, new Color(90, 20, 120));

        STYLE_DEFAULT = new SimpleAttributeSet();
		StyleConstants.setForeground(STYLE_DEFAULT, Color.BLACK);

        STYLE_COMMENT = new SimpleAttributeSet();
        StyleConstants.setForeground(STYLE_COMMENT, new Color(51, 102, 0));

        STYLE_STRING = new SimpleAttributeSet();
        StyleConstants.setForeground(STYLE_STRING, new Color(153, 0, 107));
    }

    private static void initStyle(String[] kws, Color c) {
        SimpleAttributeSet sas = new SimpleAttributeSet();
        StyleConstants.setForeground(sas, c);
        StyleConstants.setBold(sas, true);
        for (String k : kws) {
			keywords.put(k, sas);
		}
    }

    SmaliDocument() {
        rootElement = getDefaultRootElement();
        putProperty(javax.swing.text.DefaultEditorKit.EndOfLineStringProperty, "\n");
    }

    @Override
    public void insertString(final int offs, final String str, AttributeSet a)
            throws BadLocationException {
        super.insertString(offs, str, a);
        execHighlight(offs, str.length());
    }

    @Override
    public void remove(int offs, int len) throws BadLocationException {
        super.remove(offs, len);
        execHighlight(offs, 0);
    }

    private void execHighlight(final int offset, final int length) throws BadLocationException {
        String content = getText(0, getLength());

        int startLine = rootElement.getElementIndex(offset);
        int endLine = rootElement.getElementIndex(offset + length);
        //System.out.println(startLine + " " + endLine);
        for (int i = startLine; i <= endLine; i++) {
            execHighlightInner(content, i);
        }

//        int end = rootElement.getElementIndex(content.length());
//        for (int i = endLine + 1; i < end; i++) {
//            Element branch = rootElement.getElement(i);
//            Element leaf = getCharacterElement(branch.getStartOffset());
//            AttributeSet as = leaf.getAttributes();
//            if (as.isEqual(STYLE_COMMENT)) {
//                execHighlightInner(content, i);
//            }
//        }
    }

    private void execHighlightInner(String content, int line) {
        int startOffset = rootElement.getElement(line).getStartOffset();
        int endOffset = rootElement.getElement(line).getEndOffset() - 1;
        int contentLength = content.length();

        if (endOffset >= contentLength) {
            endOffset = contentLength - 1;
        }
        // Set normal attributes for the line
        int lineLength = endOffset - startOffset;
        //long s = System.currentTimeMillis();
        setCharacterAttributes(startOffset, lineLength, STYLE_DEFAULT, true);
        //System.out.println(line +" " + startOffset + " " + endOffset + " " + (System.currentTimeMillis() - s));

        // Check for single line comment
        int index = content.indexOf('#', startOffset);
        if ((index > -1) && (index < endOffset)) {
            setCharacterAttributes(index, endOffset - index + 1, STYLE_COMMENT, false);
            endOffset = index - 1;
        }
        while (startOffset <= endOffset) {
            // Skip the delimiters to find the start of a new token
            while (isDelimiter(content.substring(startOffset, startOffset + 1))) {
                if (startOffset < endOffset) {
                    startOffset++;
                } else {
                    return;
                }
            }

            // Extract and process the entire token
            if (isQuoteDelimiter(content.substring(startOffset, startOffset + 1))) {
                startOffset = getQuoteToken(content, startOffset, endOffset);
            } else {
                startOffset = getOtherToken(content, startOffset, endOffset);
            }
        }
    }

    private int getQuoteToken(String content, int startOffset, int endOffset) {
        String quoteDelimiter = content.substring(startOffset, startOffset + 1);
        String escapeString = "\\" + quoteDelimiter;
        int endOfQuote = startOffset;

        // Skip over the escape quotes in this quote
        int index = content.indexOf(escapeString, endOfQuote + 1);
        while ((index > -1) && (index < endOffset)) {
            endOfQuote = index + 1;
            index = content.indexOf(escapeString, endOfQuote);
        }

		// Now find the matching delimiter
        index = content.indexOf(quoteDelimiter, endOfQuote + 1);
        if ((index < 0) || (index > endOffset)) {
            endOfQuote = endOffset;
        } else {
            endOfQuote = index;
        }
        setCharacterAttributes(startOffset, endOfQuote - startOffset + 1, STYLE_STRING, false);

        return endOfQuote + 1;
    }

    private int getOtherToken(String content, int startOffset, int endOffset) {
        int endOfToken = startOffset + 1;

        while (endOfToken <= endOffset) {
            String c = content.substring(endOfToken, endOfToken + 1);
            // '-' for smali instr
            if (c.charAt(0) != '-' && isDelimiter(c)) {
                break;
            }

            endOfToken++;
        }

        String token = content.substring(startOffset, endOfToken);

        // see if this token has a highlighting format associated with it
        MutableAttributeSet attr = keywords.get(token);
        if (attr != null) {
            setCharacterAttributes(startOffset, endOfToken - startOffset, attr, false);
        }

        return endOfToken + 1;
    }

    private static boolean isDelimiter(String character) {
        String operands = ";:{}()[]+-/%<=>!&|^~*";
        return Character.isWhitespace(character.charAt(0)) || operands.contains(character);
    }

    private static boolean isQuoteDelimiter(String character) {
        return "\"'".contains(character);
    }
}

// https://github.com/aterai/java-swing-tips/blob/master/LICENSE.txt
// http://java-swing-tips.blogspot.tw/2008/04/drag-and-drop-tabs-in-jtabbedpane.html
class TabTransferable implements Transferable {

    private static final String NAME = "TabTransferable";
    private static final DataFlavor FLAVOR = new DataFlavor(
            DataFlavor.javaJVMLocalObjectMimeType, NAME);
    private final Component tabbedPane;

    TabTransferable(Component tabbedPane) {
        this.tabbedPane = tabbedPane;
    }

    @Override
    public Object getTransferData(DataFlavor flavor) {
        return tabbedPane;
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        DataFlavor[] f = new DataFlavor[1];
        f[0] = FLAVOR;
        return f;
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return flavor.getHumanPresentableName().equals(NAME);
    }
}

class DnDTabbedPane extends JTabbedPane {

    private static final int LINEWIDTH = 3;
    private static final Color LINE_COLOR = new Color(0, 100, 255);
    private final GhostGlassPane mGlassPane;
    private final Rectangle mLineRect = new Rectangle();
    private int mDragTabIndex = -1;

    protected boolean mHasGhost = true;
    protected boolean mIsPaintScrollArea = false;

    private Rectangle mRectBackward = new Rectangle();
    private Rectangle mRectForward = new Rectangle();
    private static final int RECT_WH = 20;
    private static final int BUTTON_SIZE = 30; // Scroll button size

    public void autoScrollTest(Point glassPt) {
        Rectangle r = getTabAreaBounds();
        if (tabPlacement == TOP || tabPlacement == BOTTOM) {
            mRectBackward.setBounds(r.x, r.y, RECT_WH, r.height);
            mRectForward.setBounds(r.x + r.width - RECT_WH - BUTTON_SIZE,
                    r.y, RECT_WH + BUTTON_SIZE, r.height);
        } else if (tabPlacement == LEFT || tabPlacement == RIGHT) {
            mRectBackward.setBounds(r.x, r.y, r.width, RECT_WH);
            mRectForward.setBounds(r.x, r.y + r.height - RECT_WH - BUTTON_SIZE,
                    r.width, RECT_WH + BUTTON_SIZE);
        }
        mRectBackward = SwingUtilities.convertRectangle(getParent(), mRectBackward, mGlassPane);
        mRectForward = SwingUtilities.convertRectangle(getParent(), mRectForward, mGlassPane);
        if (mRectBackward.contains(glassPt)) {
            clickArrowButton("scrollTabsBackwardAction");
        } else if (mRectForward.contains(glassPt)) {
            clickArrowButton("scrollTabsForwardAction");
        }
    }

    private void clickArrowButton(String actionKey) {
        ActionMap map = getActionMap();
        if (map != null) {
            Action action = map.get(actionKey);
            if (action != null && action.isEnabled()) {
                action.actionPerformed(new ActionEvent(
                        this, ActionEvent.ACTION_PERFORMED, null, 0, 0));
            }
        }
    }

    DnDTabbedPane() {
        mGlassPane = new GhostGlassPane();
        mGlassPane.setName("GlassPane");
        initDrop();
    }

    private void initDrop() {
        new DropTarget(mGlassPane, DnDConstants.ACTION_COPY_OR_MOVE,
                new TabDropTargetListener(), true);
        new DragSource().createDefaultDragGestureRecognizer(this,
                DnDConstants.ACTION_COPY_OR_MOVE, new TabDragGestureListener());
        //DragSource.getDefaultDragSource().createDefaultDragGestureRecognizer(this,
        //    DnDConstants.ACTION_COPY_OR_MOVE, new TabDragGestureListener());
    }

    class TabDragSourceListener implements DragSourceListener {

        @Override
        public void dragEnter(DragSourceDragEvent e) {
            e.getDragSourceContext().setCursor(DragSource.DefaultMoveDrop);
        }

        @Override
        public void dragExit(DragSourceEvent e) {
            e.getDragSourceContext().setCursor(DragSource.DefaultMoveNoDrop);
            mLineRect.setRect(0, 0, 0, 0);
            mGlassPane.setPoint(new Point(-1000, -1000));
            mGlassPane.repaint();
        }

        @Override
        public void dragOver(DragSourceDragEvent e) {
            Point glassPt = e.getLocation();
            SwingUtilities.convertPointFromScreen(glassPt, mGlassPane);
            int targetIdx = getTargetTabIndex(glassPt);
            if (getTabAreaBounds().contains(glassPt) && targetIdx >= 0
                    && targetIdx != mDragTabIndex && targetIdx != mDragTabIndex + 1) {
                e.getDragSourceContext().setCursor(DragSource.DefaultMoveDrop);
                mGlassPane.setCursor(DragSource.DefaultMoveDrop);
            } else {
                e.getDragSourceContext().setCursor(DragSource.DefaultMoveNoDrop);
                mGlassPane.setCursor(DragSource.DefaultMoveNoDrop);
            }
        }

        @Override
        public void dragDropEnd(DragSourceDropEvent e) {
            mLineRect.setRect(0, 0, 0, 0);
            mDragTabIndex = -1;
            mGlassPane.setVisible(false);
            mGlassPane.setImage(null);

            //Component parent = e.getDragSourceContext().getComponent();
            //if (parent instanceof DnDTabbedPane) {
            //    DnDTabbedPane tabbedPane = (DnDTabbedPane) parent;
            //    int pos = tabbedPane.indexOfComponent(tabbedPane.getSelectedComponent());
            //}
        }

        @Override
        public void dropActionChanged(DragSourceDragEvent e) {
        }
    }

    class TabDropTargetListener implements DropTargetListener {

        @Override
        public void dragEnter(DropTargetDragEvent e) {
            if (isDragAcceptable(e)) {
                e.acceptDrag(e.getDropAction());
            } else {
                e.rejectDrag();
            }
        }

        @Override
        public void dragExit(DropTargetEvent e) {
            //Component c = e.getDropTargetContext().getComponent();
            //System.out.println("DropTargetListener#dragExit: " + c.getName());
        }

        @Override
        public void dropActionChanged(DropTargetDragEvent e) {
        }

        private Point prevGlassPt = new Point();

        @Override
        public void dragOver(final DropTargetDragEvent e) {
            Point glassPt = e.getLocation();
            if (getTabPlacement() == JTabbedPane.TOP || getTabPlacement() == JTabbedPane.BOTTOM) {
                initTargetLeftRightLine(getTargetTabIndex(glassPt));
            } else {
                initTargetTopBottomLine(getTargetTabIndex(glassPt));
            }
            if (mHasGhost) {
                mGlassPane.setPoint(glassPt);
            }
            if (!prevGlassPt.equals(glassPt)) {
                mGlassPane.repaint();
            }
            prevGlassPt = glassPt;
            autoScrollTest(glassPt);
        }

        @Override
        public void drop(DropTargetDropEvent e) {
            if (isDropAcceptable(e)) {
                convertTab(mDragTabIndex, getTargetTabIndex(e.getLocation()));
                e.dropComplete(true);
            } else {
                e.dropComplete(false);
            }

            repaint();
            dispatchEvent(new MouseEvent(DnDTabbedPane.this,
                    0, 0, 0, e.getLocation().x, e.getLocation().y, 1, false));
        }

        private boolean isDragAcceptable(DropTargetDragEvent e) {
            Transferable t = e.getTransferable();
            DataFlavor[] f = e.getCurrentDataFlavors();
            return t.isDataFlavorSupported(f[0]) && mDragTabIndex >= 0;
        }

        private boolean isDropAcceptable(DropTargetDropEvent e) {
            Transferable t = e.getTransferable();
            DataFlavor[] f = t.getTransferDataFlavors();
            return t.isDataFlavorSupported(f[0]) && mDragTabIndex >= 0;
        }
    }

    class TabDragGestureListener implements DragGestureListener {

        @Override
        public void dragGestureRecognized(DragGestureEvent e) {
            if (getTabCount() <= 1) {
                return;
            }
            Point tabPt = e.getDragOrigin();
            mDragTabIndex = indexAtLocation(tabPt.x, tabPt.y);
            if (mDragTabIndex < 0 || !isEnabledAt(mDragTabIndex)) {
                return;
            }
            initGlassPane(e.getComponent(), e.getDragOrigin());
            try {
                e.startDrag(DragSource.DefaultMoveDrop,
                        new TabTransferable(e.getComponent()), new TabDragSourceListener());
            } catch (InvalidDnDOperationException idoe) {
                idoe.printStackTrace();
            }
        }
    }

    private int getTargetTabIndex(Point glassPt) {
        Point tabPt = SwingUtilities.convertPoint(mGlassPane, glassPt, DnDTabbedPane.this);
        boolean isTB = getTabPlacement() == JTabbedPane.TOP
                || getTabPlacement() == JTabbedPane.BOTTOM;
        for (int i = 0; i < getTabCount(); i++) {
            Rectangle r = getBoundsAt(i);
            if (isTB) {
                r.setRect(r.x - r.width / 2, r.y, r.width, r.height);
            } else {
                r.setRect(r.x, r.y - r.height / 2, r.width, r.height);
            }
            if (r.contains(tabPt)) {
                return i;
            }
        }
        Rectangle r = getBoundsAt(getTabCount() - 1);
        if (isTB) {
            r.setRect(r.x + r.width / 2, r.y, r.width, r.height);
        } else {
            r.setRect(r.x, r.y + r.height / 2, r.width, r.height);
        }
        return r.contains(tabPt) ? getTabCount() : -1;
    }

    private void convertTab(int prev, int next) {
        if (next < 0 || prev == next) {
            return;
        }
        Component cmp = getComponentAt(prev);
        Component tab = getTabComponentAt(prev);
        String str = getTitleAt(prev);
        Icon icon = getIconAt(prev);
        String tip = getToolTipTextAt(prev);
        boolean flg = isEnabledAt(prev);
        int tgtindex = prev > next ? next : next - 1;
        remove(prev);
        insertTab(str, icon, cmp, tip, tgtindex);
        setEnabledAt(tgtindex, flg);
        if (flg) {
            setSelectedIndex(tgtindex);
        }
        setTabComponentAt(tgtindex, tab);
    }

    private void initTargetLeftRightLine(int next) {
        if (next < 0 || mDragTabIndex == next || next - mDragTabIndex == 1) {
            mLineRect.setRect(0, 0, 0, 0);
        } else if (next == 0) {
            Rectangle r = SwingUtilities.convertRectangle(this, getBoundsAt(0), mGlassPane);
            mLineRect.setRect(r.x - LINEWIDTH / 2, r.y, LINEWIDTH, r.height);
        } else {
            Rectangle r = SwingUtilities.convertRectangle(
                    this, getBoundsAt(next - 1), mGlassPane);
            mLineRect.setRect(r.x + r.width - LINEWIDTH / 2, r.y, LINEWIDTH, r.height);
        }
    }

    private void initTargetTopBottomLine(int next) {
        if (next < 0 || mDragTabIndex == next || next - mDragTabIndex == 1) {
            mLineRect.setRect(0, 0, 0, 0);
        } else if (next == 0) {
            Rectangle r = SwingUtilities.convertRectangle(this, getBoundsAt(0), mGlassPane);
            mLineRect.setRect(r.x, r.y - LINEWIDTH / 2, r.width, LINEWIDTH);
        } else {
            Rectangle r = SwingUtilities.convertRectangle(this, getBoundsAt(next - 1), mGlassPane);
            mLineRect.setRect(r.x, r.y + r.height - LINEWIDTH / 2, r.width, LINEWIDTH);
        }
    }

    private void initGlassPane(Component c, Point tabPt) {
        getRootPane().setGlassPane(mGlassPane);
        if (mHasGhost) {
            Rectangle rect = getBoundsAt(mDragTabIndex);
            BufferedImage image = new BufferedImage(
                    c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics g = image.getGraphics();
            c.paint(g);
            rect.x = rect.x < 0 ? 0 : rect.x;
            rect.y = rect.y < 0 ? 0 : rect.y;
            try {
                image = image.getSubimage(rect.x, rect.y, rect.width, rect.height);
                mGlassPane.setImage(image);
            } catch (Exception ignored) {
            }
        }
        Point glassPt = SwingUtilities.convertPoint(c, tabPt, mGlassPane);
        mGlassPane.setPoint(glassPt);
        mGlassPane.setVisible(true);
    }

    private Rectangle getTabAreaBounds() {
        Rectangle tabbedRect = getBounds();
        Component comp = getSelectedComponent();
        int idx = 0;
        while (comp == null && idx < getTabCount()) {
            comp = getComponentAt(idx++);
        }
        Rectangle compRect = (comp == null) ? new Rectangle() : comp.getBounds();
        if (tabPlacement == TOP) {
            tabbedRect.height = tabbedRect.height - compRect.height;
        } else if (tabPlacement == BOTTOM) {
            tabbedRect.y = tabbedRect.y + compRect.y + compRect.height;
            tabbedRect.height = tabbedRect.height - compRect.height;
        } else if (tabPlacement == LEFT) {
            tabbedRect.width = tabbedRect.width - compRect.width;
        } else if (tabPlacement == RIGHT) {
            tabbedRect.x = tabbedRect.x + compRect.x + compRect.width;
            tabbedRect.width = tabbedRect.width - compRect.width;
        }
        tabbedRect.grow(2, 2);
        return tabbedRect;
    }

    class GhostGlassPane extends JPanel {

        private final AlphaComposite mComposite;
        private Point mLocation = new Point(0, 0);
        private BufferedImage mDraggingGhost;

        GhostGlassPane() {
            super();
            setOpaque(false);
            mComposite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f);
            //setCursor(null);
        }

        public void setImage(BufferedImage dragging) {
            mDraggingGhost = dragging;
        }

        public void setPoint(Point loc) {
            mLocation = loc;
        }

        @Override
        public void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setComposite(mComposite);
            if (mIsPaintScrollArea && getTabLayoutPolicy() == SCROLL_TAB_LAYOUT) {
                g2.setPaint(Color.RED);
                g2.fill(mRectBackward);
                g2.fill(mRectForward);
            }
            if (mDraggingGhost != null) {
                double xx = mLocation.getX() - mDraggingGhost.getWidth(this) / 2d;
                double yy = mLocation.getY() - mDraggingGhost.getHeight(this) / 2d;
                g2.drawImage(mDraggingGhost, (int) xx, (int) yy, null);
            }
            if (mDragTabIndex >= 0) {
                g2.setPaint(LINE_COLOR);
                g2.fill(mLineRect);
            }
        }
    }
}
