#!/usr/bin/env bash

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
set -e

export DEEPX_HOME="$(cd "`dirname "$0"`"/..; pwd)"

. "$DEEPX_HOME"/conf/deepx-env.sh

# Find the java binary
if [ -n "${JAVA_HOME}" ]; then
  RUNNER="${JAVA_HOME}/bin/java"
else
  if [ `command -v java` ]; then
    RUNNER="java"
  else
    echo "JAVA_HOME is not set" >&2
    exit 1
  fi
fi

DEEPX_LIB_DIR=$DEEPX_HOME/lib

num_jars="$(ls -1 "$DEEPX_LIB_DIR" | grep "^deepx.*hadoop.*\.jar$" | wc -l)"
if [ "$num_jars" -eq "0" ]; then
  echo "Failed to find DeepX jar in $DEEPX_LIB_DIR." 1>&2
  exit 1
fi
DEEPX_JARS="$(ls -1 "$DEEPX_LIB_DIR" | grep "^deepx.*hadoop.*\.jar$" || true)"
if [ "$num_jars" -gt "1" ]; then
  echo "Found multiple DeepX jars in $DEEPX_LIB_DIR:" 1>&2
  echo "$DEEPX_LIB_DIR" 1>&2
  echo "Please remove all but one jar." 1>&2
  exit 1
fi

DEEPX_JAR="${DEEPX_LIB_DIR}/${DEEPX_JARS}"
LAUNCH_CLASSPATH=$DEEPX_CLASSPATH

if [ ! -d "$DEEPX_HOME/log" ]; then
  mkdir $DEEPX_HOME/log
fi

nohup "$RUNNER" -cp "$LAUNCH_CLASSPATH" "net.deepcloud.deepx.jobhistory.JobHistoryServer" "$@" >"$DEEPX_HOME/log/DeepX-history-$USER-$HOSTNAME.out" 2>&1 &
sleep 1
