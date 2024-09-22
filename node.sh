#!/bin/bash -eu
TRACKER=$1
NODEID=$2
java -cp out/ pfs.Main "./data/d${NODEID}" $TRACKER
