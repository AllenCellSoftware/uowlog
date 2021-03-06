#!/bin/bash

set -ex

version=`sbt -no-colors -batch 'show version' | tail -1 | sed 's/\[info\][ \t]*//'`

git add build.sbt */lock.sbt
git config --global user.email "nobody@example.com"
git config --global user.name  "TravisCI automated build"
git commit -q -m "Version tagging for version $version"
git tag v$version

set +x

git push -q https://popiel:$GITHUB_TAGGING_TOKEN@github.com/AllenCellSoftware/uowlog.git v$version
