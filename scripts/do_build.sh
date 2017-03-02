#!/bin/bash

set -ex

scripts/make_bintray_credentials.sh
[ "$TRAVIS_BRANCH" = "master" -a "$TRAVIS_PULL_REQUEST" = "false" ] && scripts/prepare_build_version.sh
sbt clean
sbt core/lock    +core/test    +core/packagedArtifacts
sbt http/lock    +http/test    +http/packagedArtifacts
sbt testkit/lock +testkit/test +testkit/packagedArtifacts
[ "$TRAVIS_BRANCH" = "master" -a "$TRAVIS_PULL_REQUEST" = "false" ] && scripts/tag_and_push_version.sh && sbt +publish
