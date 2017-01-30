val osName = if (Option(System.getProperty("os.name")).getOrElse("").toLowerCase contains "win") "win" else "unix"
val osArch = System.getProperty("sun.arch.data.model")

lazy val nParallel = {
  val p = System.getProperty("parallel")
  if (p ne null) {
    try {
      p.toInt
    } catch {
      case nfe: NumberFormatException => 1
    }
  } else {
    1
  }
}

lazy val frontendClass = settingKey[String]("The name of the compiler wrapper used to extract stainless trees")

lazy val script = taskKey[Unit]("Generate the stainless Bash script")

lazy val artifactSettings: Seq[Setting[_]] = Seq(
  version := "0.1",
  organization := "ch.epfl.lara",
  scalaVersion := "2.11.8"
)

lazy val commonSettings: Seq[Setting[_]] = artifactSettings ++ Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-unchecked",
    "-feature"
  ),

  scalacOptions in (Compile, doc) ++= Seq("-doc-root-content", baseDirectory.value+"/src/main/scala/root-doc.txt"),

  // TODO: Reenable site.settings
//  site.settings,
//  site.sphinxSupport(),

  unmanagedJars in Runtime += {
    baseDirectory.value / "unmanaged" / s"scalaz3-$osName-$osArch-${scalaBinaryVersion.value}.jar"
  },

  resolvers ++= Seq(
    "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
    "uuverifiers" at "http://logicrunch.it.uu.se:4096/~wv/maven"
  ),

  libraryDependencies ++= Seq(
    //"ch.epfl.lamp" %% "dotty" % "0.1-SNAPSHOT",
    "ch.epfl.lara" %% "inox" % "1.0-SNAPSHOT",
    "ch.epfl.lara" %% "inox" % "1.0-SNAPSHOT" % "test" classifier "tests",
    "org.scalatest" %% "scalatest" % "3.0.1" % "test"
  ),

  concurrentRestrictions in Global += Tags.limit(Tags.Test, nParallel),

  sourcesInBase in Compile := false,

  Keys.fork in run := true,

  testOptions in Test := Seq(Tests.Argument("-oDF")),

  testOptions in IntegrationTest := Seq(Tests.Argument("-oDF"))
)

lazy val commonFrontendSettings: Seq[Setting[_]] = Seq(
  libraryDependencies ++= Seq(
    "ch.epfl.lara" %% "inox" % "1.0-SNAPSHOT" % "it" classifier "tests" classifier "it",
    "org.scalatest" %% "scalatest" % "3.0.1" % "it"  // FIXME: Does this override `% "test"` from commonSettings above?
  ),

  /** 
    * NOTE: IntelliJ seems to have trouble including sources located outside the base directory of an
    *   sbt project. You can temporarily disable the following two lines when importing the project.
    */
  scalaSource       in IntegrationTest := root.base.getAbsoluteFile / "frontends/it/scala",
  resourceDirectory in IntegrationTest := root.base.getAbsoluteFile / "frontends/it/resources",

  sourceGenerators in Compile <+= Def.task {
    val libraryFiles = ((root.base / "frontends" / "library") ** "*.scala").getPaths
    val main = (sourceManaged in Compile).value / "stainless" / "Main.scala"
    val extractFromSourceSig = """def extractFromSource(ctx: inox.Context, compilerOpts: List[String]): (
                                 |    List[xt.UnitDef],
                                 |    Program { val trees: xt.type }
                                 |  )""".stripMargin
    IO.write(main, s"""|package stainless
                       |
                       |import extraction.xlang.{trees => xt}
                       |
                       |object Main extends MainHelpers {
                       |  val libraryFiles = List(
                          ${libraryFiles
      .mkString("\"\"\"", "\"\"\",\n    \"\"\"", "\"\"\"")
      .replaceAll("\\\\" + "u", "\\\\\"\"\"+\"\"\"u")}
                       |  )
                       |
                       |  def extractFromSource(ctx: inox.Context, compilerOpts: List[String]): (
                       |    List[xt.UnitDef],
                       |    Program { val trees: xt.type }
                       |  ) = frontends.${frontendClass.value}(ctx, compilerOpts)
                       |}""".stripMargin)
    Seq(main)
  }
)

val scriptSettings: Seq[Setting[_]] = Seq(
  compile <<= (compile in Compile) dependsOn script,

  clean := {
    clean.value
    val scriptFile = root.base / "bin" / name.value
    if (scriptFile.exists && scriptFile.isFile) {
      scriptFile.delete
    }
  },

  script := {
    val s = streams.value
    try {
      val binDir = root.base / "bin"
      binDir.mkdirs

      val scriptFile = binDir / name.value

      val cps = (managedClasspath in Runtime).value ++
        (unmanagedClasspath in Runtime).value ++
        (internalDependencyClasspath in Runtime).value

      val out = (classDirectory      in Compile).value
      val res = (resourceDirectory   in Compile).value

      if (scriptFile.exists) {
        s.log.info("Regenerating '" + scriptFile.getName + "' script")
        scriptFile.delete
      } else {
        s.log.info("Generating '" + scriptFile.getName + "' script")
      }

      val paths = (res.getAbsolutePath +: out.getAbsolutePath +: cps.map(_.data.absolutePath)).mkString(System.getProperty("path.separator"))
      IO.write(scriptFile, s"""|#!/bin/bash --posix
                               |
                               |SCALACLASSPATH=$paths
                               |
                               |java -Xmx2G -Xms512M -Xss64M -classpath "$${SCALACLASSPATH}" -Dscala.usejavacp=true stainless.Main $$@ 2>&1 | tee -i last.log
                               |""".stripMargin)
      scriptFile.setExecutable(true)
    } catch {
      case e: Throwable =>
        s.log.error("There was an error while generating the script file: " + e.getLocalizedMessage)
    }
  }
)


def ghProject(repo: String, version: String) = RootProject(uri(s"${repo}#${version}"))

//lazy val inox = RootProject(file("../inox"))
lazy val dotty = ghProject("git://github.com/lampepfl/dotty.git", "fb1dbba5e35d1fc7c00250f597b8c796d8c96eda")
lazy val cafebabe = ghProject("git://github.com/psuter/cafebabe.git", "49dce3c83450f5fa0b5e6151a537cc4b9f6a79a6")


lazy val `stainless-core` = (project in file("core"))
  .settings(name := "stainless-core")
  .settings(commonSettings)
//  .dependsOn(inox % "compile->compile;test->test;it->it,test")
  .dependsOn(cafebabe)

def frontendProject(proj: Project, _frontendClass: String): Project = proj
  .dependsOn(`stainless-core`)
  .configs(IntegrationTest)
  .settings(frontendClass := _frontendClass)
  .settings(commonSettings)
  .settings(Defaults.itSettings : _*)
  .settings(commonFrontendSettings)
  .settings(inConfig(IntegrationTest)(Defaults.testTasks ++ Seq(
    logBuffered := (nParallel > 1),
    parallelExecution := (nParallel > 1)
  )) : _*)

lazy val `stainless-scalac` = frontendProject(project in file("frontends/scalac"), "scalac.ScalaCompiler")
  .settings(name := "stainless-scalac", scriptSettings)
  .settings(libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value)

lazy val `stainless-dotty-frontend` = frontendProject(project in file("frontends/dotty"), "dotc.DottyCompiler")
  .settings(name := "stainless-dotty-frontend")
  .dependsOn(dotty % "provided")

lazy val `stainless-dotty` = (project in file("frontends/stainless-dotty"))
  .settings(name := "stainless-dotty", artifactSettings, scriptSettings)
  .dependsOn(`stainless-dotty-frontend`)
  .dependsOn(dotty)  // Should truly depend on dotty, overriding the "provided" modifier above
  .aggregate(`stainless-dotty-frontend`)

lazy val root = (project in file("."))
  .settings(sourcesInBase in Compile := false)
  .dependsOn(`stainless-scalac`, `stainless-dotty`)
  .aggregate(`stainless-core`, `stainless-scalac`, `stainless-dotty`)
