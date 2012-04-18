package de.tototec.sbuild.ant.tasks

import java.io.File

import org.apache.tools.ant.taskdefs.Jar

import de.tototec.sbuild.ant.AntProject
import de.tototec.sbuild.Path
import de.tototec.sbuild.Project

object AntJar {
  def apply(destFile: File = null,
            baseDir: File = null,
            manifest: File = null,
            includes: String = null,
            excludes: String = null)(implicit _project: Project) =
    new AntJar(
      destFile = destFile,
      baseDir = baseDir,
      manifest = manifest,
      includes = includes,
      excludes = excludes
    ).execute
}

class AntJar()(implicit _project: Project) extends Jar {
  setProject(AntProject())

  def this(destFile: File = null,
           baseDir: File = null,
           manifest: File = null,
           includes: String = null,
           excludes: String = null)(implicit _project: Project) {
    this
    if (destFile != null) setDestFile(destFile)
    if (baseDir != null) setBasedir(baseDir)
    if (manifest != null) setManifest(manifest)
    if (includes != null) setIncludes(includes)
    if (excludes != null) setExcludes(excludes)
  }

  def setBaseDir(baseDir: File) = setBasedir(baseDir)

}