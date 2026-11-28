package com.jidan.receiving.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 重量。内部是整数，单位 0.01斤，只在接口边界与斤小数互转（开发规格 9.2）。
 * 用整数而不是 Double，是为了让 0.1 + 0.2 这类误差不会混进同重归类与均重取整。
 */
@JvmInline
value class Jin(val cents: Int) : Comparable<Jin> {

    operator fun plus(o: Jin) = Jin(cents + o.cents)
    operator fun minus(o: Jin) = Jin(cents - o.cents)
    override fun compareTo(other: Jin) = cents.compareTo(other.cents)

    /** 接口边界用的斤小数。 */
    fun toDouble(): Double = cents / 100.0

    /** 去掉末尾的 0：46、45.6、15.33（原型的 `kg`）。 */
    fun format(): String {
        val abs = Math.abs(cents)
        val frac = abs % 100
        val sign = if (cents < 0) "-" else ""
        val whole = "$sign${abs / 100}"
        return when {
            frac == 0 -> whole
            frac % 10 == 0 -> "$whole.${frac / 10}"
            else -> "$whole.${frac.toString().padStart(2, '0')}"
        }
    }

    /** 称重框固定 1 位小数，宽度不跳（原型的 `kg1`）；第 2 位小数四舍五入。 */
    fun format1(): String {
        val abs = Math.abs(cents)
        val tenths = (abs + 5) / 10
        // 负数四舍五入到 0.0 时不带负号
        val sign = if (cents < 0 && tenths != 0) "-" else ""
        return "$sign${tenths / 10}.${tenths % 10}"
    }

    override fun toString() = format()

    companion object {
        val ZERO = Jin(0)

        /**斤小数转内部整数，第 3 位起四舍五入；经十进制转换，45.6 不会变成 4559。 */
        fun of(jin: Double) = Jin(BigDecimal.valueOf(jin).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toInt())

        /** 接口里的 斤 文本（如 `"45.6"`）。 */
        fun parse(jin: String) = Jin(BigDecimal(jin.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toInt())
    }
}

fun Iterable<Jin>.sum(): Jin = fold(Jin.ZERO) { acc, k -> acc + k }
