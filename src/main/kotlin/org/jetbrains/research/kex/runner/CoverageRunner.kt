package org.jetbrains.research.kex.runner

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.parser.ParseException
import org.jetbrains.research.kex.UnknownTypeException
import org.jetbrains.research.kex.driver.RandomDriver
import org.jetbrains.research.kex.asm.TraceInstrumenter
import org.jetbrains.research.kex.config.GlobalConfig
import org.jetbrains.research.kex.util.Loggable
import org.jetbrains.research.kex.util.loggerFor
import org.jetbrains.research.kfg.ir.Method as KfgMethod
import org.jetbrains.research.kfg.type.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*

internal val runs = GlobalConfig.getIntValue("runner.runs", 10)

internal fun getClass(type: Type, loader: ClassLoader): Class<*> = when (type) {
    is BoolType -> Boolean::class.java
    is ByteType -> Byte::class.java
    is ShortType -> Short::class.java
    is IntType -> Int::class.java
    is LongType -> Long::class.java
    is CharType -> Char::class.java
    is FloatType -> Float::class.java
    is DoubleType -> Double::class.java
    is ArrayType -> Class.forName(type.getCanonicalDesc())
    is ClassType -> try {
        loader.loadClass(type.`class`.getFullname().replace('/', '.'))
    } catch (e: ClassNotFoundException) {
        ClassLoader.getSystemClassLoader().loadClass(type.`class`.getFullname().replace('/', '.'))
    }
    else -> throw UnknownTypeException("Unknown type $type")
}

internal fun invoke(method: Method, instance: Any?, args: Array<Any?>): Pair<ByteArrayOutputStream, ByteArrayOutputStream> {
    val log = loggerFor("org.jetbrains.research.kex.runner.invoke")
    log.debug("Running $method")
    log.debug("Instance: $instance")
    log.debug("Args: ${args.map { it.toString() }}")

    val output = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()
    if (!method.isAccessible) method.isAccessible = true

    val oldOut = System.out
    val oldErr = System.err
    try {
        System.setOut(PrintStream(output))
        System.setErr(PrintStream(error))

        method.invoke(instance, *args)

        System.setOut(oldOut)
        System.setErr(oldErr)

        log.debug("Invocation output: $output")
        if (error.toString().isNotEmpty()) log.debug("Invocation err: $error")
    } catch (e: InvocationTargetException) {
        System.setOut(oldOut)
        System.setErr(oldErr)
        log.debug("Invocation exception ${e.targetException}")
        throw e
    }
    return output to error
}

class CoverageRunner(val method: KfgMethod, val loader: ClassLoader) : Loggable {
    private val random = RandomDriver()
    private val javaClass: Class<*> = loader.loadClass(method.`class`.getFullname().replace('/', '.'))
    private val javaMethod: java.lang.reflect.Method

    init {
        val argumentTypes = method.desc.args.map { getClass(it, loader) }.toTypedArray()
        javaMethod = javaClass.getDeclaredMethod(method.name, *argumentTypes)
    }

    fun run() = repeat(runs, {
        if (CoverageManager.isBodyCovered(method)) return
        val instance = if (method.isStatic()) null else random.generate(javaClass)
        val args = javaMethod.genericParameterTypes.map { random.generate(it) }.toTypedArray()

        val (outputStream, errorStream, exception) = try {
            val (sout, serr) = invoke(javaMethod, instance, args)
            Triple(sout, serr, null)
        } catch (e: Exception) {
            log.error("Failed when running method $method")
            log.error("Exception: $e")
            Triple(ByteArrayOutputStream(), ByteArrayOutputStream(), e)
        }

        val output = Scanner(ByteArrayInputStream(outputStream.toByteArray()))
        val error = ByteArrayInputStream(errorStream.toByteArray())

        val parser = ActionParser()
        val actions = mutableListOf<Action>()
        val tracePrefix = TraceInstrumenter.tracePrefix
        while (output.hasNextLine()) {
            val line = output.nextLine()
            if (line.startsWith(tracePrefix)) {
                try {
                    val trimmed = line.removePrefix(tracePrefix).drop(1)
                    actions.add(parser.parseToEnd(trimmed))
                } catch (e: ParseException) {
                    log.error("Failed to parse $method output: $e")
                    log.error("Failed line: $line")
                    return
                }
            }
        }
        MethodInfo.parse(actions, exception).forEach { CoverageManager.addInfo(it.method, it) }
    })
}