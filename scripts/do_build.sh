#!/bin/bash

set -ex

scripts/prepare_build_version.sh
sbt clean
sbt core/lock    +core/test    +core/packagedArtifacts
sbt http/lock    +http/test    +http/packagedArtifacts
sbt testkit/lock +testkit/test +testkit/packagedArtifacts
scripts/tag_and_push_version.sh
