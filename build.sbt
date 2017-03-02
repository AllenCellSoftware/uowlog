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
  bintrayOrganization := Some("allencellsoftware"),
  bintrayRepository := "uowlog",
  organization := "org.uowlog",
version := "1.0.52",
  scalaVersion := "2.12.1",
  crossScalaVersions := Seq("2.11.8", "2.12.1"),

  description  := "Unit Of Work metrics and logging framework for Scala",
  homepage     := Some(new java.net.URL("https://github.com/AllenCellSoftware/uowlog")),

  fork := true,
  publishMavenStyle := true,
 
  startYear := Some(2017),
  licenses := Seq(("Apache-2.0", new java.net.URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))),
  scmInfo := Some(ScmInfo(new java.net.URL("http://github.com/AllenCellSoftware/uowlog/tree/master"), "scm:git:git://github.com/AllenCellSoftware/uowlog.git", Some("scm:git:ssh://github.com:AllenCellSoftware/uowlog.git"))),

  pomExtra := (
    <developers>
      <developer>
        <name>T. Alexander Popiel</name>
        <email>alexp@alleninstitute.org</email>
        <organization>Allen Institute</organization>
        <organizationUrl>http://www.alleninstitute.org</organizationUrl>
      </developer>
    </developers>
  ),

  // Make sure we don't publish repository information
  pomPostProcess := { (node: scala.xml.Node) =>
    node match {
      case elem: scala.xml.Elem if elem.label == "project" => elem.copy(child = elem.child.filter(_.label != "repositories"))
      case _ => node
    }
  }
)

commonSettings

// Disable publication from root project
publishLocal := {}
publish      := {}

lazy val core      = project.settings(commonSettings)
lazy val http      = project.settings(commonSettings).dependsOn(core)
lazy val testkit   = project.settings(commonSettings).dependsOn(core, http)
