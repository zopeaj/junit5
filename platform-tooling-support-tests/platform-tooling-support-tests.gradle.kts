import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.configurationcache.extensions.capitalized
import org.gradle.jvm.toolchain.internal.NoToolchainAvailableException

plugins {
	id("junitbuild.build-parameters")
	id("junitbuild.kotlin-library-conventions")
	id("junitbuild.testing-conventions")
}

javaLibrary {
	mainJavaVersion = JavaVersion.VERSION_11
}

spotless {
	java {
		target(files(project.java.sourceSets.map { it.allJava }), "projects/**/*.java")
	}
	kotlin {
		target("projects/**/*.kt")
	}
	format("projects") {
		target("projects/**/*.gradle.kts", "projects/**/*.md")
		trimTrailingWhitespace()
		endWithNewline()
	}
}

val thirdPartyJars by configurations.creatingResolvable
val antJars by configurations.creatingResolvable
val mavenDistribution by configurations.creatingResolvable

dependencies {
	implementation(libs.bartholdy) {
		because("manage external tool installations")
	}
	implementation(libs.commons.io) {
		because("moving/deleting directory trees")
	}

	testImplementation(libs.archunit) {
		because("checking the architecture of JUnit 5")
	}
	testImplementation(libs.apiguardian) {
		because("we validate that public classes are annotated")
	}
	testImplementation(libs.groovy4) {
		because("it provides convenience methods to handle process output")
	}
	testImplementation(libs.bndlib) {
		because("parsing OSGi metadata")
	}
	testRuntimeOnly(libs.slf4j.julBinding) {
		because("provide appropriate SLF4J binding")
	}
	testImplementation(libs.ant) {
		because("we reference Ant's main class")
	}
	testImplementation(libs.bundles.xmlunit)

	thirdPartyJars(libs.junit4)
	thirdPartyJars(libs.assertj)
	thirdPartyJars(libs.apiguardian)
	thirdPartyJars(libs.hamcrest)
	thirdPartyJars(libs.opentest4j)
	thirdPartyJars(libs.jimfs)

	antJars(platform(projects.junitBom))
	antJars(libs.bundles.ant)
	antJars(projects.junitPlatformConsoleStandalone)
	antJars(projects.junitPlatformLauncher)
	antJars(projects.junitPlatformReporting)

	mavenDistribution(libs.maven) {
		artifact {
			classifier = "bin"
			type = "zip"
			isTransitive = false
		}
	}
}

val unzipMavenDistribution by tasks.registering(Sync::class) {
	from(zipTree(mavenDistribution.elements.map { it.single() }))
	into(layout.buildDirectory.dir("maven-distribution"))
}

tasks.test {
	// Opt-out via system property: '-Dplatform.tooling.support.tests.enabled=false'
	enabled = System.getProperty("platform.tooling.support.tests.enabled")?.toBoolean() ?: true

	// The following if-block is necessary since Gradle will otherwise
	// always publish all mavenizedProjects even if this "test" task
	// is not executed.
	if (enabled) {

		// All maven-aware projects must be installed, i.e. published to the local repository
		val mavenizedProjects: List<Project> by rootProject
		val tempRepoName: String by rootProject

		(mavenizedProjects + projects.junitBom.dependencyProject)
			.map { project -> project.tasks.named("publishAllPublicationsTo${tempRepoName.capitalized()}Repository") }
			.forEach { dependsOn(it) }
	}

	val tempRepoDir: File by rootProject
	jvmArgumentProviders += MavenRepo(tempRepoDir)
	jvmArgumentProviders += JarPath(project, thirdPartyJars)
	jvmArgumentProviders += JarPath(project, antJars)
	jvmArgumentProviders += MavenDistribution(project, unzipMavenDistribution)

	(options as JUnitPlatformOptions).apply {
		includeEngines("archunit")
	}

	inputs.apply {
		dir("projects").withPathSensitivity(RELATIVE)
		file("${rootDir}/gradle.properties")
		file("${rootDir}/settings.gradle.kts")
		file("${rootDir}/gradlew")
		file("${rootDir}/gradlew.bat")
		dir("${rootDir}/gradle/wrapper").withPathSensitivity(RELATIVE)
		dir("${rootDir}/documentation/src/main").withPathSensitivity(RELATIVE)
		dir("${rootDir}/documentation/src/test").withPathSensitivity(RELATIVE)
	}

	distribution {
		requirements.add("jdk=8")
	}
	jvmArgumentProviders += JavaHomeDir(project, 8, distribution.enabled)
}

class MavenRepo(@get:InputDirectory @get:PathSensitive(RELATIVE) val repoDir: File) : CommandLineArgumentProvider {
	override fun asArguments() = listOf("-Dmaven.repo=$repoDir")
}

class JavaHomeDir(project: Project, @Input val version: Int, testDistributionEnabled: Provider<Boolean>) : CommandLineArgumentProvider {

	@Internal
	val javaLauncher: Property<JavaLauncher> = project.objects.property<JavaLauncher>()
			.value(project.provider {
				try {
					project.javaToolchains.launcherFor {
						languageVersion.set(JavaLanguageVersion.of(version))
					}.get()
				} catch (e: NoToolchainAvailableException) {
					null
				}
			})

	@Internal
	val enabled: Property<Boolean> = project.objects.property<Boolean>().convention(testDistributionEnabled.map { !it })

	override fun asArguments(): List<String> {
		if (!enabled.get()) {
			return emptyList()
		}
		val metadata = javaLauncher.map { it.metadata }
		val javaHome = metadata.map { it.installationPath.asFile.absolutePath }.orNull
		return javaHome?.let { listOf("-Djava.home.$version=$it") } ?: emptyList()
	}
}

class JarPath(project: Project, configuration: Configuration, @Input val key: String = configuration.name) : CommandLineArgumentProvider {
	@get:Classpath
	val files: ConfigurableFileCollection = project.objects.fileCollection().from(configuration)

	override fun asArguments() = listOf("-D${key}=${files.asPath}")
}

class MavenDistribution(project: Project, sourceTask: TaskProvider<*>) : CommandLineArgumentProvider {
	@InputDirectory
	@PathSensitive(RELATIVE)
	val mavenDistribution: DirectoryProperty = project.objects.directoryProperty()
		.value(project.layout.dir(sourceTask.map { it.outputs.files.singleFile.listFiles()!!.single() }))

	override fun asArguments() = listOf("-DmavenDistribution=${mavenDistribution.get().asFile.absolutePath}")
}
