import sbt._
import java.io.File
import Dependencies._
import sbtbuildinfo.BuildInfoPlugin.autoImport._
import org.scoverage.coveralls.Imports.CoverallsKeys._

val mainVersion = "v2.0-SNAPSHOT"
maintainer := "Mandar Chandorkar <mandar2812@gmail.com>"
packageSummary := "Scala Library/REPL for Machine Learning Research"
packageDescription := "DynaML is a Scala & JVM Machine Learning toolbox for research, education & industry."

val heapSize = Option(System.getProperty("heap")).getOrElse("4096m")

val dataDirectory = settingKey[File](
  "The directory holding the data files for running example scripts"
)

val scriptsDir = settingKey[File](
  "The directory holding the example scripts"
)

val baseSettings = Seq(
  organization := "io.github.tailhq",
  scalaVersion in ThisBuild := scala,
  //crossScalaVersions in ThisBuild := crossScala,
  resolvers in ThisBuild ++= Seq(
    "jzy3d-releases" at "https://maven.jzy3d.org/releases",
    "Scalaz Bintray Repo" at "https://dl.bintray.com/scalaz/releases",
    "BeDataDriven" at "https://nexus.bedatadriven.com/content/groups/public",
    Resolver.sonatypeRepo("public"),
    Resolver.sonatypeRepo("snapshots"),
    Resolver.typesafeIvyRepo("releases"),
    Resolver.bintrayRepo("cibotech", "public"),
    "jitpack" at "https://jitpack.io"
  ),
  publishTo := sonatypePublishTo.value,
  useGpg := true,
  publishConfiguration := publishConfiguration.value.withOverwrite(true),
  publishLocalConfiguration := publishLocalConfiguration.value
    .withOverwrite(true),
  coverallsTokenFile := Some(".coveralls.yml"),
  autoAPIMappings := true
)

lazy val pipes = (project in file("dynaml-pipes"))
  .settings(baseSettings: _*)
  .settings(libraryDependencies ++= pipesDependencies)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "dynaml-pipes",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "io.github.mandar2812.dynaml.pipes",
    buildInfoUsePackageAsPath := true,
    version := mainVersion
  )

lazy val core = (project in file("dynaml-core"))
  .settings(baseSettings: _*)
  .dependsOn(pipes)
  .settings(libraryDependencies ++= coreDependencies)
  .enablePlugins(JavaAppPackaging, BuildInfoPlugin)
  .settings(
    name := "dynaml-core",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "io.github.mandar2812.dynaml",
    buildInfoUsePackageAsPath := true,
    version := mainVersion
  )

lazy val examples = (project in file("dynaml-examples"))
  .settings(baseSettings: _*)
  .dependsOn(pipes, core)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "dynaml-examples",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "io.github.mandar2812.dynaml.examples",
    buildInfoUsePackageAsPath := true,
    version := mainVersion
  )

lazy val repl = (project in file("dynaml-repl"))
  .enablePlugins(BuildInfoPlugin)
  .settings(baseSettings: _*)
  .settings(
    name := "dynaml-repl",
    version := mainVersion,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "io.github.mandar2812.dynaml.repl",
    buildInfoUsePackageAsPath := true,
    libraryDependencies ++= replDependencies
  )

lazy val tensorflow = (project in file("dynaml-tensorflow"))
  .enablePlugins(BuildInfoPlugin)
  .settings(baseSettings: _*)
  .dependsOn(core, pipes)
  .settings(
    name := "dynaml-tensorflow",
    version := mainVersion,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "io.github.mandar2812.dynaml.tensorflow",
    buildInfoUsePackageAsPath := true,
    libraryDependencies ++= tensorflowDependency
      .map(_.excludeAll(excludeSlf4jBindings: _*))
  )

lazy val notebook = (project in file("dynaml-notebook"))
  .enablePlugins(BuildInfoPlugin)
  .settings(baseSettings: _*)
  .dependsOn(core, examples, pipes, tensorflow)
  .settings(
    name := "dynaml-notebook",
    version := mainVersion,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "io.github.mandar2812.dynaml.jupyter",
    buildInfoUsePackageAsPath := true,
    libraryDependencies ++= notebookDepencencies
      .map(_.excludeAll(excludeSlf4jBindings: _*))
  )

lazy val DynaML = (project in file("."))
  .enablePlugins(
    JavaAppPackaging,
    UniversalPlugin,
    BuildInfoPlugin,
    sbtdocker.DockerPlugin,
    ScalaUnidocPlugin,
    JavaUnidocPlugin
  )
  .settings(baseSettings: _*)
  .dependsOn(core, examples, pipes, tensorflow, repl)
  .settings(
    libraryDependencies ++= dynaServeDependencies,
    name := "DynaML",
    version := mainVersion,
    fork in run := true,
    fork in test := true,
    mainClass in Compile := Some("io.github.mandar2812.dynaml.DynaML"),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "io.github.mandar2812",
    buildInfoUsePackageAsPath := true,
    dataDirectory := new File("data"),
    mappings in Universal ++= dataDirectory.value
      .listFiles()
      .toSeq
      .map(p => p -> s"data/${p.getName}"),
    scriptsDir := new File("scripts"),
    mappings in Universal ++= scriptsDir.value
      .listFiles()
      .toSeq
      .map(p => p -> s"scripts/${p.getName}"),
    mappings in Universal ++= Seq(
      {
        //Initialization script for the DynaML REPL
        val init = (resourceDirectory in Compile).value / "DynaMLInit.scala"
        init -> "conf/DynaMLInit.scala"
      }, {
        val banner = (resourceDirectory in Compile).value / "dynamlBanner.txt"
        banner -> "conf/banner.txt"
      }
    ),
    javaOptions in test ++= Seq(
      "-Dlog4j.debug=true",
      "-Dlog4j.configuration=log4j.properties"
    ),
    javaOptions in Universal ++= Seq(
      // -J params will be added as jvm parameters
      s"-J-Xmx$heapSize",
      "-J-Xms64m",
      "-J-XX:HeapBaseMinAddress=32G"
    ),
    scalacOptions in Universal ++= Seq("-Xlog-implicits"),
    initialCommands in console := """io.github.mandar2812.dynaml.DynaML.main(Array())""",
    dockerfile in docker := {
      val appDir: File = stage.value
      val targetDir    = "/app"

      new Dockerfile {
        from("openjdk:8-jre")
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir, chown = "daemon:daemon")
      }
    },
    imageNames in docker := Seq(
      // Sets the latest tag
      ImageName(s"mandar2812/${name.value.toLowerCase}:latest"),
      // Sets a name with a tag that contains the project version
      ImageName(
        namespace = Some("mandar2812"),
        repository = name.value.toLowerCase,
        tag = Some(version.value)
      )
    )
  )
  .aggregate(core, pipes, examples, repl, tensorflow, notebook)
  .settings(aggregate in publishM2 := true, aggregate in update := false)

lazy val docs = project
  .in(file("dynaml-docs")) // important: it must not be docs/
  .dependsOn(DynaML)
  .enablePlugins(
    MdocPlugin,
    DocusaurusPlugin,
    ScalaUnidocPlugin,
    JavaUnidocPlugin
  )
  .settings(
    mdocVariables := Map(
      "VERSION" -> version.value
    ),
    moduleName := "dynaml-docs",
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(
      DynaML,
      core,
      pipes,
      examples,
      notebook,
      tensorflow,
      repl
    ),
    target in (ScalaUnidoc, unidoc) := (baseDirectory in LocalRootProject).value / "website" / "static" / "api",
    cleanFiles += (target in (ScalaUnidoc, unidoc)).value,
    docusaurusCreateSite := docusaurusCreateSite
      .dependsOn(unidoc in Compile)
      .value,
    docusaurusPublishGhpages := docusaurusPublishGhpages
      .dependsOn(unidoc in Compile)
      .value
  )
