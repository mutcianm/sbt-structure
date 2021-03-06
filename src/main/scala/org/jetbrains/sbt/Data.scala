package org.jetbrains.sbt

import java.io.File

import org.jetbrains.sbt.FS._
import sbt.Configuration

import scala.xml.{Elem, Text}

/**
 * @author Pavel Fatin
 */
case class FS(home: File, base: Option[File] = None) {
  def withBase(base: File): FS = copy(base = Some(base))
}

object FS {
  val HomePrefix = "~/"
  val BasePrefix = "./"

  private val Windows = System.getProperty("os.name").startsWith("Win")

  implicit def toRichFile(file: File)(implicit fs: FS) = new {
    def path: String = {
      val home = toPath(fs.home)
      val base = fs.base.map(toPath)

      val path = toPath(file)
      replace(base.map(it => replace(path, it + "/", BasePrefix)).getOrElse(path), home + "/", HomePrefix)
    }

    def absolutePath: String = toPath(file)
  }

  private def replace(path: String, root: String, replacement: String) = {
    val (target, prefix) = if (Windows) (path.toLowerCase, root.toLowerCase) else (path, root)
    if (target.startsWith(prefix)) replacement + path.substring(root.length) else path
  }

  def toPath(file: File) = file.getAbsolutePath.replace('\\', '/')
}

case class StructureData(sbt: String, scala: ScalaData, projects: Seq[ProjectData], repository: Option[RepositoryData], localCachePath: Option[String]) {
  def toXML(home: File): Elem = {
    val fs = new FS(home)

    <structure sbt={sbt}>
      {projects.sortBy(_.base).map(project => project.toXML(fs.withBase(project.base)))}
      {repository.map(_.toXML(fs)).toSeq}
      {localCachePath.map(path => <localCachePath>{path}</localCachePath>).toSeq}
    </structure>
  }
}

case class ProjectData(id: String, name: String, organization: String, version: String, base: File, target: File,
                       build: BuildData, configurations: Seq[ConfigurationData], java: Option[JavaData],
                       scala: Option[ScalaData], android: Option[AndroidData], dependencies: DependencyData,
                       resolvers: Set[ResolverData], play2: Option[Play2Extractor.Play2Data]) {
  def toXML(implicit fs: FS): Elem = {
    <project>
      <id>{id}</id>
      <name>{name}</name>
      <organization>{organization}</organization>
      <version>{version}</version>
      <base>{base.absolutePath}</base>
      <target>{target.path}</target>
      {build.toXML}
      {java.map(_.toXML).toSeq}
      {scala.map(_.toXML).toSeq}
      {android.map(_.toXML).toSeq}
      {configurations.sortBy(_.id).map(_.toXML)}
      {dependencies.toXML}
      {resolvers.map(_.toXML).toSeq}
      {play2.map(_.toXml).toSeq}
    </project>
  }
}

case class BuildData(imports: Seq[String], classes: Seq[File], docs: Seq[File], sources: Seq[File]) {
  def toXML(implicit fs: FS): Elem = {
    <build>
      {imports.map { it =>
        <import>{it}</import>
      }}
      {classes.map(_.path).filter(_.startsWith(FS.HomePrefix)).sorted.map { it =>
        <classes>{it}</classes>
      }}
      {docs.map { it =>
        <docs>{it.path}</docs>
      }}
      {sources.map { it =>
        <sources>{it.path}</sources>
      }}
    </build>
  }
}

case class ConfigurationData(id: String, sources: Seq[DirectoryData], resources: Seq[DirectoryData], classes: File) {
  def toXML(implicit fs: FS): Elem = {
    <configuration id={id}>
      {sources.sortBy(it => (it.managed, it.file)).map { directory =>
          <sources managed={format(directory.managed)}>{directory.file.path}</sources>
      }}
      {resources.sortBy(it => (it.managed, it.file)).map { directory =>
        <resources managed={format(directory.managed)}>{directory.file.path}</resources>
      }}
      <classes>{classes.path}</classes>
    </configuration>
  }

  private def format(b: Boolean) = if (b) Some(Text("true")) else None
}

case class DirectoryData(file: File, managed: Boolean)

case class JavaData(home: Option[File], options: Seq[String]) {
  def toXML(implicit fs: FS): Elem = {
    <java>
      {home.toSeq.map { file =>
        <home>{file.path}</home>
      }}
      {options.map { option =>
        <option>{option}</option>
      }}
    </java>
  }
}

case class ScalaData(version: String, libraryJar: File, compilerJar: File, extraJars: Seq[File], options: Seq[String]) {
  def toXML(implicit fs: FS): Elem = {
    <scala>
      <version>{version}</version>
      <library>{libraryJar.path}</library>
      <compiler>{compilerJar.path}</compiler>
      {extraJars.map { jar =>
        <extra>{jar.path}</extra>
      }}
      {options.map { option =>
        <option>{option}</option>
      }}
    </scala>
  }
}

case class DependencyData(projects: Seq[ProjectDependencyData], modules: Seq[ModuleDependencyData], jars: Seq[JarDependencyData]) {
  def toXML(implicit fs: FS): Seq[Elem] = {
    projects.sortBy(_.project).map(_.toXML) ++
      modules.sortBy(_.id.key).map(_.toXML) ++
      jars.sortBy(_.file).map(_.toXML)
  }
}

case class ProjectDependencyData(project: String, configuration: Option[String]) {
  def toXML: Elem = {
    <project configurations={configuration.map(Text(_))}>{project}</project>
  }
}

case class ModuleDependencyData(id: ModuleIdentifier, configurations: Seq[Configuration]) {
  def toXML: Elem = {
    <module organization={id.organization} name={id.name} revision={id.revision} artifactType={id.artifactType} classifier={id.classifier} configurations={configurations.mkString(";")}/>
  }
}

case class JarDependencyData(file: File, configurations: Seq[Configuration]) {
  def toXML(implicit fs: FS): Elem = {
    <jar configurations={configurations.mkString(";")}>{file.path}</jar>
  }
}

case class ModuleIdentifier(organization: String, name: String, revision: String, artifactType: String, classifier: String) {
  def toXML: Elem = {
    <module organization={organization} name={name} revision={revision} artifactType={artifactType} classifier={classifier}/>
  }

  def key: Iterable[String] = productIterator.toIterable.asInstanceOf[Iterable[String]]
}

case class ModuleData(id: ModuleIdentifier, binaries: Set[File], docs: Set[File], sources: Set[File]) {
  def toXML(implicit fs: FS): Elem = {
    val artifacts =
      binaries.toSeq.sorted.map(it => <jar>{it.path}</jar>) ++
      docs.toSeq.sorted.map(it => <doc>{it.path}</doc>) ++
      sources.toSeq.sorted.map(it => <src>{it.path}</src>)

    id.toXML.copy(child = artifacts.toSeq)
  }
}

case class RepositoryData(modules: Seq[ModuleData]) {
  def toXML(implicit fs: FS): Elem = {
    <repository>
      {modules.sortBy(_.id.key).map(_.toXML)}
    </repository>
  }
}

case class ResolverData(name: String, root: String) {
  def toXML(implicit fs: FS): Elem = <resolver name={name} root={root}/>
}

case class AndroidData(targetVersion: String, manifestPath: String,
                       apkPath: String, resPath: String,
                       assetsPath: String, genPath: String,
                       libsPath: String, isLibrary: Boolean,
                       proguardConfig: Seq[String]) {

  def toXML: Elem = {
    <android>
      <version>{targetVersion}</version>
      <manifest>{manifestPath}</manifest>
      <resources>{resPath}</resources>
      <assets>{assetsPath}</assets>
      <generatedFiles>{genPath}</generatedFiles>
      <nativeLibs>{libsPath}</nativeLibs>
      <apk>{apkPath}</apk>
      <isLibrary>{isLibrary}</isLibrary>
      <proguard>{proguardConfig.map { opt => <option>{opt}</option> }}</proguard>
    </android>
  }
}
