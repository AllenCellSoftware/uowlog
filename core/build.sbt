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

name := "uowlog"

// logLevel in Global := Level.Debug

libraryDependencies += "org.scalactic"  %% "scalactic"       % "3.0.0"
libraryDependencies += "org.scalatest"  %% "scalatest"       % "3.0.0" % "test"
libraryDependencies += "org.slf4j"      %  "slf4j-api"       % "1.7.21"
libraryDependencies += "ch.qos.logback" %  "logback-classic" % "1.1.7"
libraryDependencies += "io.spray"       %% "spray-json"      % "1.3.2"

libraryDependencies += "org.aspectj" % "aspectjweaver" % "1.8.9"
libraryDependencies += "org.aspectj" % "aspectjtools"  % "1.8.9"

libraryDependencies += "com.typesafe.akka" %% "akka-actor"   % "2.4.14"
libraryDependencies += "com.typesafe.akka" %% "akka-remote"  % "2.4.14"
libraryDependencies += "com.typesafe.akka" %% "akka-slf4j"   % "2.4.14"
libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.4.14"

// scalacOptions += "-P:artima-supersafe:config-file:project/supersafe.cfg"

fork in (Test,run) := true
aspectjSettings
AspectjKeys.compileOnly in Aspectj := true
publishArtifact in Test := true

javaOptions in run <++= AspectjKeys.weaverOptions in Aspectj
javaOptions in Test <++= AspectjKeys.weaverOptions in Aspectj
products in Compile <++= products in Aspectj
products in run <<= products in Compile


publishTo := Some("Artifactory Realm" at "https://artifactory.corp.alleninstitute.org/artifactory/sbt-snapshot-local")
credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")
