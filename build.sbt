name := """rura-core"""

organization := "xyz.rura.labs"

version := "0.0.1"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "2.2.4" % "test",
  "org.apache.httpcomponents" % "httpclient" % "4.5.2" exclude("commons-logging", "commons-logging"),
  "org.jsoup" % "jsoup" % "1.8.3",
  "com.typesafe.play" %% "play-json" % "2.5.0",
  "commons-io" % "commons-io" % "2.4",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.4.0",
  "com.typesafe.akka" %% "akka-actor" % "2.4.16",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.16",
  "com.typesafe.akka" %% "akka-slf4j" % "2.4.16",
  "io.kamon" %% "kamon-core" % "0.6.3",
  "io.kamon" %% "kamon-statsd" % "0.6.3",
  "io.kamon" %% "kamon-akka" % "0.6.3",
  "io.kamon" %% "kamon-akka-remote_akka-2.4" % "0.6.3",
  //"io.kamon" %% "kamon-log-reporter" % "0.6.0",
  //"io.kamon" %% "kamon-system-metrics" % "0.6.0",
  //"org.slf4j" % "slf4j-nop" % "1.7.21",
  "org.mongodb" %% "casbah" % "3.1.1",
  "org.slf4j" % "jcl-over-slf4j" % "1.7.21")

scalacOptions in Compile := Vector("-Ywarn-infer-any", "-Ywarn-unused-import")

publishTo := Some(Resolver.file("ranalubis-dropbox", new File(Path.userHome.absolutePath+"/Dropbox/M2")))

aspectjSettings

javaOptions <++= AspectjKeys.weaverOptions in Aspectj

// when you call "sbt run" aspectj weaving kicks in
fork in run := true