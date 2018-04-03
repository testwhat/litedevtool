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

package org.rh.ldt;

import org.rh.smaliex.LLog;

public class DLog {
    public static void e(String msg) {
        LLog.e(msg);
    }

    public static void ex(Throwable e) {
        LLog.ex(e);
    }

    public static void v(String msg) {
        LLog.v(msg);
    }

    public static void i(String msg) {
        LLog.i(msg);
    }

    public static void i(Object o) {
        LLog.i(o == null ? "null" : o.toString());
    }

    public static void enableVerbose(boolean enable) {
        LLog.VERBOSE = enable;
    }
}
