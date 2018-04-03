#!/bin/sh
java -Xmx768m -cp $(readlink -f litedevtools.jar) org.rh.ldt.ui.MainUi
