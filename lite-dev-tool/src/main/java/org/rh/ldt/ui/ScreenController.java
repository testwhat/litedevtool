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

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.Device;
import com.android.ddmlib.RawImage;

import org.rh.ldt.DLog;
import org.rh.ldt.util.AdbUtilEx;
import org.rh.smaliex.AdbUtil;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;

public class ScreenController implements ActionListener {

    public static void main(String[] args) {
        AdbUtilEx.startAdb(new AdbUtilEx.DeviceListener() {

            final HashMap<Device, ScreenController> mScreens = new HashMap<>();

            @Override
            public void deviceConnected(Device device) {
                //AdbUtil.shell(device, "input keyevent 26");
                //AdbUtil.shell(device, "input swipe 200 702 200 10 500");
                ScreenController sc = new ScreenController(device, true);
                mScreens.put(device, sc);
                sc.show();
            }

            @Override
            public void deviceDisconnected(Device device) {
                DLog.i(device + " has disconnected");
                ScreenController sc = mScreens.remove(device);
                if (sc != null) {
                    sc.stop();
                }
            }

        });
    }

    private final Timer mTimer;
    private final Device mDevice;
    private BufferedImage mImage;
    private int[] mScanline;
    private volatile boolean mIsLoading;
    private GetScreenshotTask mTask;
    private final JFrame mFrame;
    private final JPanel mPanel;

    public ScreenController(Device device) {
        this(device, false);
    }

    public ScreenController(Device device, boolean standalone) {
        mDevice = device;
        mTimer = new Timer(3000, this);
        mTimer.setInitialDelay(0);
        mTimer.setRepeats(true);

        mPanel = new JPanel();
        mPanel.setLayout(new BorderLayout());
        mPanel.setOpaque(false);
        mPanel.add(new ScreenshotViewer());

        mFrame = new JFrame(device.getName());
        mFrame.getContentPane().add(mPanel);
        SwingUtilities.invokeLater(mTimer::start);
        mFrame.setDefaultCloseOperation(standalone
                ? javax.swing.WindowConstants.EXIT_ON_CLOSE
                : javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        mFrame.pack();
        mFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stop();
            }
        });
    }

    public void show() {
        mFrame.setVisible(true);
    }

    public void stop() {
        mTimer.stop();
        mFrame.setVisible(false);
        mFrame.dispose();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (mTask != null && !mTask.isDone()) {
            return;
        }
        mTask = new GetScreenshotTask();
        mTask.execute();
    }

    private class GetScreenshotTask extends SwingWorker<Boolean, Void> {

        private GetScreenshotTask() {
        }

        @Override
        protected Boolean doInBackground() throws Exception {
            RawImage rawImage;
            long s;
            try {
                s = System.currentTimeMillis();
                rawImage = RawImage.getFrameBuffer(AndroidDebugBridge.getSocketAddress(), mDevice);
            } catch (IOException e) {
                DLog.ex(e);
                return false;
            }

            boolean resize = false;
            mIsLoading = true;
            try {
                if (rawImage != null) {
                    if (mImage == null || rawImage.width != mImage.getWidth()
                            || rawImage.height != mImage.getHeight()) {
                        mImage = new BufferedImage(rawImage.width, rawImage.height,
                                BufferedImage.TYPE_INT_ARGB);
                        mScanline = new int[rawImage.width];
                        resize = true;
                    }

                    DLog.i("w=" + rawImage.width + " h=" + rawImage.height + " bpp=" + rawImage.bpp
                        + " dur=" + (System.currentTimeMillis() - s) + "ms");
                    switch (rawImage.bpp) {
                    case 16:
                        rawImage16toARGB(rawImage);
                        break;
                    case 32:
                        rawImage32toARGB(rawImage);
                        break;
                    }
                }
            } finally {
                mIsLoading = false;
            }

            return resize;
        }

        private int getMask(int length) {
            int res = 0;
            for (int i = 0; i < length; i++) {
                res = (res << 1) + 1;
            }

            return res;
        }

        private void rawImage32toARGB(RawImage rawImage) {
            byte[] buffer = rawImage.data;
            int index = 0;

            final int redOffset = rawImage.red_offset;
            final int redLength = rawImage.red_length;
            final int redMask = getMask(redLength);
            final int greenOffset = rawImage.green_offset;
            final int greenLength = rawImage.green_length;
            final int greenMask = getMask(greenLength);
            final int blueOffset = rawImage.blue_offset;
            final int blueLength = rawImage.blue_length;
            final int blueMask = getMask(blueLength);
            final int alphaLength = rawImage.alpha_length;
            final int alphaOffset = rawImage.alpha_offset;
            final int alphaMask = getMask(alphaLength);

            for (int y = 0; y < rawImage.height; y++) {
                for (int x = 0; x < rawImage.width; x++) {
                    int value = buffer[index++] & 0x00FF;
                    value |= (buffer[index++] & 0x00FF) << 8;
                    value |= (buffer[index++] & 0x00FF) << 16;
                    value |= (buffer[index++] & 0x00FF) << 24;

                    int r = ((value >>> redOffset) & redMask) << (8 - redLength);
                    int g = ((value >>> greenOffset) & greenMask) << (8 - greenLength);
                    int b = ((value >>> blueOffset) & blueMask) << (8 - blueLength);
                    int a = 0xFF;

                    if (alphaLength != 0) {
                        a = ((value >>> alphaOffset) & alphaMask) << (8 - alphaLength);
                    }

                    mScanline[x] = a << 24 | r << 16 | g << 8 | b;
                }

                mImage.setRGB(0, y, rawImage.width, 1, mScanline,
                        0, rawImage.width);
            }
        }

        private void rawImage16toARGB(RawImage rawImage) {
            byte[] buffer = rawImage.data;
            int index = 0;

            for (int y = 0; y < rawImage.height; y++) {
                for (int x = 0; x < rawImage.width; x++) {
                    int value = buffer[index++] & 0x00FF;
                    value |= (buffer[index++] << 8) & 0x0FF00;

                    int r = ((value >> 11) & 0x01F) << 3;
                    int g = ((value >> 5) & 0x03F) << 2;
                    int b = ((value) & 0x01F) << 3;

                    mScanline[x] = 0xFF << 24 | r << 16 | g << 8 | b;
                }

                mImage.setRGB(0, y, rawImage.width, 1, mScanline,
                        0, rawImage.width);
            }
        }

        @Override
        protected void done() {
            try {
                if (get()) {
                    mPanel.validate();
                }
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
            mPanel.repaint();

        }
    }


    class ScreenshotViewer extends JComponent {

        float ratioX;
        float ratioY;
        long time;
        private int mTouchStartX;
        private int mTouchStartY;
        private int cxr;
        private int cyr;

        JPopupMenu menu = new JPopupMenu();
        {
            JMenuItem mi = new JMenuItem("power key");
            ActionListener l = e -> shellCmd("input keyevent 26");
            mi.addActionListener(l);
            menu.add(mi);
        }

        void shellCmd(String cmd) {
            System.out.println(cmd);
            AdbUtil.shell(mDevice, cmd);
            System.out.println("end " + cmd);
        }
        
        InputStateController controller = new InputStateController() {

            @Override
            public void mouseClicked(MouseEvent e) {
               
                if (e.getButton() == MouseEvent.BUTTON3) {
                    menu.show(mPanel, e.getX(), e.getY());
                    return;
                }
                if (mImage == null) {
                    return;
                }
                int x = x(e.getX());
                int y = y(e.getY());
                shellCmd("input tap " + x + " " + y);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                super.mouseMoved(e);
                cxr = e.getX();
                cyr = e.getY();
                mPanel.repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                super.mousePressed(e);
                mTouchStartX = x(e.getX());
                mTouchStartY = y(e.getY());
                time = System.currentTimeMillis();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                super.mouseReleased(e);
                int x = x(e.getX());
                int y = y(e.getY());
                if (Math.abs(mTouchStartX - x) < 5 && Math.abs(mTouchStartY - y) < 5) {
                    return;
                }
                long dur = System.currentTimeMillis() - time;
                String cmd = String.format("input swipe %d %d %d %d %d",
                                mTouchStartX, mTouchStartY, x, y, dur);
                shellCmd(cmd);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                super.mouseDragged(e);
                //int x = x(e.getX());
                //int y = y(e.getY());
                //LLog.i("mouseDragged " + x + " " + y);
            }

        };

        ScreenshotViewer() {
            setOpaque(true);
            addKeyListener(controller);
            addMouseListener(controller);
            addMouseMotionListener(controller);
            addMouseWheelListener(controller);
        }

        public int x(int x) {
            return (int) (ratioX * x);
        }

        public int y(int y) {
            return (int) (ratioY * y);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());

            if (mIsLoading) {
                return;
            }

            if (mImage != null) {
                ratioX = (float) mImage.getWidth() / (float) getWidth();
                ratioY = (float) mImage.getHeight() / (float) getHeight();
                //g.drawImage(image, 0, 0, null);
                drawScaledImage(mImage, this, g);
            }
            g.setColor(Color.RED);
            g.drawString("x=" + x(cxr) + "y=" + y(cyr), cxr - 10, cyr);
        }

        @Override
        public Dimension getPreferredSize() {
            if (mImage == null) {
                return new Dimension(480, 854);
            }
            return new Dimension(mImage.getWidth(), mImage.getHeight());
        }
    }

    public static void drawScaledImage(Image image, Component canvas, Graphics g) {
        int imgWidth = image.getWidth(null);
        int imgHeight = image.getHeight(null);

        float imgAspect = (float) imgHeight / imgWidth;

        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();

        float canvasAspect = (float) canvasHeight / canvasWidth;

        int x1 = 0; // top left X position
        int y1 = 0; // top left Y position
        int x2; // bottom right X position
        int y2; // bottom right Y position

        if (imgWidth < canvasWidth && imgHeight < canvasHeight) {
            // the image is smaller than the canvas
            x1 = (canvasWidth - imgWidth) / 2;
            y1 = (canvasHeight - imgHeight) / 2;
            x2 = imgWidth + x1;
            y2 = imgHeight + y1;

        } else {
            if (canvasAspect > imgAspect) {
                y1 = canvasHeight;
                // keep image aspect ratio
                canvasHeight = (int) (canvasWidth * imgAspect);
                y1 = (y1 - canvasHeight) / 2;
            } else {
                x1 = canvasWidth;
                // keep image aspect ratio
                canvasWidth = (int) (canvasHeight / imgAspect);
                x1 = (x1 - canvasWidth) / 2;
            }
            x2 = canvasWidth + x1;
            y2 = canvasHeight + y1;
        }

        g.drawImage(image, x1, y1, x2, y2, 0, 0, imgWidth, imgHeight, null);
    }


    static class InputStateController implements MouseListener,
            MouseMotionListener, KeyListener, MouseWheelListener {

        final boolean[] mKeyState = new boolean[526];
        final boolean[] mMouseState = new boolean[4];
        boolean mActive = false;

        public InputStateController() {
        }

        public void clearKeyState() {
            for (int i = 0; i < mKeyState.length; i++) {
                mKeyState[i] = false;
            }
        }

        public void pause() {
            mActive = false;
        }

        public void resume() {
            mActive = true;
        }

        @Override
        public void keyPressed(KeyEvent e) {
            if (!mActive) {
                return;
            }
            mKeyState[e.getKeyCode()] = true;
        }

        @Override
        public void keyReleased(KeyEvent e) {
            if (!mActive) {
                return;
            }
            keyReleasedAction();
            mKeyState[e.getKeyCode()] = false;
        }

        public void keyReleasedAction() {
        }

        @Override
        public void keyTyped(KeyEvent e) {
        }

        @Override
        public void mouseMoved(MouseEvent e) {
        }

        @Override
        public void mouseClicked(MouseEvent e) {
        }

        @Override
        public void mouseEntered(MouseEvent e) {
        }

        @Override
        public void mouseExited(MouseEvent e) {
        }

        @Override
        public void mousePressed(MouseEvent e) {
            if (!mActive) {
                return;
            }
            mMouseState[e.getButton()] = true;
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (!mActive) {
                return;
            }
            mMouseState[e.getButton()] = false;
        }

        @Override
        public void mouseDragged(MouseEvent e) {
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
        }
    }


    public enum AndroidKey { //frameworks/base/core/java/android/view/KeyEvent.java
        HOME(3),
        BACK(4),
        DPAD_UP(19),
        DPAD_DOWN(20),
        DPAD_LEFT(21),
        DPAD_RIGHT(22),
        DPAD_CENTER(23),
        VOLUME_UP(24),
        VOLUME_DOWN(25),
        POWER(26),
        CAMERA(27),
        CLEAR(28),
        COMMA(55),
        PERIOD(56),
        ALT_LEFT(57),
        ALT_RIGHT(58),
        SHIFT_LEFT(59),
        SHIFT_RIGHT(60),
        TAB(61),
        SPACE(62),
        ENTER(66),
        DEL(67),
        FOCUS(80),
        MENU(82),
        NOTIFICATION(83),
        SEARCH(84);
        final int code;

        AndroidKey(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}
