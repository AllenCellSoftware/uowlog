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
  version      := "1.1-SNAPSHOT",
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

lazy val core = project.in(file("core"))
  .settings(commonSettings)
  .enablePlugins(SbtAspectj)
  .settings(
    name := "uowlog",

    libraryDependencies += "org.scalactic"  %% "scalactic"       % "3.0.0",
    libraryDependencies += "org.scalatest"  %% "scalatest"       % "3.0.0" % "test",
    libraryDependencies += "org.slf4j"      %  "slf4j-api"       % "1.7.21",
    libraryDependencies += "ch.qos.logback" %  "logback-classic" % "1.1.7",
    libraryDependencies += "io.spray"       %% "spray-json"      % "1.3.2",

    libraryDependencies += "org.aspectj" % "aspectjweaver" % "1.8.9",
    libraryDependencies += "org.aspectj" % "aspectjtools"  % "1.8.9",

    libraryDependencies += "com.typesafe.akka" %% "akka-actor"   % "2.4.14",
    libraryDependencies += "com.typesafe.akka" %% "akka-remote"  % "2.4.14",
    libraryDependencies += "com.typesafe.akka" %% "akka-slf4j"   % "2.4.14",
    libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.4.14",

    fork in (Test,run) := true,
    aspectjCompileOnly in Aspectj := true,
    publishArtifact in Test := true,

    javaOptions in run  ++= (aspectjWeaverOptions in Aspectj).value,
    javaOptions in Test ++= (aspectjWeaverOptions in Aspectj).value,
    products in Compile ++= (products in Aspectj).value,
    products in run := (products in Compile).value
  )
lazy val http = project.in(file("http"))
  .settings(commonSettings)
  .enablePlugins(SbtAspectj)
  .settings(
    name := "uowlog-http",

    libraryDependencies += "com.typesafe.akka" %% "akka-http-core"       % "10.0.0",
    libraryDependencies += "com.typesafe.akka" %% "akka-http"            % "10.0.0",
    libraryDependencies += "com.typesafe.akka" %% "akka-http-testkit"    % "10.0.0",

    libraryDependencies += "org.scalactic"  %% "scalactic"       % "3.0.0",
    libraryDependencies += "org.scalatest"  %% "scalatest"       % "3.0.0"  % "test",
    libraryDependencies += "org.scalacheck" %% "scalacheck"      % "1.13.4" % "test",

    fork in (Test,run) := true,
    aspectjCompileOnly in Aspectj := true,
    publishArtifact in Test := true,

    javaOptions in run  ++= (aspectjWeaverOptions in Aspectj).value,
    javaOptions in Test ++= (aspectjWeaverOptions in Aspectj).value,
    products in Compile ++= (products in Aspectj).value,
    products in run := (products in Compile).value
  )
  .dependsOn(core)
lazy val testkit = project.in(file("testkit"))
  .settings(commonSettings)
  .enablePlugins(SbtAspectj)
  .settings(
    name := "uowlog-testkit",

    libraryDependencies += "com.typesafe.akka" %% "akka-http-core"       % "10.0.0",
    libraryDependencies += "com.typesafe.akka" %% "akka-http"            % "10.0.0",
    libraryDependencies += "com.typesafe.akka" %% "akka-http-testkit"    % "10.0.0",

    libraryDependencies += "org.scalactic"  %% "scalactic"       % "3.0.0",
    libraryDependencies += "org.scalatest"  %% "scalatest"       % "3.0.0",
    libraryDependencies += "org.scalacheck" %% "scalacheck"      % "1.13.4" % "test",

    fork in (Test,run) := true,
    aspectjCompileOnly in Aspectj := true,
    publishArtifact in Test := true,

    javaOptions in run  ++= (aspectjWeaverOptions in Aspectj).value,
    javaOptions in Test ++= (aspectjWeaverOptions in Aspectj).value,
    products in Compile ++= (products in Aspectj).value,
    products in run := (products in Compile).value
  )
  .dependsOn(core, http)

lazy val root = project.in(file("."))
  .settings(commonSettings)
  .enablePlugins(ScalaUnidocPlugin, GhpagesPlugin, SbtAspectj)
  .settings(
    name := "uowlog",
    siteSubdirName in ScalaUnidoc := "latest/api",
    addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), siteSubdirName in ScalaUnidoc),
    git.remoteRepo := "git@github.com:allencellsoftware/uowlog.git"
  )
  .aggregate(core, http, testkit)
