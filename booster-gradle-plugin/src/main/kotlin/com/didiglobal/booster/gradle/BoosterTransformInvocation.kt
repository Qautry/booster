package com.didiglobal.booster.gradle

import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.Format
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Status.NOTCHANGED
import com.android.build.api.transform.Status.REMOVED
import com.android.build.api.transform.TransformInvocation
import com.android.dex.DexFormat
import com.didiglobal.booster.gradle.util.dex
import com.didiglobal.booster.kotlinx.NCPU
import com.didiglobal.booster.kotlinx.file
import com.didiglobal.booster.kotlinx.green
import com.didiglobal.booster.kotlinx.red
import com.didiglobal.booster.transform.AbstractKlassPool
import com.didiglobal.booster.transform.ArtifactManager
import com.didiglobal.booster.transform.Collector
import com.didiglobal.booster.transform.TransformContext
import com.didiglobal.booster.transform.artifacts
import com.didiglobal.booster.transform.util.CompositeCollector
import com.didiglobal.booster.transform.util.collect
import com.didiglobal.booster.transform.util.transform
import java.io.File
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Represents a delegate of TransformInvocation
 *
 * @author johnsonlee
 */
internal class BoosterTransformInvocation(
        private val delegate: TransformInvocation,
        internal val transform: BoosterTransform
) : TransformInvocation by delegate, TransformContext, ArtifactManager {

    private val project = transform.project

    private val outputs = CopyOnWriteArrayList<File>()

    private val collectors = CopyOnWriteArrayList<Collector<*>>()

    override val name: String = delegate.context.variantName

    override val projectDir: File = project.projectDir

    override val buildDir: File = project.buildDir

    override val temporaryDir: File = delegate.context.temporaryDir

    override val reportsDir: File = File(buildDir, "reports").also { it.mkdirs() }

    override val bootClasspath = delegate.bootClasspath

    override val compileClasspath = delegate.compileClasspath

    override val runtimeClasspath = delegate.runtimeClasspath

    override val artifacts = this

    override val dependencies: Collection<String> by lazy {
        ResolvedArtifactResults(variant).map {
            it.id.displayName
        }
    }

    override val klassPool: AbstractKlassPool = object : AbstractKlassPool(compileClasspath, transform.bootKlassPool) {}

    override val applicationId = delegate.applicationId

    override val originalApplicationId = delegate.originalApplicationId

    override val isDebuggable = variant.buildType.isDebuggable

    override val isDataBindingEnabled = delegate.isDataBindingEnabled

    override fun hasProperty(name: String) = project.hasProperty(name)

    override fun <T> getProperty(name: String, default: T): T = project.getProperty(name, default)

    override fun get(type: String) = variant.artifacts.get(type)

    override fun <R> registerCollector(collector: Collector<R>) {
        this.collectors += collector
    }

    override fun <R> unregisterCollector(collector: Collector<R>) {
        this.collectors -= collector
    }

    internal fun doFullTransform() = doTransform(this::transformFully)

    internal fun doIncrementalTransform() = doTransform(this::transformIncrementally)

    private fun lookAhead(executor: ExecutorService): Set<File> {
        return this.inputs.asSequence().map {
            it.jarInputs + it.directoryInputs
        }.flatten().map { input ->
            executor.submit(Callable {
                input.file.takeIf { file ->
                    file.collect(CompositeCollector(collectors)).isNotEmpty()
                }
            })
        }.mapNotNull {
            it.get()
        }.toSet()
    }

    private fun onPreTransform() {
        transform.transformers.forEach {
            it.onPreTransform(this)
        }
    }

    private fun onPostTransform() {
        transform.transformers.forEach {
            it.onPostTransform(this)
        }
    }

    private fun doTransform(block: (ExecutorService, Set<File>) -> Iterable<Future<*>>) {
        this.outputs.clear()
        this.collectors.clear()

        val executor = Executors.newFixedThreadPool(NCPU)

        this.onPreTransform()

        // Look ahead to determine which input need to be transformed even incremental build
        val outOfDate = this.lookAhead(executor).onEach {
            project.logger.info("✨ ${it.canonicalPath} OUT-OF-DATE ")
        }

        try {
            block(executor, outOfDate).forEach {
                it.get()
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(1, TimeUnit.HOURS)
        }

        this.onPostTransform()

        if (transform.verifyEnabled) {
            this.doVerify()
        }
    }

    private fun transformFully(executor: ExecutorService, @Suppress("UNUSED_PARAMETER") outOfDate: Set<File>) = this.inputs.map {
        it.jarInputs + it.directoryInputs
    }.flatten().map { input ->
        executor.submit {
            val format = if (input is DirectoryInput) Format.DIRECTORY else Format.JAR
            outputProvider?.let { provider ->
                project.logger.info("Transforming ${input.file}")
                input.transform(provider.getContentLocation(input.name, input.contentTypes, input.scopes, format))
            }
        }
    }

    private fun transformIncrementally(executor: ExecutorService, outOfDate: Set<File>) = this.inputs.map { input ->
        input.jarInputs.filter {
            it.status != NOTCHANGED || outOfDate.contains(it.file)
        }.map { jarInput ->
            executor.submit {
                doIncrementalTransform(jarInput)
            }
        } + input.directoryInputs.filter {
            it.changedFiles.isNotEmpty() || outOfDate.contains(it.file)
        }.map { dirInput ->
            executor.submit {
                doIncrementalTransform(dirInput, dirInput.file.toURI())
            }
        }
    }.flatten()

    @Suppress("NON_EXHAUSTIVE_WHEN")
    private fun doIncrementalTransform(jarInput: JarInput) {
        when (jarInput.status) {
            REMOVED -> jarInput.file.delete()
            else -> {
                project.logger.info("Transforming ${jarInput.file}")
                outputProvider?.let { provider ->
                    jarInput.transform(provider.getContentLocation(jarInput.name, jarInput.contentTypes, jarInput.scopes, Format.JAR))
                }
            }
        }
    }

    @Suppress("NON_EXHAUSTIVE_WHEN")
    private fun doIncrementalTransform(dirInput: DirectoryInput, base: URI) {
        dirInput.changedFiles.forEach { (file, status) ->
            when (status) {
                REMOVED -> {
                    project.logger.info("Deleting $file")
                    outputProvider?.let { provider ->
                        provider.getContentLocation(dirInput.name, dirInput.contentTypes, dirInput.scopes, Format.DIRECTORY).parentFile.listFiles()?.asSequence()
                                ?.filter { it.isDirectory }
                                ?.map { File(it, dirInput.file.toURI().relativize(file.toURI()).path) }
                                ?.filter { it.exists() }
                                ?.forEach { it.delete() }
                    }
                    file.delete()
                }
                else -> {
                    project.logger.info("Transforming $file")
                    outputProvider?.let { provider ->
                        val root = provider.getContentLocation(dirInput.name, dirInput.contentTypes, dirInput.scopes, Format.DIRECTORY)
                        val output = File(root, base.relativize(file.toURI()).path)
                        outputs += output
                        file.transform(output) { bytecode ->
                            bytecode.transform()
                        }
                    }
                }
            }
        }
    }

    private fun doVerify() {
        outputs.sortedBy(File::nameWithoutExtension).forEach { output ->
            val out = temporaryDir.file(output.name)
            val rc = out.dex(output, variant.extension.defaultConfig.targetSdkVersion?.apiLevel ?: DexFormat.API_NO_EXTENDED_OPCODES)
            println("${if (rc != 0) red("✗") else green("✓")} $output")
            out.deleteRecursively()
        }
    }

    private fun QualifiedContent.transform(output: File) {
        outputs += output
        this.file.transform(output) { bytecode ->
            bytecode.transform()
        }
    }

    private fun ByteArray.transform(): ByteArray {
        return transform.transformers.fold(this) { bytes, transformer ->
            transformer.transform(this@BoosterTransformInvocation, bytes)
        }
    }
}
