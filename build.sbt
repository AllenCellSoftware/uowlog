/*
** Copyright 2017 Allen Institute
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
** 
** http://www.apache.org/licenses/LICENSE-2.0
**    
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
** implied. See the License for the specific language governing
** permissions and limitations under the License.
*/    

lazy val commonSettings = Seq(
  organization := "org.uowlog",
  version      := "1.1-SNAPSHOT",
  scalaVersion := "2.12.1",
  crossScalaVersions := Seq("2.11.8", "2.12.1"),

  fork := true,
  publishMavenStyle := true,

  pomExtra := (
    <name>org.uowlog:uowlog</name>
++  <description>Unit Of Work metrics and logging framework for Scala.</description>
++  <url>https://github.com/AllenCellSoftware/uowlog</url>
++  <licenses>
      <license>
        <name>The Apache License, Version 2.0</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      </license>
    </licenses>
++  <developers>
      <developer>
        <name>T. Alexander Popiel</name>
        <email>alexp@alleninstitute.org</email>
        <organization>Allen Institute</organization>
        <organizationUrl>http://www.alleninstitute.org</organizationUrl>
      </developer>
    </developers>
++  <scm>
      <connection>scm:git:git://github.com/AllenCellSoftware/uowlog.git</connection>
      <developerConnection>scm:git:ssh://github.com:AllenCellSoftware/uowlog.git</developerConnection>
      <url>http://github.com/AllenCellSoftware/uowlog/tree/master</url>
    </scm>
  )
)

commonSettings

// Disable publication from root project
publishLocal := {}
publish      := {}

lazy val core      = project.settings(commonSettings)
lazy val http      = project.settings(commonSettings).dependsOn(core)
lazy val testkit   = project.settings(commonSettings).dependsOn(core, http)
