import sbt._

import scala.xml.Elem

import Keys.{ Classpath, TaskStreams }
import Project.Initialize

import ClasspathPlugin._

object WebStartPlugin extends Plugin {
	//------------------------------------------------------------------------------
	//## configuration objects

	case class GenConfig(
		dname:String,
		validity:Int
	)

	case class KeyConfig(
		keyStore:File,
		storePass:String,
		alias:String,
		keyPass:String,
    tsaUrl:Option[String] = None
	)

	case class JnlpConfig(
		fileName:String,
		descriptor:(String,Seq[JnlpAsset])=>Elem
	)

	case class JnlpAsset(href:String, main:Boolean, size:Long) {
		def toElem:Elem	= <jar href={href} main={main.toString} size={size.toString}/>
	}

	//------------------------------------------------------------------------------
	//## exported

	val webstartKeygen			= taskKey[Unit]("generate a signing key")
	val webstart				= taskKey[File]("complete build, returns the output directory")
  val webstartUseTsa = taskKey[Boolean]("use timestamp authority for signing")
	val webstartOutput			= settingKey[File]("where to put the output files")
	val webstartGenConfig		= settingKey[Option[GenConfig]]("configurations for signing key generation")
	val webstartKeyConfig		= settingKey[Option[KeyConfig]]("configuration for signing keys")
	val webstartJnlpConfigs		= settingKey[Seq[JnlpConfig]]("configurations for jnlp files to create")
	val webstartManifest		= settingKey[Option[File]]("manifest file to be included in jar files")
	val webstartExtras			= taskKey[Traversable[(File,String)]]("extra files to include in the build")

	// webstartJnlp		<<= (Keys.name) { it => it + ".jnlp" },
	lazy val webstartSettings:Seq[Def.Setting[_]]	=
			classpathSettings ++
			Vector(
				webstartKeygen	:=
						keygenTaskImpl(
							streams		= Keys.streams.value,
							genConfig	= webstartGenConfig.value,
							keyConfig	= webstartKeyConfig.value
						),
				webstart		:=
						buildTaskImpl(
							streams		= Keys.streams.value,
							assets		= classpathAssets.value,
							keyConfig	= webstartKeyConfig.value,
              useTsa    = webstartUseTsa.value,
							jnlpConfigs	= webstartJnlpConfigs.value,
							manifest	= webstartManifest.value,
							extras		= webstartExtras.value,
							output		= webstartOutput.value
						),
				webstartOutput		:= Keys.crossTarget.value / "webstart",
				webstartGenConfig	:= None,
				webstartKeyConfig	:= None,
				webstartJnlpConfigs	:= Seq.empty,
				webstartManifest	:= None,
				webstartExtras		:= Seq.empty,
        webstartUseTsa    := webstartKeyConfig.value.exists(_.tsaUrl.isDefined),
				Keys.watchSources	:= Keys.watchSources.value ++ webstartManifest.value.toVector
			)

	//------------------------------------------------------------------------------
	//## tasks

	private def buildTaskImpl(
		streams:TaskStreams,
		assets:Seq[ClasspathAsset],
		keyConfig:Option[KeyConfig],
    useTsa:Boolean,
		jnlpConfigs:Seq[JnlpConfig],
		manifest:Option[File],
		extras:Traversable[(File,String)],
		output:File
	):File	= {
		// BETTER copy and sign fresh jars only unless they did not exist before
		val assetMap	=
				for {
					asset	<- assets
					source	= asset.jar
					target	= output / asset.name
				}
				yield (source, target)

		streams.log info "copying assets"
		// BETTER care about freshness
		val assetsToCopy	= assetMap filter { case (source,target) => source newerThan target }
		val assetsCopied	= IO copy assetsToCopy

		// BETTER care about freshness
		val freshJars	= assetsCopied
		if (freshJars.nonEmpty) {
			if (manifest.isEmpty) {
				streams.log info "missing manifest, leaving jar manifests unchanged"
			}
			manifest foreach { manifest =>
				streams.log info "extending jar manifests"
				freshJars.par foreach { jar =>
					extendManifest(manifest, jar, streams.log)
				}
			}

			if (keyConfig.isEmpty) {
				streams.log info "missing KeyConfig, leaving jar files unsigned"
			}
			keyConfig foreach { keyConfig =>
        keyConfig.tsaUrl match {
          case Some(_)        => streams.log info "signing jars with tsa usage"
          case None if useTsa => sys error "tsa usage enabled but tsa url is not provided"
          case _              => streams.log info "signing jars without using tsa"
        }
				freshJars.par foreach { jar =>
					signAndVerify(keyConfig, useTsa, jar, streams.log)
				}
			}
		}
		else {
			streams.log info "no fresh jars to sign"
		}

		// @see http://download.oracle.com/javase/tutorial/deployment/deploymentInDepth/jnlpFileSyntax.html
		streams.log info "creating jnlp descriptor(s)"
		// main jar must come first
		val sortedAssets	=
				assets sortBy { !_.main } map { cp:ClasspathAsset =>
					JnlpAsset(cp.name, cp.main, cp.jar.length)
				}
		val configFiles:Seq[(JnlpConfig,File)]	= jnlpConfigs map { it => (it, output / it.fileName) }
		configFiles foreach { case (jnlpConfig, jnlpFile) =>
			val xml:Elem	= jnlpConfig descriptor (jnlpConfig.fileName, sortedAssets)
			val str:String	= """<?xml version="1.0" encoding="utf-8"?>""" + "\n" + xml
			IO write (jnlpFile, str)
		}
		val jnlpFiles	= configFiles map { _._2 }

		streams.log info "copying extras"
		val extrasToCopy	= extras map { case (file,path) => (file, output / path) }
		val extrasCopied	= IO copy extrasToCopy

		streams.log info "cleaning up"
		val allFiles	= (output * "*").get.toSet
		val jarFiles	= assetMap map { case (source,target) => target }
		val obsolete	= allFiles -- jarFiles -- extrasCopied -- jnlpFiles
		IO delete obsolete

		output
	}

	private def extendManifest(manifest:File, jar:File, log:Logger) {
		val rc	=
				Process("jar", List(
					"umf",
					manifest.getAbsolutePath,
					jar.getAbsolutePath
				)) ! log
		if (rc != 0)	sys error s"manifest change failed: ${rc}"
	}

	private def signAndVerify(keyConfig:KeyConfig, useTsa:Boolean, jar:File, log:Logger) {
		// sigfile, storetype, provider, providerName
    val tsaUrl = keyConfig.tsaUrl
      .filter(_ => useTsa)
      .map(url => List("-tsa", url))
      .getOrElse(List.empty)

		val rc1	=
        Process("jarsigner", tsaUrl ++ List(
					// "-verbose",
					"-keystore",	keyConfig.keyStore.getAbsolutePath,
					"-storepass",	keyConfig.storePass,
					"-keypass",		keyConfig.keyPass,
					// TODO makes the vm crash???
					// "-signedjar",	jar.getAbsolutePath,
					jar.getAbsolutePath,
					keyConfig.alias
				)) ! log
		if (rc1 != 0)	sys error s"sign failed: ${rc1}"

		val rc2	=
				Process("jarsigner", List(
					"-verify",
					"-keystore",	keyConfig.keyStore.getAbsolutePath,
					"-storepass",	keyConfig.storePass,
					"-keypass",		keyConfig.keyPass,
					jar.getAbsolutePath
				)) ! log
		if (rc2 != 0)	sys error s"verify failed: ${rc2}"
	}

	//------------------------------------------------------------------------------

	private def keygenTaskImpl(
		streams:TaskStreams,
		genConfig:Option[GenConfig],
		keyConfig:Option[KeyConfig]
	) {
		require(genConfig.nonEmpty, s"${webstartGenConfig.key.label} must be set")
		require(keyConfig.nonEmpty, s"${webstartKeyConfig.key.label} must be set")
		for {
			genConfig	<- genConfig
			keyConfig	<- keyConfig
		} {
			streams.log info s"creating webstart key in ${keyConfig.keyStore}"
			genkey(keyConfig, genConfig, streams.log)
		}
	}

	private def genkey(keyConfig:KeyConfig, genConfig:GenConfig, log:Logger) {
		val rc	=
				Process("keytool", List(
					"-genkey",
					"-dname",		genConfig.dname,
					"-validity",	genConfig.validity.toString,
					"-keystore",	keyConfig.keyStore.getAbsolutePath,
					"-storePass",	keyConfig.storePass,
					"-keypass",		keyConfig.keyPass,
					"-alias",		keyConfig.alias
				)) ! log
		if (rc != 0)	sys error s"key gen failed: ${rc}"
	}
}
