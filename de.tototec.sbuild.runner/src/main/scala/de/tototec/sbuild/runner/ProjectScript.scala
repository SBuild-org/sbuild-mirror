package de.tototec.sbuild.runner

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.net.URL
import java.net.URLClassLoader
import scala.io.BufferedSource
import de.tototec.sbuild.HttpSchemeHandlerBase
import de.tototec.sbuild.OSGiVersion
import de.tototec.sbuild.Path
import de.tototec.sbuild.Project
import de.tototec.sbuild.ResolveResult
import de.tototec.sbuild.SBuildException
import de.tototec.sbuild.SBuildVersion
import de.tototec.sbuild.Util
import de.tototec.sbuild.Logger
import de.tototec.sbuild.ProjectConfigurationException
import de.tototec.sbuild.TargetRef
import de.tototec.sbuild.TargetNotFoundException
import de.tototec.sbuild.ProjectConfigurationException
import de.tototec.sbuild.ProjectConfigurationException
import de.tototec.sbuild.WithinTargetExecution
import de.tototec.sbuild.TargetContextImpl
import java.lang.reflect.Method
import de.tototec.sbuild.BuildFileProject
import scala.util.Try
import java.text.ParseException
import scala.annotation.tailrec
import de.tototec.sbuild.ExportDependencies
import de.tototec.sbuild.TargetRefs
import de.tototec.sbuild.execute.TargetExecutor
import de.tototec.sbuild.execute.InMemoryTransientTargetCache
import de.tototec.sbuild.BuildfileCompilationException
import de.tototec.sbuild.SBuildException
import scala.collection.LinearSeq
import de.tototec.sbuild.CmdlineMonitor
import de.tototec.sbuild.OutputStreamCmdlineMonitor

object ProjectScript {

  val InfoFileName = "sbuild.info.xml"

  case class CachedScalaCompiler(compilerClass: Class[_], compilerMethod: Method, reporterMethod: Method)
  case class CachedExtendedScalaCompiler(compilerClass: Class[_], compilerMethod: Method, reporterMethod: Method, outputMethod: Method, clearOutputMethod: Method)

  /**
   * Drop all caches. For now, this is the Scalac compiler and its ClassLoader.
   */
  def dropCaches { cachedScalaCompiler = None; cachedExtendedScalaCompiler = None }
  /**
   * Cached instance of the Scalac compiler class and its ClassLoader.
   * Using a cache will provide the benefit, that loading is faster and we potentially profit from any JIT-compilation at runtime.
   */
  private var cachedScalaCompiler: Option[CachedScalaCompiler] = None
  private var cachedExtendedScalaCompiler: Option[CachedExtendedScalaCompiler] = None

}

class ProjectScript(_scriptFile: File,
                    sbuildClasspath: Array[String],
                    compileClasspath: Array[String],
                    additionalProjectClasspath: Array[String],
                    compilerPluginJars: Array[String],
                    noFsc: Boolean,
                    monitor: CmdlineMonitor,
                    fileLocker: FileLocker) {

  private[this] val log = Logger[ProjectScript]

  import ProjectScript._

  private[this] val annotationReader = new AnnotationReader()

  def this(scriptFile: File,
           classpathConfig: ClasspathConfig,
           monitor: CmdlineMonitor,
           fileLocker: FileLocker) {
    this(_scriptFile = scriptFile,
      sbuildClasspath = classpathConfig.sbuildClasspath,
      compileClasspath = classpathConfig.compileClasspath,
      additionalProjectClasspath = classpathConfig.projectClasspath,
      compilerPluginJars = classpathConfig.compilerPluginJars,
      noFsc = classpathConfig.noFsc,
      monitor = monitor,
      fileLocker = fileLocker)
  }

  private[this] val scriptFile: File = Path.normalize(_scriptFile)
  require(scriptFile.isFile, "scriptFile must be a file")
  private[this] val projectDir: File = scriptFile.getParentFile

  private[this] val buildTargetDir = ".sbuild";
  private[this] val buildFileTargetDir = ".sbuild/scala/" + scriptFile.getName;

  private[this] val scriptBaseName = scriptFile.getName.endsWith(".scala") match {
    case true => scriptFile.getName.substring(0, scriptFile.getName.length - 6)
    case false =>
      log.debug("Scriptfile name does not end in '.scala'")
      scriptFile.getName
  }
  private[this] lazy val targetBaseDir: File = new File(scriptFile.getParentFile, buildTargetDir)
  private[this] lazy val targetDir: File = new File(scriptFile.getParentFile, buildFileTargetDir)
  private[this] val defaultTargetClassName = scriptBaseName
  private[this] def targetClassFile(targetClassName: String): File = new File(targetDir, targetClassName + ".class")
  // lazy val targetClassFile = new File(targetDir, scriptBaseName + ".class")
  private[this] lazy val infoFile = new File(targetDir, InfoFileName)

  /** The lock file used to synchronize compilation of the build file by multiple processes. */
  private[this] val lockFile = new File(targetBaseDir, "lock/" + scriptFile.getName + ".lock")

  /** File that contains the map from Scala type to the containing source file. */
  val typesToIncludedFilesPropertiesFile: File = new File(targetDir, "analyzedtypes.properties")

  private[this] def checkFile = if (!scriptFile.exists) {
    throw new ProjectConfigurationException(s"Could not find build file: ${scriptFile.getName}\n" +
      s"Searched in: ${scriptFile.getAbsoluteFile.getParent}")
  }

  /**
   * Compile this project script (if necessary) and apply it to the given Project.
   */
  def compileAndExecute(project: Project): Any = try {
    checkFile

    // We get an iterator and convert it to a stream, which will cache all seen lines
    val sourceStream = new BufferedSource(new FileInputStream(scriptFile)).getLines().toStream
    // Now we create an iterator which utilizes the already lazy and potentially cached stream
    def buildScriptIterator = sourceStream.iterator

    val version = annotationReader.findFirstAnnotationSingleValue(buildScriptIterator, "version", "value")
    val osgiVersion = OSGiVersion.parseVersion(version)
    if (osgiVersion.compareTo(new OSGiVersion(SBuildVersion.osgiVersion)) > 0) {
      throw new SBuildException("The buildscript '" + scriptFile + "' requires at least SBuild version: " + version)
    }

    val addCp: Array[String] = readAdditionalClasspath(project, buildScriptIterator) ++ additionalProjectClasspath

    val includes: Map[String, Seq[File]] = readIncludeFiles(project, buildScriptIterator)

    // TODO: also check additional classpath entries 

    def compileWhenNecessary(checkLock: Boolean): String =
      checkInfoFileUpToDate(includes) match {
        case LastRunInfo(true, className, _) => className
        case LastRunInfo(_, _, reason) if !checkLock =>
          // println("Compiling build script " + scriptFile + "...")
          newCompile(sbuildClasspath ++ addCp, includes, reason)
        case LastRunInfo(_, _, reason) =>
          fileLocker.acquire(
            file = lockFile,
            timeoutMsec = 30000,
            processInformation = s"SBuild ${SBuildVersion.osgiVersion} for project file: ${scriptFile}",
            onFirstWait = () =>
              monitor.info(CmdlineMonitor.Default, "Waiting for another SBuild process to release the build file: " + scriptFile),
            onDeleteOrphanLock = () =>
              log.debug("Deleting orphan lock file: " + lockFile)
          ) match {
              case Right(fileLock) => try {
                // println("Compiling build script " + scriptFile + "...")
                compileWhenNecessary(false)
              } finally {
                fileLock.release
              }
              case Left(reason) => {
                throw new BuildfileCompilationException("Buildfile compilation is locked by another process: " + reason)
              }
            }
      }

    val buildClassName = compileWhenNecessary(checkLock = true)

    // Experimental: Attach included files 
    {
      implicit val _p = project
      val includedFiles = includes.flatMap { case (k, v) => v }.map(TargetRef(_)).toSeq
      ExportDependencies("sbuild.project.includes", TargetRefs.fromSeq(includedFiles))
    }

    useExistingCompiled(project, addCp, buildClassName)
  } catch {
    case e: SBuildException =>
      e.buildScript = Some(project.projectFile)
      throw e
  }

  case class LastRunInfo(upToDate: Boolean, targetClassName: String, issues: Option[String] = None)

  def checkInfoFileUpToDate(includes: Map[String, Seq[File]]): LastRunInfo = {
    if (!infoFile.exists()) LastRunInfo(false, defaultTargetClassName)
    else {
      val info = xml.XML.loadFile(infoFile)

      val sourceSize = (info \ "sourceSize").text.toLong
      val sourceLastModified = (info \ "sourceLastModified").text.toLong
      val targetClassName = (info \ "targetClassName").text match {
        case "" | null => defaultTargetClassName
        case x => x
      }
      val targetClassLastModified = (info \ "targetClassLastModified").text.toLong
      val sbuildVersion = (info \ "sbuildVersion").text
      val sbuildOsgiVersion = (info \ "sbuildOsgiVersion").text

      val sbuildVersionMatch = sbuildVersion == SBuildVersion.version && sbuildOsgiVersion == SBuildVersion.osgiVersion

      val classFile = targetClassFile(targetClassName)
      val scriptFileUpToDate = scriptFile.length == sourceSize &&
        scriptFile.lastModified == sourceLastModified &&
        classFile.lastModified == targetClassLastModified &&
        classFile.lastModified >= scriptFile.lastModified

      lazy val includesMatch: Boolean = try {
        val lastIncludes = (info \ "includes" \ "include").map { lastInclude =>
          ((lastInclude \ "path").text, (lastInclude \ "lastModified").text.toLong)
        }.toMap

        val flatIncludes = includes.flatMap { case (key, value) => value }

        flatIncludes.size == lastIncludes.size &&
          flatIncludes.forall { file =>
            lastIncludes.get(file.getPath()) match {
              case Some(time) => file.lastModified == time
              case _ => false
            }
          }
      } catch {
        case e: Exception =>
          log.debug("Could not evaluate up-to-date state of included files.", e)
          false
      }

      LastRunInfo(
        upToDate = sbuildVersionMatch && scriptFileUpToDate && includesMatch,
        targetClassName = targetClassName,
        issues = (sbuildVersionMatch, scriptFileUpToDate, includesMatch) match {
          case (false, _, _) => Some(s"SBuild version changed (${sbuildVersion} -> ${SBuildVersion.version})")
          case (_, false, _) => None
          case (_, _, false) => Some("Includes changed")
          case _ => None
        }
      )
    }
  }

  protected def readIncludeFiles(project: Project, buildScript: => Iterator[String]): Map[String, Seq[File]] = {
    log.debug("About to find include files.")
    val cp = annotationReader.findFirstAnnotationWithVarArgValue(buildScript, annoName = "include", varArgValueName = "value").map(_.values).getOrElse(Array())
    log.debug(if (cp.isEmpty) "No includes files specified." else s"Using ${cp.size} included files:\n - ${cp.mkString("\n - ")}")

    // TODO: specific error message, when fetch or download fails
    resolveViaProject(cp, project, "@include entry")
  }

  protected def resolveViaProject(targets: Seq[String], project: Project, purposeOfEntry: String): Map[String, Seq[File]] = {
    val resolveMonitor = new OutputStreamCmdlineMonitor(Console.out, mode = project.monitor.mode, messagePrefix = "(resolve " + purposeOfEntry + ") ")
    class RequirementsResolver extends BuildFileProject(_projectFile = project.projectFile, monitor = resolveMonitor)
    val initProject: Project = new RequirementsResolver

    targets.map(t => (t -> resolveViaInitProject(t, initProject, purposeOfEntry))).toMap
  }

  protected def resolveViaInitProject(target: String, project: Project, purposeOfEntry: String): Seq[File] = {
    implicit val p: Project = project

    project.determineRequestedTarget(targetRef = TargetRef(target), searchInAllProjects = true, supportCamelCaseShortCuts = false) match {

      case None =>
        // not found
        // if an existing file, then proceed.
        val targetRef = TargetRef.fromString(target)
        targetRef.explicitProto match {
          case None | Some("file") if targetRef.explicitProject == None && Path(targetRef.nameWithoutProto).exists =>
            return Seq(Path(targetRef.nameWithoutProto))
          case _ =>
            throw new TargetNotFoundException(s"""Could not found ${purposeOfEntry} "${target}" in project ${scriptFile}.""")
        }

      case Some(target) =>

        val targetExecutor = new TargetExecutor(
          monitor = project.monitor,
          monitorConfig = TargetExecutor.MonitorConfig(
            executing = CmdlineMonitor.Verbose,
            topLevelSkipped = CmdlineMonitor.Verbose
          )
        )

        // TODO: progress
        val executedTarget = targetExecutor.preorderedDependenciesTree(
          curTarget = target,
          transientTargetCache = Some(new InMemoryTransientTargetCache())
        )
        executedTarget.targetContext.targetFiles
    }
  }

  protected def readAdditionalClasspath(project: Project, buildScript: => Iterator[String]): Array[String] = {
    log.debug("About to find additional classpath entries.")
    val cp = annotationReader.findFirstAnnotationWithVarArgValue(buildScript, annoName = "classpath", varArgValueName = "value").map(_.values).getOrElse(Array())
    log.debug("Using additional classpath entries: " + cp.mkString(", "))
    resolveViaProject(cp, project, "@classpath entry").flatMap { case (key, value) => value }.map { _.getPath }.toArray
  }

  protected def useExistingCompiled(project: Project, classpath: Array[String], className: String): Any = {
    log.debug("Loading compiled version of build script: " + scriptFile)
    val cl = new URLClassLoader(Array(targetDir.toURI.toURL) ++ classpath.map(cp => new File(cp).toURI.toURL), getClass.getClassLoader)
    log.debug("CLassLoader loads build script from URLs: " + cl.asInstanceOf[{ def getURLs: Array[URL] }].getURLs.mkString(", "))
    val clazz: Class[_] = cl.loadClass(className)
    val ctr = clazz.getConstructor(classOf[Project])
    val scriptInstance = ctr.newInstance(project)
    // We assume, that everything is done in constructor, so we are done here
    project.applyPlugins
    scriptInstance
  }

  def clean() {
    Util.delete(targetBaseDir)
  }
  def cleanScala() {
    Util.delete(targetDir)
  }

  protected def newCompile(classpath: Array[String], includes: Map[String, Seq[File]], printReason: Option[String] = None): String = {
    cleanScala()
    targetDir.mkdirs
    monitor.info(CmdlineMonitor.Default,
      (printReason match {
        case None => ""
        case Some(r) => r + ": "
      }) + "Compiling build script: " + scriptFile +
        (if (includes.isEmpty) "" else " and " + includes.size + " included files") +
        "..."
    )

    compile(classpath.mkString(File.pathSeparator), includes)

    val (realTargetClassName, realTargetClassFile) = targetClassFile(defaultTargetClassName) match {
      case classExists if classExists.exists() => (defaultTargetClassName, classExists)
      case _ => ("SBuild", targetClassFile("SBuild"))
    }

    log.debug("Writing info file: " + infoFile)
    val info = <sbuild>
                 <sourceSize>{ scriptFile.length }</sourceSize>
                 <sourceLastModified>{ scriptFile.lastModified }</sourceLastModified>
                 <targetClassName>{ realTargetClassName }</targetClassName>
                 <targetClassLastModified>{ realTargetClassFile.lastModified }</targetClassLastModified>
                 <sbuildVersion>{ SBuildVersion.version }</sbuildVersion>
                 <sbuildOsgiVersion>{ SBuildVersion.osgiVersion }</sbuildOsgiVersion>
                 <includes>
                   {
                     includes.map {
                       case (key, value) =>
                         log.debug(s"""@include("${key}") resolved to ${value.size} files: ${value.mkString(", ")}""")
                         value.map { file =>
                           <include>
                             <path>{ file.getPath }</path>
                             <lastModified>{ file.lastModified }</lastModified>
                           </include>
                         }
                     }
                   }
                 </includes>
               </sbuild>
    val file = new FileWriter(infoFile)
    xml.XML.write(file, info, "UTF-8", true, null)
    file.close

    realTargetClassName
  }

  protected def compile(classpath: String, includes: Map[String, Seq[File]]) {
    val compilerPluginSettings = compilerPluginJars match {
      case Array() => Array[String]()
      case jars => jars.map { jar: String => "-Xplugin:" + jar }
    }
    val params = compilerPluginSettings ++ Array(
      "-P:analyzetypes:outfile=" + typesToIncludedFilesPropertiesFile.getPath(),
      "-classpath", classpath,
      "-deprecation",
      "-g:vars",
      "-d", targetDir.getPath,
      scriptFile.getPath) ++
      (includes.flatMap { case (name, files) => files }.map { _.getPath })

    lazy val lazyCompilerClassloader = {
      log.debug("Using additional classpath for scala compiler: " + compileClasspath.mkString(", "))
      new URLClassLoader(compileClasspath.map { f => new File(f).toURI.toURL }, getClass.getClassLoader)
    }

    def compileWithFsc {
      val compileClient = lazyCompilerClassloader.loadClass("scala.tools.nsc.StandardCompileClient").newInstance
      //      import scala.tools.nsc.StandardCompileClient
      //      val compileClient = new StandardCompileClient
      val compileMethod = compileClient.asInstanceOf[Object].getClass.getMethod("process", Array(classOf[Array[String]]): _*)
      log.debug("Executing CompileClient with args: " + params.mkString(" "))
      val retVal = compileMethod.invoke(compileClient, params).asInstanceOf[Boolean]
      if (!retVal) throw new BuildfileCompilationException("Could not compile build file " + scriptFile.getAbsolutePath + " with CompileClient. See compiler output.")
    }

    def compileWithoutFsc {

      val useExtendedCompiler = true
      if (useExtendedCompiler) {

        val cachedCompiler = ProjectScript.cachedExtendedScalaCompiler match {
          case Some(cached) =>
            log.debug("Reusing cached compiler instance.")
            cached
          case None =>
            val compiler = lazyCompilerClassloader.loadClass("de.tototec.sbuild.scriptcompiler.ScriptCompiler")
            //            val compiler = compilerClass.getConstructor().newInstance()
            val compilerMethod = compiler.getMethod("process", Array(classOf[Array[String]]): _*)
            val reporterMethod = compiler.getMethod("reporter")
            val outputMethod = compiler.getMethod("getRecordedOutput")
            val clearOutputMethod = compiler.getMethod("clearRecordedOutput")
            val cache = CachedExtendedScalaCompiler(compiler, compilerMethod, reporterMethod, outputMethod, clearOutputMethod)
            log.debug("Caching compiler for later use.")
            ProjectScript.cachedExtendedScalaCompiler = Some(cache)
            cache
        }

        log.debug("Executing Scala Compile with args: " + params.mkString(" "))
        val compilerInstance = cachedCompiler.compilerClass.getConstructor().newInstance()

        cachedCompiler.compilerMethod.invoke(compilerInstance, params)
        val reporter = cachedCompiler.reporterMethod.invoke(compilerInstance)
        val hasErrorsMethod = reporter.asInstanceOf[Object].getClass.getMethod("hasErrors")
        val hasErrors = hasErrorsMethod.invoke(reporter).asInstanceOf[Boolean]
        if (hasErrors) {
          val output = cachedCompiler.outputMethod.invoke(compilerInstance).asInstanceOf[Seq[String]]
          cachedCompiler.clearOutputMethod.invoke(compilerInstance)
          throw new BuildfileCompilationException("Could not compile build file " + scriptFile.getAbsolutePath + " with scala compiler.\nCompiler output:\n" + output.mkString("\n"))
        }
        cachedCompiler.clearOutputMethod.invoke(compilerInstance)

      } else {
        val cachedCompiler = ProjectScript.cachedScalaCompiler match {
          case Some(cached) =>
            log.debug("Reusing cached compiler instance.")
            cached
          case None =>
            val compiler = lazyCompilerClassloader.loadClass("scala.tools.nsc.Main")
            val compilerMethod = compiler.getMethod("process", Array(classOf[Array[String]]): _*)
            val reporterMethod = compiler.getMethod("reporter")
            val cache = CachedScalaCompiler(compiler, compilerMethod, reporterMethod)
            log.debug("Caching compiler for later use.")
            ProjectScript.cachedScalaCompiler = Some(cache)
            cache
        }

        log.debug("Executing Scala Compile with args: " + params.mkString(" "))
        cachedCompiler.compilerMethod.invoke(null, params)
        val reporter = cachedCompiler.reporterMethod.invoke(null)
        val hasErrorsMethod = reporter.asInstanceOf[Object].getClass.getMethod("hasErrors")
        val hasErrors = hasErrorsMethod.invoke(reporter).asInstanceOf[Boolean]
        if (hasErrors) throw new BuildfileCompilationException("Could not compile build file " + scriptFile.getAbsolutePath + " with scala compiler. See compiler output.")
      }
    }

    if (noFsc) {
      compileWithoutFsc
    } else {
      try {
        compileWithFsc
      } catch {
        case e: SBuildException => throw e
        case e: Exception =>
          log.debug("Compilation with CompileClient failed. trying non-distributed Scala compiler.")
          compileWithoutFsc
      }
    }
  }

}
