package org.rh.ldt.ui;

import org.rh.ldt.DLog;

import javax.swing.AbstractAction;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagLayout;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.StringTokenizer;

class UiUtil {
    private static java.util.Timer sTaskTimer;

    public static java.util.Timer getTaskTimer() {
        if (sTaskTimer == null) {
            sTaskTimer = new java.util.Timer("TaskTimer");
        }
        return sTaskTimer;
    }

    public static void scheduleTask(Runnable r, long delay) {
        java.util.TimerTask task = new java.util.TimerTask() {
            @Override
            public void run() {
                r.run();
            }
        };
        getTaskTimer().schedule(task, delay);
    }

    public static boolean confirm(String title, String content) {
        int result = JOptionPane.showConfirmDialog(null, content,
                title, JOptionPane.YES_NO_OPTION);
        return result == JOptionPane.YES_OPTION;
    }

    public static void showToast(JFrame owner, String text) {
        showToast(owner, text, Toast.LENGTH_SHORT);
    }

    public static void showToast(JFrame owner, String text, int duration) {
        showToast(owner, text, duration, Toast.Style.NORMAL);
    }

    public static void showToast(JFrame owner, String text, int duration, Toast.Style style) {
        Toast.makeText(owner, text, duration, style).display();
    }
}

class ContextMenuUtil {

    private static final JPopupMenu menu = new JPopupMenu();
    private static final Object[] defaultMenuItems = {
        new CutAction(),
        new CopyAction(),
        new PasteAction(),
        new DeleteAction(),
        new JPopupMenu.Separator(),
        new SelectAllAction(),
        new ClearAllAction()
    };

    public interface AutoContextMenu {
    }

    public static class ACMJTextArea extends JTextArea implements AutoContextMenu {
    }

    public static void enable() {
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new MyEventQueue());
    }

    public static class MyEventQueue extends EventQueue {

        @Override
        protected void dispatchEvent(AWTEvent event) {
            super.dispatchEvent(event);

            if (!(event instanceof MouseEvent)) {
                return;
            }

            MouseEvent me = (MouseEvent) event;

            if (!me.isPopupTrigger()) {
                return;
            }

            Component comp = SwingUtilities.getDeepestComponentAt(
                    me.getComponent(), me.getX(), me.getY());
            if (!(comp instanceof JTextComponent) || !(comp instanceof AutoContextMenu)) {
                return;
            }

            if (MenuSelectionManager.defaultManager().getSelectedPath().length > 0) {
                return;
            }

            JTextComponent tc = (JTextComponent) comp;
            comp.requestFocus();

            menu.removeAll();
            for (Object o : defaultMenuItems) {
                if (o instanceof Component) {
                    menu.add((Component) o);
                } else if (o instanceof TextAction) {
                    TextAction ta = (TextAction) o;
                    ta.setTextComp(tc);
                    menu.add(ta);
                }
            }

            Point pt = SwingUtilities.convertPoint(me.getComponent(), me.getPoint(), tc);
            menu.show(tc, pt.x, pt.y);
        }
    }

    static abstract class TextAction extends AbstractAction {

        protected JTextComponent comp;

        public void setTextComp(JTextComponent c) {
            comp = c;
        }

        public TextAction(String label) {
            super(label);
        }

        @Override
        public boolean isEnabled() {
            return comp.isEnabled() && comp.getText().length() > 0;
        }
    }

    static class CutAction extends TextAction {

        public CutAction() {
            super("Cut");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            comp.cut();
        }

        @Override
        public boolean isEnabled() {
            return comp.isEditable() && comp.isEnabled() && comp.getSelectedText() != null;
        }
    }

    static class PasteAction extends TextAction {

        public PasteAction() {
            super("Paste");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            comp.paste();
        }

        @Override
        public boolean isEnabled() {
            if (comp.isEditable() && comp.isEnabled()) {
                Transferable contents =
                        Toolkit.getDefaultToolkit().getSystemClipboard().getContents(this);
                return contents.isDataFlavorSupported(DataFlavor.stringFlavor);
            } else {
                return false;
            }
        }
    }

    static class DeleteAction extends TextAction {

        public DeleteAction() {
            super("Delete");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            comp.replaceSelection(null);
        }

        @Override
        public boolean isEnabled() {
            return comp.isEditable() && comp.isEnabled() && comp.getSelectedText() != null;
        }
    }

    static class CopyAction extends TextAction {

        public CopyAction() {
            super("Copy");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            comp.copy();
        }

        @Override
        public boolean isEnabled() {
            return comp.isEnabled() && comp.getSelectedText() != null;
        }
    }

    static class SelectAllAction extends TextAction {

        public SelectAllAction() {
            super("Select All");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            comp.selectAll();
        }
    }

    static class ClearAllAction extends TextAction {

        public ClearAllAction() {
            super("Clear All");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            comp.setText(null);
        }
    }
}

class Droppable {
    final static int DRAG_OPERATION = System.getProperty("os.name").contains("Windows")
            ? DnDConstants.ACTION_MOVE
            : DnDConstants.ACTION_COPY_OR_MOVE;

    static void setAcceptDrag(DropTargetDragEvent d) {
        d.acceptDrag(DRAG_OPERATION);
    }

    interface CanDropFile {
        void onDrop(File[] files);
    }

    static class FileDropListener implements DropTargetListener {
        final CanDropFile mOwner;

        public FileDropListener(CanDropFile drop) {
            mOwner = drop;
        }

        @Override
        public void dragEnter(DropTargetDragEvent dtde) {
            setAcceptDrag(dtde);
        }

        @Override
        public void dragOver(DropTargetDragEvent dtde) {
        }

        @Override
        public void dropActionChanged(DropTargetDragEvent dtde) {
        }

        @Override
        public void dragExit(DropTargetEvent dte) {
        }

        @Override
        public void drop(DropTargetDropEvent dtde) {
            onDropFiles(dtde, this);
        }

        public void onDropFile(File[] files) {
            mOwner.onDrop(files);
        }
    }

    public static abstract class Panel extends JPanel implements CanDropFile {
        {
            new DropTarget(this, new FileDropListener(this));
        }

        public Panel() {
        }

        public Panel(LayoutManager layout) {
            super(layout);
        }
    }

    public static abstract class EditorPane extends JEditorPane implements CanDropFile {
        {
            new DropTarget(this, new FileDropListener(this));
        }
    }

    public static abstract class TreeView extends JTree implements CanDropFile {
        {
            new DropTarget(this, new FileDropListener(this));
        }
    }

    static DataFlavor sNixFileDataFlavor;

    static void onDropFiles(DropTargetDropEvent dtde, FileDropListener onDrop) {
        try {
            Transferable transferable = dtde.getTransferable();

            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                dtde.acceptDrop(DnDConstants.ACTION_MOVE);
                java.util.List<?> files = (java.util.List<?>) transferable.getTransferData(
                        DataFlavor.javaFileListFlavor);
                File[] fa = new File[files.size()];
                for (int i = 0; i < fa.length; i++) {
                    fa[i] = (File) files.get(i);
                }
                onDrop.onDropFile(fa);
                dtde.getDropTargetContext().dropComplete(true);

            } else {
                if (sNixFileDataFlavor == null) {
                    sNixFileDataFlavor = new DataFlavor("text/uri-list;class=java.lang.String");
                }
                dtde.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
                String data = (String) transferable.getTransferData(sNixFileDataFlavor);
                if (data != null) {
                    ArrayList<File> fs = new ArrayList<>();
                    StringTokenizer st = new StringTokenizer(data, "\r\n");
                    while (st.hasMoreTokens()) {
                        String token = st.nextToken().trim();
                        if (token.startsWith("#") || token.isEmpty()) {
                            continue;
                        }
                        try {
                            fs.add(new File(new URI(token)));
                        } catch (URISyntaxException e) {
                            DLog.ex(e);
                        }
                    }
                    onDrop.onDropFile(fs.toArray(new File[fs.size()]));
                    dtde.getDropTargetContext().dropComplete(true);
                } else {
                    dtde.rejectDrop();
                }
            }
        } catch (Exception e) {
            DLog.ex(e);
        }
    }
}

class Toast extends JDialog {
    // https://github.com/schnie/android-toasts-for-swing/
    public enum Style {
        NORMAL(Color.BLACK),
        SUCCESS(new Color(22, 127, 57)),
        ERROR(new Color(121, 0, 0));
        Color color;

        Style(Color c) {
            color = c;
        }
    };

    public static final int LENGTH_SHORT = 3000;
    public static final int LENGTH_LONG = 6000;

    private static final float MAX_OPACITY = 0.8f;
    private static final float OPACITY_INCREMENT = 0.05f;
    private static final int FADE_REFRESH_RATE = 20;
    private static final int WINDOW_RADIUS = 15;
    private static final int CHARACTER_LENGTH_MULTIPLIER = 7;
    private static final int DISTANCE_FROM_PARENT_TOP = 100;
    private static final boolean SUPPORTS_TRANSLUCENCY;

    private final JFrame mOwner;
    private String mText;
    private int mDuration;
    private Color mBackgroundColor = Color.BLACK;
    private Color mForegroundColor = Color.WHITE;

    static {
        GraphicsEnvironment ge = GraphicsEnvironment
                .getLocalGraphicsEnvironment();
        GraphicsDevice gd = ge.getDefaultScreenDevice();

        SUPPORTS_TRANSLUCENCY = gd.isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.TRANSLUCENT);
    }

    public Toast(JFrame owner) {
        super(owner);
        mOwner = owner;
    }

    private void createGUI() {
        setLayout(new GridBagLayout());
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                setShape(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(),
                        WINDOW_RADIUS, WINDOW_RADIUS));
            }
        });

        setAlwaysOnTop(true);
        setUndecorated(true);
        setFocusableWindowState(false);
        setModalityType(ModalityType.MODELESS);
        setSize(mText.length() * CHARACTER_LENGTH_MULTIPLIER, 25);
        getContentPane().setBackground(mBackgroundColor);

        JLabel label = new JLabel(mText);
        label.setForeground(mForegroundColor);
        add(label);
    }

    @Override
    public void setOpacity(float val) {
        if (SUPPORTS_TRANSLUCENCY) {
            super.setOpacity(val);
        }
    }

    public void fadeIn() {
        final Timer timer = new Timer(FADE_REFRESH_RATE, null);
        timer.setRepeats(true);
        timer.addActionListener(new ActionListener() {
            private float opacity = 0;

            @Override
            public void actionPerformed(ActionEvent e) {
                opacity += OPACITY_INCREMENT;
                setOpacity(Math.min(opacity, MAX_OPACITY));
                if (opacity >= MAX_OPACITY) {
                    timer.stop();
                }
            }
        });

        setOpacity(0);
        timer.start();

        final Point ownerLoc = mOwner.getLocation();
        final int x = (int) (ownerLoc.getX() + ((mOwner.getWidth() - getWidth()) / 2));
        final int y = (int) (ownerLoc.getY() + DISTANCE_FROM_PARENT_TOP);
        setLocation(new Point(x, y));
        setVisible(true);
    }

    public void fadeOut() {
        final Timer timer = new Timer(FADE_REFRESH_RATE, null);
        timer.setRepeats(true);
        timer.addActionListener(new ActionListener() {
            private float opacity = MAX_OPACITY;

            @Override
            public void actionPerformed(ActionEvent e) {
                opacity -= OPACITY_INCREMENT;
                setOpacity(Math.max(opacity, 0));
                if (opacity <= 0) {
                    timer.stop();
                    setVisible(false);
                    dispose();
                }
            }
        });

        setOpacity(MAX_OPACITY);
        timer.start();
    }

    public void setText(String text) {
        mText = text;
    }

    public void setDuration(int duration) {
        mDuration = duration;
    }

    @Override
    public void setBackground(Color backgroundColor) {
        mBackgroundColor = backgroundColor;
    }

    @Override
    public void setForeground(Color foregroundColor) {
        mForegroundColor = foregroundColor;
    }

    public static Toast makeText(JFrame owner, String text) {
        return makeText(owner, text, LENGTH_SHORT);
    }

    public static Toast makeText(JFrame owner, String text, Style style) {
        return makeText(owner, text, LENGTH_SHORT, style);
    }

    public static Toast makeText(JFrame owner, String text, int duration) {
        return makeText(owner, text, duration, Style.NORMAL);
    }

    public static Toast makeText(JFrame owner, String text, int duration, Style style) {
        Toast toast = new Toast(owner);
        toast.mText = text;
        toast.mDuration = duration;
        toast.mBackgroundColor = style.color;
        return toast;
    }

    public void display() {
        new Thread(() -> {
            try {
                createGUI();
                fadeIn();
                Thread.sleep(mDuration);
                fadeOut();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }).start();
    }
}
