name := baseDirectory.value.getName

organization := "hobby.wei.c"

version := "2.3.1"

scalaVersion := "2.11.12"

crossScalaVersions := Seq(
  /*"2.11.7", 多余，不需要两个*/
  "2.11.12",
  /*"2.12.2", 有一些编译问题：`the interface is not a direct parent`。*/
  "2.12.12")

javacOptions ++= Seq("-source", "1.7", "-target", "1.7")
// 启用对 java8 lambda 语法的支持。
scalacOptions += "-Xexperimental"

// 解决生成文档报错导致 jitpack.io 出错的问题。
publishArtifact in packageDoc := false

// TODO: 独立使用本库的话，应该启用下面的设置。
//lazy val scalaSettings = Seq(
//  scalaVersion := "2.11.12"
//)
//
//lazy val root = Project(id = "reflow", base = file("."))
//  .dependsOn(/*lang*/)
//  .settings(scalaSettings,
//    aggregate in update := false
//  )

exportJars := true

offline := true

// 如果要用 jitpack 打包的话就加上，打完了再注掉。
resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies ++= Seq(
  // 如果要用 jitpack 打包的话就加上，打完了再注掉。
  // TODO: 独立使用本库的话，应该启用本依赖。
  "com.github.dedge-space" % "annoguard" % "v1.0.5-beta",
  // TODO: 如果 jitpack 打包 2.12.6, 这个包的引入也必须是 2.12.6，切记切记。
  "com.github.dedge-space" % "scala-lang" % "59fa763793",

  // ScalaTest 的标准引用。
  "junit" % "junit" % "[4.12,)" % Test,
  // `3.2.0-SNAP10`会导致`scala.ScalaReflectionException: object org.scalatest.prop.Configuration$ not found`.
  "org.scalatest" %% "scalatest" % "3.2.0-SNAP7" % Test
)

// 如果项目要独立编译，请同时启用这部分。
// Macro Settings
///*
resolvers += Resolver.sonatypeRepo("releases")
addCompilerPlugin("org.scalamacros" % "paradise" % "[2.1.0,)" cross CrossVersion.full)
// https://mvnrepository.com/artifact/org.scala-lang/scala-compiler
libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value
//*/
