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
  publishMavenStyle := true
)

commonSettings

// Disable publication from root project
publishLocal := {}
publish      := {}

lazy val core = project.settings(commonSettings)
lazy val http = project.settings(commonSettings).dependsOn(core % "test->test;compile->compile")

