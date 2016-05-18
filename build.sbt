name := """rura-core"""

version := "1.0"

scalaVersion := "2.11.7"

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
  "com.typesafe.scala-logging" %% "scala-logging" % "3.4.0")