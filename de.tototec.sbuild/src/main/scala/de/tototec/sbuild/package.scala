package de.tototec

/**
 * =SBuild API Reference=
 *
 * This package contains SBuild core classes and public API.
 *
 * Project Homepage: [[http://sbuild.org]]
 *
 * SBuild is released under the [[http://www.apache.org/licenses/LICENSE-2.0 Apache License, Version 2.0]].
 *
 *
 * ==SBuild API to be used in SBuild build scripts==
 *
 * The following classes form SBUild's public API:
 *
 *  - `[[classpath @classpath]]`
 *  - `[[ExportDependencies]]`
 *  - `[[include @include]]`
 *  - `[[Logger]]`
 *  - `[[Module]]`
 *  - `[[Modules]]`
 *  - `[[Path]]`
 *  - `[[Plugin]]`
 *  - `[[Prop]]`
 *  - `[[SchemeHandler]]`
 *  - `[[SchemeResolver]]`
 *  - `[[SchemeResolverWithDependencies]]`
 *  - `[[SetProp]]`
 *  - `[[Target]]`
 *  - `[[TargetContext]]`
 *  - `[[TargetRef]]`
 *  - `[[TargetRefs]]`
 *  - `[[version @version]]`
 *
 *  The following classes are currently experimental or not part of the public API:
 *  - `[[ResolveFiles]]`
 *  - `[[ResolveResult]]`
 *  - `[[ServiceRegistry]]`
 *
 * Classes and objects, not listed above are candidates to change in succeeding releases.
 * You are encouraged, to use only the API form above in your build scripts.
 *
 * ==Default Scheme Handlers==
 *
 * The following list of `[[SchemeHandler]]` implementations are registered by-default with their default configuration in each SBuild project.
 * To register or overwrite a scheme handler in your project, you should use `[[SchemeHandler$#apply SchemeHandler(scheme, handler)]]`.
 *
 *  - `[[HttpSchemeHandler]]`
 *  - `[[MvnSchemeHandler]]`
 *  - `[[ZipSchemeHandler]]`
 *  - `[[ScanSchemeHandler]]`
 *
 */
package object sbuild extends TargetRefsImplicits {

  implicit def toRichFile(file: java.io.File): RichFile = new RichFile(file)

//  @deprecated("Use internal.OSGiVersion instead", "0.6.0.9002")
//  type OSGiVersion = internal.OSGiVersion

  @deprecated("Use de.tototec.sbuild.internal.ProjectTarget instead.", "0.7.1.9000")
  type ProjectTarget = internal.ProjectTarget

//  @deprecated("No public API of SBuild.", "0.6.0.9002")
//  type Util = internal.Util

}
