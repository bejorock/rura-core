name := """rura-core"""

version := "1.0"

scalaVersion := "2.11.7"

//javaHome := Some(file("/Library/Java/JavaVirtualMachines/jdk1.8.0_60.jdk/Contents/Home"))

// Change this to another test framework if you prefer
//libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.4" % "test"

// Uncomment to use Akka
//libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.3.11"

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "2.2.4" % "test",
  "org.apache.httpcomponents" % "httpclient" % "4.5.2",
  "org.jsoup" % "jsoup" % "1.8.3",
  "com.typesafe.play" %% "play-json" % "2.5.0",
  "commons-io" % "commons-io" % "2.4",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.4.0",
  "com.typesafe.akka" %% "akka-actor" % "2.4.6",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.6",
  "com.typesafe.akka" %% "akka-slf4j" % "2.4.6",
  "io.kamon" %% "kamon-core" % "0.6.0",
  "io.kamon" %% "kamon-statsd" % "0.6.0",
  "io.kamon" %% "kamon-akka" % "0.6.0",
  "io.kamon" %% "kamon-akka-remote" % "0.6.0",
  //"io.kamon" %% "kamon-log-reporter" % "0.6.0",
  //"io.kamon" %% "kamon-system-metrics" % "0.6.0",
  "org.slf4j" % "slf4j-nop" % "1.7.21",
  "org.mongodb" %% "casbah" % "3.1.1")

scalacOptions in Compile := Vector("-Ywarn-infer-any", "-Ywarn-unused-import")

aspectjSettings

javaOptions in (Test, run) <++= AspectjKeys.weaverOptions in Aspectj

//javaOptions in testOnly <++= AspectjKeys.weaverOptions in Aspectj

fork in (Test, run) := true

//fork in test := true