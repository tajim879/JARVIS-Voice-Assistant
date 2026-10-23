package com.jarvis.assistant.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Exact local calculator for plain expressions (no internet, instant).
 * Understands + - * / ^ % ! parentheses, sqrt, trig (radians, or degrees with the degree sign),
 * logs, constants pi / e, implicit multiplication like 2(3+4) or 2pi, and digits from
 * Bengali, Hindi, Arabic and other scripts.
 */
object MathEngine {

    private val FUNCTIONS = setOf(
        "sqrt", "cbrt", "abs", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh",
        "ln", "log", "log2", "log10", "exp", "floor", "ceil", "round", "pow", "min", "max", "gcd", "lcm", "ncr", "npr", "mod"
    )

    /** Rewrites symbols and non-ASCII digits so the parser only sees plain ASCII. */
    fun normalize(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            when {
                Character.isDigit(ch) && ch.code > 127 -> sb.append(Character.digit(ch, 10).toString())
                ch == '\u00D7' || ch == '\u2217' || ch == '\u22C5' -> sb.append('*')
                ch == '\u00F7' || ch == '\u2215' -> sb.append('/')
                ch == '\u2212' || ch == '\u2013' -> sb.append('-')
                ch == '\u03C0' -> sb.append("pi")
                ch == '\u221A' -> sb.append("sqrt")
                ch == '\u00B2' -> sb.append("^2")
                ch == '\u00B3' -> sb.append("^3")
                ch == '\u00B0' -> sb.append('\u00B0')
                ch == '[' || ch == '{' -> sb.append('(')
                ch == ']' || ch == '}' -> sb.append(')')
                ch == '=' || ch == '?' -> { /* ignore a trailing "=" or "?" */ }
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim().lowercase(Locale.ROOT).replace("**", "^")
    }

    /** True when the text is probably a pure calculation (so we can answer instantly). */
    fun looksLikeExpression(raw: String): Boolean {
        val s = normalize(raw)
        if (s.isEmpty() || s.none { it.isDigit() }) return false
        if (s.none { it in "+-*/^%!()\u00B0" } && !FUNCTIONS.any { s.contains(it) } && !s.contains("pi")) return false
        val words = Regex("[a-z]+").findAll(s).map { it.value }.toList()
        return words.all { it in FUNCTIONS || it == "pi" || it == "e" || it == "tau" || it == "deg" }
    }

    fun evaluate(raw: String): Double? {
        val s = normalize(raw)
        if (s.isEmpty()) return null
        return try {
            val value = Parser(s).parseAll()
            if (value.isNaN() || value.isInfinite()) null else value
        } catch (e: Exception) {
            null
        }
    }

    fun format(value: Double): String {
        if (value == Math.rint(value) && abs(value) < 1e15) {
            return String.format(Locale.US, "%.0f", value)
        }
        var s = String.format(Locale.US, "%.12g", value)
        if (s.contains('e') || s.contains('E')) return s
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseAll(): Double {
            val v = parseExpr()
            skipWs()
            if (i != s.length) throw IllegalArgumentException("unexpected '" + s[i] + "'")
            return v
        }

        private fun skipWs() {
            while (i < s.length && s[i] == ' ') i++
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                skipWs()
                val c = peek()
                if (c == '+') { i++; v += parseTerm() }
                else if (c == '-') { i++; v -= parseTerm() }
                else return v
            }
        }

        private fun parseTerm(): Double {
            var v = parseUnary()
            while (true) {
                skipWs()
                val c = peek()
                if (c == '*') { i++; v *= parseUnary() }
                else if (c == '/') { i++; v /= parseUnary() }
                else if (c == '%') { i++; v %= parseUnary() }
                else if (c == '(' || c.isLetter()) { v *= parseUnary() } // implicit multiplication
                else return v
            }
        }

        private fun parseUnary(): Double {
            skipWs()
            val c = peek()
            if (c == '+') { i++; return parseUnary() }
            if (c == '-') { i++; return -parseUnary() }
            return parsePower()
        }

        private fun parsePower(): Double {
            val base = parsePostfix()
            skipWs()
            if (peek() == '^') {
                i++
                return base.pow(parseUnary())
            }
            return base
        }

        private fun parsePostfix(): Double {
            var v = parsePrimary()
            while (true) {
                skipWs()
                val c = peek()
                if (c == '!') { i++; v = factorial(v) }
                else if (c == '\u00B0') { i++; v = Math.toRadians(v) }
                else return v
            }
        }

        private fun parsePrimary(): Double {
            skipWs()
            val c = peek()
            if (c == '(') {
                i++
                val v = parseExpr()
                skipWs()
                if (peek() != ')') throw IllegalArgumentException("missing )")
                i++
                return v
            }
            if (c.isDigit() || c == '.') return parseNumber()
            if (c.isLetter()) {
                val start = i
                while (i < s.length && (s[i].isLetter() || (s[i].isDigit() && i > start && s.startsWith("log", start)))) i++
                val name = s.substring(start, i)
                skipWs()
                if (peek() == '(' && name in FUNCTIONS) {
                    i++
                    val args = ArrayList<Double>()
                    skipWs()
                    if (peek() != ')') {
                        args.add(parseExpr())
                        skipWs()
                        while (peek() == ',') {
                            i++
                            args.add(parseExpr())
                            skipWs()
                        }
                    }
                    if (peek() != ')') throw IllegalArgumentException("missing )")
                    i++
                    return callFunction(name, args)
                }
                if (name in FUNCTIONS && (name == "sqrt" || name == "cbrt")) {
                    // "sqrt 9" or the radical sign followed directly by a number
                    return callFunction(name, listOf(parseUnary()))
                }
                return when (name) {
                    "pi" -> Math.PI
                    "e" -> Math.E
                    "tau" -> 2 * Math.PI
                    "deg" -> Math.PI / 180.0
                    else -> throw IllegalArgumentException("unknown '" + name + "'")
                }
            }
            throw IllegalArgumentException("unexpected input")
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && s[i] == 'e' && i + 1 < s.length) {
                var j = i + 1
                if (s[j] == '+' || s[j] == '-') j++
                if (j < s.length && s[j].isDigit()) {
                    while (j < s.length && s[j].isDigit()) j++
                    i = j
                }
            }
            return s.substring(start, i).toDouble()
        }

        private fun factorial(v: Double): Double {
            if (v < 0 || v != Math.rint(v) || v > 170) throw ArithmeticException("factorial")
            var r = 1.0
            for (k in 2..v.toInt()) r *= k
            return r
        }

        private fun gcd(a: Long, b: Long): Long {
            var x = abs(a)
            var y = abs(b)
            while (y != 0L) { val t = x % y; x = y; y = t }
            return x
        }

        private fun callFunction(name: String, a: List<Double>): Double {
            fun one(): Double {
                if (a.size != 1) throw IllegalArgumentException(name + " needs 1 argument")
                return a[0]
            }
            fun two(): Pair<Double, Double> {
                if (a.size != 2) throw IllegalArgumentException(name + " needs 2 arguments")
                return Pair(a[0], a[1])
            }
            return when (name) {
                "sqrt" -> sqrt(one())
                "cbrt" -> cbrt(one())
                "abs" -> abs(one())
                "sin" -> sin(one())
                "cos" -> cos(one())
                "tan" -> tan(one())
                "asin" -> asin(one())
                "acos" -> acos(one())
                "atan" -> atan(one())
                "sinh" -> sinh(one())
                "cosh" -> cosh(one())
                "tanh" -> tanh(one())
                "ln" -> ln(one())
                "log2" -> log2(one())
                "log10" -> log10(one())
                "exp" -> exp(one())
                "floor" -> floor(one())
                "ceil" -> ceil(one())
                "round" -> round(one())
                "log" -> if (a.size == 2) ln(a[0]) / ln(a[1]) else log10(one())
                "pow" -> { val (x, y) = two(); x.pow(y) }
                "min" -> { val (x, y) = two(); min(x, y) }
                "max" -> { val (x, y) = two(); max(x, y) }
                "mod" -> { val (x, y) = two(); x % y }
                "gcd" -> { val (x, y) = two(); gcd(x.toLong(), y.toLong()).toDouble() }
                "lcm" -> {
                    val (x, y) = two()
                    val g = gcd(x.toLong(), y.toLong())
                    if (g == 0L) 0.0 else abs(x.toLong() / g * y.toLong()).toDouble()
                }
                "ncr" -> { val (n, r) = two(); factorial(n) / (factorial(r) * factorial(n - r)) }
                "npr" -> { val (n, r) = two(); factorial(n) / factorial(n - r) }
                else -> throw IllegalArgumentException("unknown function")
            }
        }
    }
}
