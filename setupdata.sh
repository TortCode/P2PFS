#!/bin/bash -eu
if [[ -d ./data ]]
then
  rm -rf ./data
fi

cp -r ./initdata/ ./data/