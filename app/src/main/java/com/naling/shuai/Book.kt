package com.naling.shuai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * 多功能记账：收入 / 支出 / 分类 / 预算 / 统计，纯本地存储，无广告。
 */
object Book {

    private val DEF_OUT = listOf("餐饮", "交通", "购物", "日用", "房租", "水电", "话费", "医疗", "娱乐", "学习", "其他")
    private val DEF_IN = listOf("工资", "加班费", "奖金", "补贴", "其他")

    class Rec(
        var id: String = "",
        var date: String = "",          // yyyy-MM-dd
        var income: Boolean = false,    // true = 收入
        var cat: String = "餐饮",
        var amount: Double = 0.0,
        var note: String = ""
    )

    private fun sp() = Store.prefs()

    fun money(v: Double): String {
        val a = abs(v)
        return if (abs(a - a.toLong()) < 0.005) a.toLong().toString() else String.format(Locale.CHINA, "%.2f", a)
    }

    fun today(): String = Wage.today()
    fun d(off: Int): String = Wage.d(off)
    fun md(date: String): String = Wage.md(date)
    fun weekday(date: String): String = Wage.weekday(date)

    // ---------------- 分类（可自由增删） ----------------
    fun cats(income: Boolean): MutableList<String> {
        val k = if (income) "b_cat_in" else "b_cat_out"
        val raw = sp().getString(k, null)
        if (raw == null) {
            val l = (if (income) DEF_IN else DEF_OUT).toMutableList()
            saveCats(income, l)
            return l
        }
        val l = mutableListOf<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) l.add(arr.getString(i))
        } catch (_: Exception) {
        }
        if (l.isEmpty()) l.addAll(if (income) DEF_IN else DEF_OUT)
        return l
    }

    fun saveCats(income: Boolean, list: List<String>) {
        val arr = JSONArray()
        for (c in list) arr.put(c)
        sp().edit().putString(if (income) "b_cat_in" else "b_cat_out", arr.toString()).apply()
    }

    fun addCat(income: Boolean, name: String) {
        val c = name.trim()
        if (c.isEmpty()) return
        val l = cats(income)
        if (!l.contains(c)) {
            l.add(c)
            saveCats(income, l)
        }
    }

    fun delCat(income: Boolean, name: String) {
        val l = cats(income)
        l.remove(name)
        saveCats(income, l)
    }

    // ---------------- 记录 ----------------
    fun recs(): MutableList<Rec> {
        val raw = sp().getString("b_recs", "[]") ?: "[]"
        val list = mutableListOf<Rec>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Rec(
                        o.optString("id", ""), o.optString("date", ""),
                        o.optBoolean("in", false), o.optString("cat", "其他"),
                        o.optDouble("amt", 0.0), o.optString("note", "")
                    )
                )
            }
        } catch (_: Exception) {
        }
        list.sortWith(compareByDescending<Rec> { it.date }.thenByDescending { it.amount })
        return list
    }

    private fun saveRecs(list: List<Rec>) {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("id", r.id); o.put("date", r.date); o.put("in", r.income)
            o.put("cat", r.cat); o.put("amt", r.amount); o.put("note", r.note)
            arr.put(o)
        }
        sp().edit().putString("b_recs", arr.toString()).apply()
    }

    fun add(r: Rec) {
        if (r.id.isEmpty()) r.id = "b" + System.currentTimeMillis() + "_" + (0..999).random()
        val l = recs()
        l.add(r)
        saveRecs(l)
    }

    fun upd(r: Rec) {
        val l = recs()
        val i = l.indexOfFirst { it.id == r.id }
        if (i >= 0) l[i] = r else l.add(r)
        saveRecs(l)
    }

    fun del(id: String) {
        saveRecs(recs().filter { it.id != id })
    }

    // ---------------- 统计 ----------------
    fun ym(date: String): String = if (date.length >= 7) date.substring(0, 7) else date

    fun month(off: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.MONTH, off)
        return "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    fun inMonth(list: List<Rec>, ym: String): List<Rec> = list.filter { ym(it.date) == ym }

    fun sumOut(list: List<Rec>): Double = list.filter { !it.income }.sumOf { it.amount }
    fun sumIn(list: List<Rec>): Double = list.filter { it.income }.sumOf { it.amount }

    fun dayOf(date: String): List<Rec> = recs().filter { it.date == date }

    fun dayOut(date: String): Double = dayOf(date).filter { !it.income }.sumOf { it.amount }
    fun dayIn(date: String): Double = dayOf(date).filter { it.income }.sumOf { it.amount }

    /** 分类排行（金额从大到小） */
    fun byCat(list: List<Rec>, income: Boolean): List<Pair<String, Double>> {
        val m = mutableMapOf<String, Double>()
        for (r in list) {
            if (r.income != income) continue
            m[r.cat] = (m[r.cat] ?: 0.0) + r.amount
        }
        return m.entries.map { Pair(it.key, it.value) }.sortedByDescending { it.second }
    }

    /** 近 n 天支出（含今天） */
    fun lastDaysOut(n: Int): List<Pair<String, Double>> {
        val out = mutableListOf<Pair<String, Double>>()
        for (i in (n - 1) downTo 0) {
            val dd = d(i)
            out.add(Pair(dd, dayOut(dd)))
        }
        return out
    }

    // ---------------- 预算 ----------------
    fun budget(): Double = sp().getString("b_budget", "0")!!.toDoubleOrNull() ?: 0.0
    fun setBudget(v: Double) = sp().edit().putString("b_budget", v.toString()).apply()
}
