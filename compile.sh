#!/bin/bash -eu
find ./src/ -type f -name "*.java" > sources.txt && mkdir -p ./out/ && javac -d ./out/ @sources.txt
