#!/bin/sh
FIND=/usr/bin/find
TOOL_PATH=./tools
$FIND $TOOL_PATH/ -type f -size -2048c ! -name '*.html' ! -name '*.txt' ! -name '*.bat' ! -name '*.xml' ! -path '*/ant/*' -exec chmod 0755 {} \;
$FIND $TOOL_PATH/
chmod -R 755 $TOOL_PATH/ant/bin/*
chmod 755 launch.sh
