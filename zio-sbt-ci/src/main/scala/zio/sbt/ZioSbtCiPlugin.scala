/*
 * Copyright 2022-2023 dev.zio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.sbt
import scala.annotation.nowarn
import scala.sys.process.*

import io.circe.*
import io.circe.syntax.*
import io.circe.yaml.Printer.{LineBreak, YamlVersion}
import sbt.{Def, io => _, *}

import zio.sbt.githubactions.Step.SingleStep
import zio.sbt.githubactions.*

object ZioSbtCiPlugin extends AutoPlugin {

  import DocsVersioning.SemanticVersioning

  override def requires: Plugins =
    super.requires && ZioSbtEcosystemPlugin

  object autoImport {
    val docsVersioning: SettingKey[DocsVersioning] = settingKey[DocsVersioning]("Docs versioning style")
    val ciEnabledBranches: SettingKey[Seq[String]] = settingKey[Seq[String]]("Publish branch for documentation")
    val parallelTestExecution: SettingKey[Boolean] = settingKey[Boolean]("Parallel Test Execution, default: true")
    val generateGithubWorkflow: TaskKey[Unit]      = taskKey[Unit]("Generate github workflow")
    val sbtBuildOptions: SettingKey[List[String]]  = settingKey[List[String]]("SBT build options")
    val updateReadmeCondition: SettingKey[Option[Condition]] =
      settingKey[Option[Condition]]("condition to update readme")
    val supportedJavaPlatform: SettingKey[Map[String, String]] =
      SettingKey[Map[String, String]]("supported Java platform for each module, default is '8'")
    val supportedScalaVersions: SettingKey[Map[String, Seq[String]]] =
      settingKey[Map[String, Seq[String]]]("list of supported scala versions")
    val javaPlatforms: SettingKey[Seq[String]] =
      settingKey[Seq[String]]("list of supported java platforms, default is 8, 11, 17")
    val checkGithubWorkflow: TaskKey[Unit] = taskKey[Unit]("Make sure if the site.yml file is up-to-date")
    val checkArtifactBuildProcessWorkflowStep: SettingKey[Option[Step]] =
      settingKey[Option[Step]]("Workflow step for checking artifact build process")
    val documentationProject: SettingKey[Option[Project]] = settingKey[Option[Project]]("Documentation project")
    val ciWorkflowName: SettingKey[String]                = settingKey[String]("CI Workflow Name")
    val ciExtraTestSteps: SettingKey[Seq[Step]]           = settingKey[Seq[Step]]("Extra test steps")
    val ciSwapSizeGB: SettingKey[Int]                     = settingKey[Int]("Swap size, default is 0")
  }
  import autoImport.*

  lazy val generateGithubWorkflowTask: Def.Initialize[Task[Unit]] =
    Def.task {
      val workflow = websiteWorkflow(
        workflowName = ciWorkflowName.value,
        ciEnabledBranches = ciEnabledBranches.value,
        parallelTest = parallelTestExecution.value,
        javaPlatforms = javaPlatforms.value,
        scalaVersionMatrix = supportedScalaVersions.value,
        javaPlatformMatrix = supportedJavaPlatform.value,
        sbtBuildOptions = sbtBuildOptions.value,
        docsProjectId = documentationProject.value.map(_.id),
        docsVersioning = docsVersioning.value,
        updateReadmeCondition = updateReadmeCondition.value,
        checkArtifactBuildProcess = checkArtifactBuildProcessWorkflowStep.value,
        extraTestSteps = ciExtraTestSteps.value,
        swapSizeGB = ciSwapSizeGB.value
      )

      val template =
        s"""|# This file was autogenerated using `zio-sbt-ci` plugin via `sbt generateGithubWorkflow` 
            |# task and should be included in the git repository. Please do not edit it manually.
            |
            |$workflow""".stripMargin

      IO.write(new File(s".github/workflows/${ciWorkflowName.value.toLowerCase}.yml"), template)
    }

  override def trigger = noTrigger

  override lazy val buildSettings: Seq[Setting[_]] = {
    Seq(
      ciWorkflowName         := "CI",
      documentationProject   := None,
      ciEnabledBranches      := Seq.empty,
      generateGithubWorkflow := generateGithubWorkflowTask.value,
      docsVersioning         := DocsVersioning.SemanticVersioning,
      checkGithubWorkflow    := checkGithubWorkflowTask.value,
      supportedScalaVersions := Map.empty,
      supportedJavaPlatform  := Map.empty,
      sbtBuildOptions        := List.empty[String],
      updateReadmeCondition  := None,
      parallelTestExecution  := true,
      ciExtraTestSteps       := Seq.empty,
      ciSwapSizeGB           := 0,
      javaPlatforms          := Seq("8", "11", "17"),
      checkArtifactBuildProcessWorkflowStep :=
        Some(
          Step.SingleStep(
            name = "Check artifacts build process",
            run = Some(s"sbt ${sbtBuildOptions.value.mkString(" ")} +publishLocal")
          )
        )
    )
  }

  abstract class DocsVersioning(val npmCommand: String)
  object DocsVersioning {
    object SemanticVersioning extends DocsVersioning("publishToNpm")
    object HashVersioning     extends DocsVersioning("publishHashverToNpm")
  }

  lazy val checkGithubWorkflowTask: Def.Initialize[Task[Unit]] =
    Def.task {
      val _ = generateGithubWorkflow.value

      if ("git diff --exit-code".! == 1) {
        sys.error(
          "The ci.yml workflow is not up-to-date!\n" +
            "Please run `sbt generateGithubWorkflow` and commit new changes."
        )
      }
    }

  @nowarn("msg=detected an interpolated expression")
  def websiteWorkflow(
    workflowName: String,
    ciEnabledBranches: Seq[String] = Seq("main"),
    parallelTest: Boolean = true,
    javaPlatforms: Seq[String] = Seq.empty,
    scalaVersionMatrix: Map[String, Seq[String]] = Map.empty,
    javaPlatformMatrix: Map[String, String] = Map.empty,
    sbtBuildOptions: List[String] = List.empty,
    docsProjectId: Option[String] = None,
    docsVersioning: DocsVersioning = SemanticVersioning,
    updateReadmeCondition: Option[Condition] = None,
    checkArtifactBuildProcess: Option[Step] = None,
    extraTestSteps: Seq[Step] = Seq.empty,
    swapSizeGB: Int = 0
  ): String = {
    val _ = docsProjectId
    object Actions {
      val checkout: ActionRef         = ActionRef("actions/checkout@v3.3.0")
      val `setup-java`: ActionRef     = ActionRef("actions/setup-java@v3.10.0")
      val `setup-node`: ActionRef     = ActionRef("actions/setup-node@v3")
      val `set-swap-space`: ActionRef = ActionRef("pierotofy/set-swap-space@master")
    }

    import Actions.*

    object Steps {
      val Checkout: Step.SingleStep = Step.SingleStep(
        name = "Git Checkout",
        uses = Some(checkout),
        parameters = Map("fetch-depth" -> "0".asJson)
      )

      def SetupJava(version: String = "17"): Step.SingleStep = Step.SingleStep(
        name = "Setup Scala",
        uses = Some(`setup-java`),
        parameters = Map(
          "distribution" -> "temurin".asJson,
          "java-version" -> version.asJson,
          "check-latest" -> true.asJson
        )
      )

      val SetupNodeJs: Step.SingleStep = Step.SingleStep(
        name = "Setup NodeJs",
        uses = Some(`setup-node`),
        parameters = Map(
          "node-version" -> "16.x".asJson,
          "registry-url" -> "https://registry.npmjs.org".asJson
        )
      )

      val Release: Step.SingleStep =
        Step.SingleStep(
          name = "Release",
          run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} ci-release"),
          env = Map(
            "PGP_PASSPHRASE"    -> "${{ secrets.PGP_PASSPHRASE }}",
            "PGP_SECRET"        -> "${{ secrets.PGP_SECRET }}",
            "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
            "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
          )
        )

      val Lint: Step.SingleStep = Step.SingleStep(
        name = "Lint",
        run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} lint")
      )

      val GenerateReadme: Step.SingleStep = Step.SingleStep(
        name = "Generate Readme",
        run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} docs/generateReadme")
      )

      val CheckWebsiteBuildProcess: Step.SingleStep = Step.SingleStep(
        name = "Check website build process",
        run = Some(s"sbt docs/clean; sbt ${sbtBuildOptions.mkString(" ")} docs/buildWebsite")
      )

      val CheckGithubWorkflow: Step.SingleStep = Step.SingleStep(
        name = "Check if the site workflow is up to date",
        run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} checkGithubWorkflow")
      )

      val CheckReadme: Step.SingleStep = Step.SingleStep(
        name = "Check if the README file is up to date",
        run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} docs/checkReadme")
      )

      val SetSwapSpace: Step.SingleStep = Step.SingleStep(
        name = "Set Swap Space",
        uses = Some(`set-swap-space`),
        parameters = Map("swap-size-gb" -> swapSizeGB.asJson)
      )

      val PublishToNpmRegistry: Step.SingleStep =
        Step.SingleStep(
          name = "Publish Docs to NPM Registry",
          run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} docs/${docsVersioning.npmCommand}"),
          env = Map(
            "NODE_AUTH_TOKEN" -> "${{ secrets.NPM_TOKEN }}"
          )
        )
    }

    val ParallelTestJob =
      Job(
        id = "test",
        name = "Test",
        strategy = Some(
          Strategy(
            matrix = Map(
              "java" -> javaPlatforms.toList
            ) ++
              (if (javaPlatformMatrix.isEmpty) {
                 Map("scala-project" -> scalaVersionMatrix.flatMap { case (moduleName, versions) =>
                   versions.map { version =>
                     s"++$version $moduleName"
                   }
                 }.toList)
               } else {
                 def generateScalaProjectJavaPlatform(javaPlatform: String) =
                   s"scala-project-java${javaPlatform}" -> scalaVersionMatrix.filterKeys { p =>
                     (javaPlatformMatrix.getOrElse(p, javaPlatform).toInt <= javaPlatform.toInt)
                   }.flatMap { case (moduleName, versions) =>
                     versions.map { version =>
                       s"++$version $moduleName"
                     }
                   }.toList
                 javaPlatforms.map(jp => generateScalaProjectJavaPlatform(jp))
               }),
            failFast = false
          )
        ),
        steps = (if (swapSizeGB > 0) Seq(Steps.SetSwapSpace) else Seq.empty) ++
          Seq(
            Steps.SetupJava("${{ matrix.java }}"),
            Steps.Checkout,
            if (javaPlatformMatrix.values.toSet.isEmpty) {
              Step.SingleStep(
                name = "Test",
                run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} " ++ "${{ matrix.scala-project }}/test")
              )
            } else {
              Step.StepSequence(
                Seq(
                  Step.SingleStep(
                    name = "Java 8 Tests",
                    condition = Some(Condition.Expression("matrix.java == '8'")),
                    run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} " ++ "${{ matrix.scala-project-java8 }}/test")
                  ),
                  Step.SingleStep(
                    name = "Java 11 Tests",
                    condition = Some(Condition.Expression("matrix.java == '11'")),
                    run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} " ++ "${{ matrix.scala-project-java11 }}/test")
                  ),
                  Step.SingleStep(
                    name = "Java 17 Tests",
                    condition = Some(Condition.Expression("matrix.java == '17'")),
                    run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} " ++ "${{ matrix.scala-project-java17 }}/test")
                  )
                )
              )

            }
          ) ++ extraTestSteps
      )

    val SequentialTestJob = {
      def makeTests(scalaVersion: String) =
        s" ${scalaVersionMatrix.filter { case (_, versions) =>
          versions.contains(scalaVersion)
        }.map(e => e._1 + "/test").mkString(" ")}"

      Job(
        id = "test",
        name = "Test",
        strategy = Some(
          Strategy(
            matrix = Map(
              "java"  -> List("8", "11", "17"),
              "scala" -> scalaVersionMatrix.values.flatten.toSet.toList
            ),
            failFast = false
          )
        ),
        steps = (if (swapSizeGB > 0) Seq(Steps.SetSwapSpace) else Seq.empty) ++
          Seq(
            Steps.SetupJava("${{ matrix.java }}"),
            Steps.Checkout
          ) ++ (if (javaPlatformMatrix.values.toSet.isEmpty) {
                  scalaVersionMatrix.values.toSeq.flatten.distinct.map { scalaVersion: String =>
                    Step.SingleStep(
                      name = "Test",
                      condition = Some(Condition.Expression(s"matrix.scala == '$scalaVersion'")),
                      run = Some(
                        s"sbt ${sbtBuildOptions.mkString(" ")} " ++ "++${{ matrix.scala }}" + makeTests(scalaVersion)
                      )
                    )
                  }
                } else {
                  (for {
                    javaPlatform: String <- Set("8", "11", "17")
                    scalaVersion: String <- scalaVersionMatrix.values.toSeq.flatten.toSet
                    projects =
                      scalaVersionMatrix.filterKeys { p =>
                        javaPlatformMatrix.getOrElse(p, javaPlatform).toInt <= javaPlatform.toInt
                      }.filter { case (_, versions) =>
                        versions.contains(scalaVersion)
                      }.keys
                  } yield
                    if (projects.nonEmpty)
                      Seq(
                        Step.SingleStep(
                          name = "Test",
                          condition = Some(
                            Condition.Expression(s"matrix.java == '$javaPlatform'") && Condition.Expression(
                              s"matrix.scala == '$scalaVersion'"
                            )
                          ),
                          run = Some(
                            s"sbt ${sbtBuildOptions.mkString(" ")} " ++ "++${{ matrix.scala }}" ++ s" ${projects.map(_ + "/test ").mkString(" ")}"
                          )
                        )
                      )
                    else Seq.empty).flatten
                }) ++ extraTestSteps
      )
    }

    import Steps.*

    yaml
      .Printer(
        preserveOrder = true,
        dropNullKeys = true,
        splitLines = false,
        lineBreak = LineBreak.Unix,
        version = YamlVersion.Auto
      )
      .pretty(
        Workflow(
          name = workflowName,
          env = Map(
            // JDK_JAVA_OPTIONS is _the_ env. variable to use for modern Java
            "JDK_JAVA_OPTIONS" -> "-XX:+PrintCommandLineFlags -Xmx6G -Xss4M -XX:+UseG1GC",
            // For Java 8 only (sadly, it is not modern enough for JDK_JAVA_OPTIONS)
            "JVM_OPTS"     -> "-XX:+PrintCommandLineFlags -Xmx6G -Xss4M -XX:+UseG1GC",
            "NODE_OPTIONS" -> "--max_old_space_size=6144"
          ),
          triggers = Seq(
            Trigger.WorkflowDispatch(),
            Trigger.Release(Seq("published")),
            Trigger.Push(branches = ciEnabledBranches.map(Branch.Named)),
            Trigger.PullRequest()
          ),
          jobs = Seq(
            Job(
              id = "build",
              name = "Build",
              continueOnError = true,
              steps = (if (swapSizeGB > 0) Seq(Steps.SetSwapSpace) else Seq.empty) ++
                Seq(
                  Step.StepSequence(
                    checkArtifactBuildProcess match {
                      case Some(artifactBuildProcess) =>
                        Seq(
                          Checkout,
                          SetupJava(),
                          CheckGithubWorkflow,
                          artifactBuildProcess,
                          CheckWebsiteBuildProcess
                        )
                      case None =>
                        Seq(
                          Checkout,
                          SetupJava(),
                          CheckGithubWorkflow,
                          CheckWebsiteBuildProcess
                        )
                    }
                  )
                )
            ),
            Job(
              id = "lint",
              name = "Lint",
              steps = (if (swapSizeGB > 0) Seq(Steps.SetSwapSpace) else Seq.empty) ++
                Seq(
                  Checkout,
                  SetupJava(),
                  Lint
                )
            ),
            if (parallelTest) ParallelTestJob else SequentialTestJob,
            Job(
              id = "ci",
              name = "CI",
              need = Seq("lint", "test", "build"),
              steps = Seq(
                SingleStep(
                  name = "Report Successful CI",
                  run = Some("echo \"ci passed\"")
                )
              )
            ),
            Job(
              id = "release",
              name = "Release",
              need = Seq("build", "lint", "test"),
              condition = Some(Condition.Expression("github.event_name != 'pull_request'")),
              steps = (if (swapSizeGB > 0) Seq(Steps.SetSwapSpace) else Seq.empty) ++
                Seq(
                  Checkout,
                  SetupJava(),
                  Release
                )
            ),
            Job(
              id = "publish-docs",
              name = "Publish Docs",
              need = Seq("release"),
              condition = Some(
                Condition.Expression("github.event_name == 'release'") &&
                  Condition.Expression("github.event.action == 'published'") || Condition.Expression(
                    "github.event_name == 'workflow_dispatch'"
                  )
              ),
              steps = (if (swapSizeGB > 0) Seq(Steps.SetSwapSpace) else Seq.empty) ++
                Seq(
                  Step.StepSequence(
                    Seq(
                      Checkout,
                      SetupJava(),
                      SetupNodeJs,
                      PublishToNpmRegistry
                    )
                  )
                )
            ),
            Job(
              id = "generate-readme",
              name = "Generate README",
              need = Seq("release"),
              condition = updateReadmeCondition orElse Some(
                Condition.Expression("github.event_name == 'push'") ||
                  Condition.Expression("github.event_name == 'release'") &&
                  Condition.Expression("github.event.action == 'published'")
              ),
              steps = (if (swapSizeGB > 0) Seq(Steps.SetSwapSpace) else Seq.empty) ++
                Seq(
                  Step.SingleStep(
                    name = "Git Checkout",
                    uses = Some(checkout),
                    parameters = Map(
                      "ref"         -> "${{ github.head_ref }}".asJson,
                      "fetch-depth" -> "0".asJson
                    )
                  ),
                  SetupJava(),
                  GenerateReadme,
                  Step.SingleStep(
                    name = "Commit Changes",
                    run = Some("""|git config --local user.email "github-actions[bot]@users.noreply.github.com"
                                  |git config --local user.name "github-actions[bot]"
                                  |git add README.md
                                  |git commit -m "Update README.md" || echo "No changes to commit"
                                  |""".stripMargin)
                  ),
                  Step.SingleStep(
                    name = "Create Pull Request",
                    uses = Some(ActionRef("peter-evans/create-pull-request@v4.2.3")),
                    parameters = Map(
                      "title"          -> "Update README.md".asJson,
                      "commit-message" -> "Update README.md".asJson,
                      "branch"         -> "zio-sbt-website/update-readme".asJson,
                      "delete-branch"  -> true.asJson,
                      "body" ->
                        """|Autogenerated changes after running the `sbt docs/generateReadme` command of the [zio-sbt-website](https://zio.dev/zio-sbt) plugin.
                           |
                           |I will automatically update the README.md file whenever there is new change for README.md, e.g.
                           |  - After each release, I will update the version in the installation section.
                           |  - After any changes to the "docs/index.md" file, I will update the README.md file accordingly.""".stripMargin.asJson
                    )
                  )
                )
            )
          )
        ).asJson
      )
  }
}
