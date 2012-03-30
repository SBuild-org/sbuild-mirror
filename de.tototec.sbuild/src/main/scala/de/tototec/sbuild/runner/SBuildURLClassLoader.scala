package de.tototec.sbuild.runner

import java.net.URLClassLoader
import java.net.URL

class SBuildURLClassLoader(urls: Array[URL], parent: ClassLoader) extends URLClassLoader(urls, parent) {

  //  println("urls: " + urls.mkString(","))

  //  override protected def findClass(className: String): Class[_] = {
  //    try {
  //      SBuild.verbose("About to find class: " + className)
  //      val res = super.findClass(className)
  //      SBuild.verbose("Found class: " + res)
  //      res
  //    } catch {
  //      case e =>
  //        SBuild.verbose("Caught a: " + e)
  //        throw e
  //    }
  //  }

  override def addURL(url: URL) {
    SBuild.verbose("About to add an URL: " + url)
    super.addURL(url)
  }

}