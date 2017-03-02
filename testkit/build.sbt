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

name := "uowlog-testkit"

libraryDependencies += "com.typesafe.akka" %% "akka-http-core"       % "10.0.0"
libraryDependencies += "com.typesafe.akka" %% "akka-http"            % "10.0.0"
libraryDependencies += "com.typesafe.akka" %% "akka-http-testkit"    % "10.0.0"

libraryDependencies += "org.scalactic"  %% "scalactic"       % "3.0.0"
libraryDependencies += "org.scalatest"  %% "scalatest"       % "3.0.0"
libraryDependencies += "org.scalacheck" %% "scalacheck"      % "1.13.4" % "test"

fork in (Test,run) := true
aspectjSettings
AspectjKeys.compileOnly in Aspectj := true
publishArtifact in Test := true

javaOptions in run <++= AspectjKeys.weaverOptions in Aspectj
javaOptions in Test <++= AspectjKeys.weaverOptions in Aspectj
products in Compile <++= products in Aspectj
products in run <<= products in Compile

