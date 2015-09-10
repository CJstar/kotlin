/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.rmi

import java.io.File
import java.io.Serializable
import java.lang.management.ManagementFactory
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.reflect.KMutableProperty1
import kotlin.text.Regex


public val COMPILER_JAR_NAME: String = "kotlin-compiler.jar"
public val COMPILER_SERVICE_RMI_NAME: String = "KotlinJvmCompilerService"
public val COMPILER_DAEMON_CLASS_FQN: String = "org.jetbrains.kotlin.rmi.service.CompileDaemon"
public val COMPILE_DAEMON_FIND_PORT_ATTEMPTS: Int = 10
public val COMPILE_DAEMON_PORTS_RANGE_START: Int = 17001
public val COMPILE_DAEMON_PORTS_RANGE_END: Int = 18000
public val COMPILE_DAEMON_STARTUP_LOCK_TIMEOUT_MS: Long = 10000L
public val COMPILE_DAEMON_STARTUP_LOCK_TIMEOUT_CHECK_MS: Long = 100L
public val COMPILE_DAEMON_ENABLED_PROPERTY: String = "kotlin.daemon.enabled"
public val COMPILE_DAEMON_JVM_OPTIONS_PROPERTY: String = "kotlin.daemon.jvm.options"
public val COMPILE_DAEMON_OPTIONS_PROPERTY: String = "kotlin.daemon.options"
public val COMPILE_DAEMON_CMDLINE_OPTIONS_PREFIX: String = "--daemon-"
public val COMPILE_DAEMON_STARTUP_TIMEOUT_PROPERTY: String ="kotlin.daemon.startup.timeout"
public val COMPILE_DAEMON_TIMEOUT_INFINITE_S: Int = 0
public val COMPILE_DAEMON_MEMORY_THRESHOLD_INFINITE: Long = 0L

val COMPILER_ID_DIGEST = "MD5"

public fun makeRunFilenameString(ts: String, digest: String, port: String, esc: String = ""): String = "kotlin-daemon$esc.$ts$esc.$digest$esc.$port$esc.run"
public fun makeRunFilenameRegex(ts: String = "[0-9-]+", digest: String = "[0-9a-f]+", port: String = "\\d+"): Regex = makeRunFilenameString(ts, digest, port, esc = "\\").toRegex()


open class PropMapper<C, V, P: KMutableProperty1<C, V>>(val dest: C,
                                                        val prop: P,
                                                        val names: List<String> = listOf(prop.name),
                                                        val fromString: (s: String) -> V,
                                                        val toString: ((v: V) -> String?) = { it.toString() },
                                                        val skipIf: ((v: V) -> Boolean) = { false },
                                                        val mergeDelimiter: String? = null)
{
    open fun toArgs(prefix: String = COMPILE_DAEMON_CMDLINE_OPTIONS_PREFIX): List<String> =
            when {
                skipIf(prop.get(dest)) -> listOf<String>()
                mergeDelimiter != null -> listOf( listOf(prefix + names.first(), toString(prop.get(dest))).filterNotNull().joinToString(mergeDelimiter))
                else -> listOf(prefix + names.first(), toString(prop.get(dest))).filterNotNull()
            }
    open fun apply(s: String) = prop.set(dest, fromString(s))
}

class StringPropMapper<C, P: KMutableProperty1<C, String>>(dest: C,
                                                           prop: P,
                                                           names: List<String> = listOf(),
                                                           fromString: ((String) -> String) = { it },
                                                           toString: ((String) -> String?) = { it.toString() },
                                                           skipIf: ((String) -> Boolean) = { it.isEmpty() },
                                                           mergeDelimiter: String? = null)
: PropMapper<C, String, P>(dest = dest, prop = prop, names = if (names.any()) names else listOf(prop.name),
                            fromString = fromString,  toString = toString, skipIf = skipIf, mergeDelimiter = mergeDelimiter)

class BoolPropMapper<C, P: KMutableProperty1<C, Boolean>>(dest: C, prop: P, names: List<String> = listOf())
    : PropMapper<C, Boolean, P>(dest = dest, prop = prop, names = if (names.any()) names else listOf(prop.name),
                                fromString = { true },  toString = { null }, skipIf = { !prop.get(dest) })

class RestPropMapper<C, P: KMutableProperty1<C, MutableCollection<String>>>(dest: C, prop: P)
    : PropMapper<C, MutableCollection<String>, P>(dest = dest, prop = prop, toString = { null }, fromString = { arrayListOf() })
{
    override fun toArgs(prefix: String): List<String> = prop.get(dest).map { prefix + it }
    override fun apply(s: String) = add(s)
    fun add(s: String) { prop.get(dest).add(s) }
}


inline fun <T, R: Any> Iterable<T>.firstMapOrNull(mappingPredicate: (T) -> Pair<Boolean, R?>): R? {
    for (element in this) {
        val (found, mapped) = mappingPredicate(element)
        if (found) return mapped
    }
    return null
}


fun Iterable<String>.filterExtractProps(propMappers: List<PropMapper<*,*,*>>, prefix: String, restParser: RestPropMapper<*,*>? = null) : Iterable<String>  {

    val iter = iterator()
    val rest = arrayListOf<String>()

    while (iter.hasNext()) {
        val param = iter.next()
        val (propMapper, matchingOption) = propMappers.firstMapOrNull {
            val name = if (it !is RestPropMapper<*,*>) it.names.firstOrNull { param.startsWith(prefix + it) } else null
            Pair(name != null, Pair(it, name))
        } ?: Pair(null, null)

        when {
            propMapper != null -> {
                val optionLength = prefix.length() + matchingOption!!.length()
                when {
                    propMapper is BoolPropMapper<*,*> -> {
                        if (param.length() > optionLength)
                            throw IllegalArgumentException("Invalid switch option '$param', expecting $prefix$matchingOption without arguments")
                        propMapper.apply("")
                    }
                    param.length() > optionLength ->
                        if (param[optionLength] != '=') {
                            if (propMapper.mergeDelimiter == null)
                                throw IllegalArgumentException("Invalid option syntax '$param', expecting $prefix$matchingOption[= ]<arg>")
                            propMapper.apply(param.substring(optionLength))
                        }
                        else {
                            propMapper.apply(param.substring(optionLength + 1))
                        }
                    else -> {
                        if (!iter.hasNext()) throw IllegalArgumentException("Expecting argument for the option $prefix$matchingOption")
                        propMapper.apply(iter.next())
                    }
                }
            }
            restParser != null && param.startsWith(prefix) ->
                restParser.add(param.removePrefix(prefix))
            else -> rest.add(param)
        }
    }
    return rest
}


// TODO: find out how to create more generic variant using first constructor
//fun<C> C.propsToParams() {
//    val kc = C::class
//    kc.constructors.first().
//}



public interface OptionsGroup : Serializable {
    public val mappers: List<PropMapper<*,*,*>>
}

public fun Iterable<String>.filterExtractProps(vararg groups: OptionsGroup, prefix: String) : Iterable<String> =
        filterExtractProps(groups.flatMap { it.mappers }, prefix)


public data class DaemonJVMOptions(
        public var maxMemory: String = "",
        public var maxPermSize: String = "",
        public var reservedCodeCacheSize: String = "",
        public var jvmParams: MutableCollection<String> = arrayListOf()
) : OptionsGroup {

    override val mappers: List<PropMapper<*,*,*>>
        get() = listOf( StringPropMapper(this, ::maxMemory, listOf("Xmx"), mergeDelimiter = ""),
                        StringPropMapper(this, ::maxPermSize, listOf("XX:MaxPermSize"), mergeDelimiter = "="),
                        StringPropMapper(this, ::reservedCodeCacheSize, listOf("XX:ReservedCodeCacheSize"), mergeDelimiter = "="),
                        restMapper)

    val restMapper: RestPropMapper<*,*>
        get() = RestPropMapper(this, ::jvmParams)
}

public data class DaemonOptions(
        public var runFilesPath: String = File(System.getProperty("java.io.tmpdir"), "kotlin_daemon").absolutePath,
        public var autoshutdownMemoryThreshold: Long = COMPILE_DAEMON_MEMORY_THRESHOLD_INFINITE,
        public var autoshutdownIdleSeconds: Int = COMPILE_DAEMON_TIMEOUT_INFINITE_S
) : OptionsGroup {

    override val mappers: List<PropMapper<*, *, *>>
        get() = listOf( PropMapper(this, ::runFilesPath, fromString = { it.trim('"') }),
                        PropMapper(this, ::autoshutdownMemoryThreshold, fromString = { it.toLong() }, skipIf = { it == 0L }),
                        PropMapper(this, ::autoshutdownIdleSeconds, fromString = { it.toInt() }, skipIf = { it == 0 }))
}


fun updateSingleFileDigest(file: File, md: MessageDigest) {
    DigestInputStream(file.inputStream(), md).use {
        val buf = ByteArray(1024)
        while (it.read(buf) != -1) { }
        it.close()
    }
}

fun updateForAllClasses(dir: File, md: MessageDigest) {
    dir.walk().forEach { updateEntryDigest(it, md) }
}

fun updateEntryDigest(entry: File, md: MessageDigest) {
    when {
        entry.isDirectory
            -> updateForAllClasses(entry, md)
        entry.isFile &&
                (entry.getName().endsWith(".class", ignoreCase = true) ||
                entry.getName().endsWith(".jar", ignoreCase = true))
            -> updateSingleFileDigest(entry, md)
        // else skip
    }
}

jvmName("getFilesClasspathDigest_Files")
fun Iterable<File>.getFilesClasspathDigest(): String {
    val md = MessageDigest.getInstance(COMPILER_ID_DIGEST)
    this.forEach { updateEntryDigest(it, md) }
    return md.digest().joinToString("", transform = { "%02x".format(it) })
}

jvmName("getFilesClasspathDigest_Strings")
fun Iterable<String>.getFilesClasspathDigest(): String = map { File(it) }.getFilesClasspathDigest()

fun Iterable<String>.distinctStringsDigest(): String =
    MessageDigest.getInstance(COMPILER_ID_DIGEST)
            .digest(this.distinct().sort().joinToString("").toByteArray())
            .joinToString("", transform = { "%02x".format(it) })


public data class CompilerId(
        public var compilerClasspath: List<String> = listOf(),
        public var compilerDigest: String = "",
        public var compilerVersion: String = ""
        // TODO: checksum
) : OptionsGroup {

    override val mappers: List<PropMapper<*, *, *>>
        get() = listOf( PropMapper(this, ::compilerClasspath, toString = { it.joinToString(File.pathSeparator) }, fromString = { it.trim('"').split(File.pathSeparator)}),
                        StringPropMapper(this, ::compilerDigest),
                        StringPropMapper(this, ::compilerVersion))

    public fun updateDigest() {
        compilerDigest = compilerClasspath.getFilesClasspathDigest()
    }

    companion object {
        public jvmStatic fun makeCompilerId(vararg paths: File): CompilerId = makeCompilerId(paths.asIterable())

        public jvmStatic fun makeCompilerId(paths: Iterable<File>): CompilerId =
                // TODO consider reading version here
                CompilerId(compilerClasspath = paths.map { it.absolutePath }, compilerDigest = paths.getFilesClasspathDigest())
    }
}


public fun isDaemonEnabled(): Boolean = System.getProperty(COMPILE_DAEMON_ENABLED_PROPERTY) != null


public fun configureDaemonLaunchingOptions(opts: DaemonJVMOptions, inheritMemoryLimits: Boolean): DaemonJVMOptions {
    // note: sequence matters, explicit override in COMPILE_DAEMON_JVM_OPTIONS_PROPERTY should be done after inputArguments processing
    if (inheritMemoryLimits)
        ManagementFactory.getRuntimeMXBean().inputArguments.filterExtractProps(opts.mappers, "-")

    System.getProperty(COMPILE_DAEMON_JVM_OPTIONS_PROPERTY)?.let {
        opts.jvmParams.addAll( it.trim('"', '\'')
                                 .split("(?<!\\\\),".toRegex())
                                 .map { it.replace("[\\\\](.)".toRegex(), "$1") }
                                 .filterExtractProps(opts.mappers, "-", opts.restMapper))
    }
    return opts
}

public fun configureDaemonLaunchingOptions(inheritMemoryLimits: Boolean): DaemonJVMOptions =
    configureDaemonLaunchingOptions(DaemonJVMOptions(), inheritMemoryLimits = inheritMemoryLimits)

jvmOverloads public fun configureDaemonOptions(opts: DaemonOptions = DaemonOptions()): DaemonOptions {
    System.getProperty(COMPILE_DAEMON_OPTIONS_PROPERTY)?.let {
        val unrecognized = it.trim('"', '\'').split(",").filterExtractProps(opts.mappers, "")
        if (unrecognized.any())
            throw IllegalArgumentException(
                    "Unrecognized daemon options passed via property $COMPILE_DAEMON_OPTIONS_PROPERTY: " + unrecognized.joinToString(" ") +
                    "\nSupported options: " + opts.mappers.joinToString(", ", transform = { it.names.first() }))
    }
    return opts
}

