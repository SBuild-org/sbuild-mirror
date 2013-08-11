package de.tototec.sbuild

import de.tototec.sbuild.runner.SBuildRunner
import java.io.File

/**
 * EXPERIMENTAL API - Resolve TargetRefs.
 */
object ResolveFiles {

  def apply(targetRefs: TargetRefs)(implicit project: Project): Seq[File] = {

    project.log.log(LogLevel.Debug, "ResolveFiles request: " + targetRefs)
    
    val targetRefFiles = targetRefs.targetRefs.flatMap { targetRef =>
      SBuildRunner.determineRequestedTarget(targetRef, searchInAllProjects = true, supportCamelCaseShortCuts = false) match {
        case None =>
          // not found
          // if an existing file, then proceed.
          targetRef.explicitProto match {
            case None | Some("file") if targetRef.explicitProject == None && Path(targetRef.nameWithoutProto).exists =>
              val fileRef = Path(targetRef.nameWithoutProto)
              project.log.log(LogLevel.Debug, "Resolved to a local file reference: " + fileRef)
              Seq(fileRef)
            case _ =>
              throw new TargetNotFoundException(s"""Could not found target with name "${targetRef}" in project ${project.projectFile}.""")
          }

        case Some(target) =>
          project.log.log(LogLevel.Debug, "About to resolve target: " + target)
          val executedTarget = SBuildRunner.preorderedDependenciesTree(curTarget = target)

          project.log.log(LogLevel.Debug, "Resolved target '" + target + "' to: " + executedTarget)
          executedTarget.targetContext.targetFiles

      }
    }

    project.log.log(LogLevel.Debug, "Resolved files:\n  - " + targetRefFiles.mkString("\n  - "))

    targetRefFiles
  }
}