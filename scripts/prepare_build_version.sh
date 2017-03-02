#!/bin/bash

set -ex

oldversion=`sbt -no-colors -batch 'show version' | tail -1 | sed 's/\[info\][ \t]*//'`
case $oldversion in
( *-SNAPSHOT ) ;;
( * ) echo Bad version for prepare; version must end in -SNAPSHOT, but was: $version; exit 1;;
esac
versionbase=${oldversion%.*-SNAPSHOT}
versionnum=${oldversion%-SNAPSHOT}
versionnum=${versionnum##*.}
if [ $versionnum -lt 1 ]; then
  echo Bad version for prepare; the last segment of the version number must be greater than 0, but was $versionnum
  exit 1
fi
versionnum=$((versionnum - 1))
git fetch --tags
max=`git tag --list v$versionbase.$versionnum.* | sed 's/.*\.//' | sort -n | tail -1`
max=${max:-0}
newversion=$versionbase.$versionnum.$((max + 1))

echo Building version $newversion

# force git into detached HEAD
git checkout -q HEAD@{0}

perl -i -pe "s/^version := \"$oldversion\"/version := \"$newversion\"/" build.sbt
sbt lock
git add build.sbt lock.sbt
git config --global user.email "nobody@example.com"
git config --global user.name  "TravisCI automated build"
git commit -q -m "Version tagging for version $newversion"

