#!/bin/bash

set -ex

version=`sbt -no-colors -batch 'show version' | tail -1 | sed 's/\[info\] //'`

git tag v$version
git push origin v$version
