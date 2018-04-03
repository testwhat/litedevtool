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

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.ConcurrentHashMap;

public class PathMonitor implements Runnable {
    private static final int EVENT_MIN_INTERVAL_MILLIS = 100;

    interface ChangeEvent {
        void onChange(File file, WatchEvent.Kind<?> event);
    }

    static class PathHandle {
        final Path path;
        final ChangeEvent eventHandler;

        PathHandle(String p, ChangeEvent e) {
            path = new File(p).toPath();
            eventHandler = e;
        }
    }

    private final ConcurrentHashMap<WatchKey, PathHandle> mKeys = new ConcurrentHashMap<>();
    private WatchService mWatchSvc;
    private File mPrevFile;
    private long mPrevEventTime;

    PathMonitor() {
        try {
            mWatchSvc = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            DLog.ex(e);
        }
    }

    public void monitorPath(String path, ChangeEvent handle, WatchEvent.Kind<?>... events) {
        if (mWatchSvc == null) {
            DLog.i("Reject monitor " + path);
            return;
        }
        Path wsPath = FileSystems.getDefault().getPath(path);
        try {
            WatchKey k = wsPath.register(mWatchSvc, events);
            mKeys.put(k, new PathHandle(path, handle));
        } catch (IOException e) {
            DLog.ex(e);
        }
    }

    @Override
    public void run() {
        try (WatchService ws = mWatchSvc) {
            while (ws != null) {
                final WatchKey wk = mWatchSvc.take();
                PathHandle handle = mKeys.get(wk);
                for (WatchEvent<?> event : wk.pollEvents()) {
                    if (handle == null) {
                        continue;
                    }
                    final Path changed = handle.path.resolve((Path) event.context());
                    final File currentFile = changed.toFile();
                    final WatchEvent.Kind<?> currentEventKind = event.kind();

                    if (System.currentTimeMillis() - mPrevEventTime > EVENT_MIN_INTERVAL_MILLIS
                            || !currentFile.equals(mPrevFile)) {
                        handle.eventHandler.onChange(currentFile, currentEventKind);
                    }
                    mPrevFile = currentFile;
                    mPrevEventTime = System.currentTimeMillis();
                }
                if (!wk.reset()) {
                    PathHandle p = mKeys.remove(wk);
                    DLog.i("Unregistered path " + p.path);
                }
            }
        } catch (IOException | InterruptedException e) {
            DLog.ex(e);
        }
        mWatchSvc = null;
    }
}
