package com.lumina.gallery.ai

import android.graphics.Bitmap
import android.graphics.Color

/** Bangla script + Banglish (romanised) hints that expand into English search terms. */
object Hints {
    private val map: Map<String, List<String>> = mapOf(
        // colours
        "লাল" to listOf("red"), "নীল" to listOf("blue"), "সবুজ" to listOf("green"), "হলুদ" to listOf("yellow"),
        "কালো" to listOf("black"), "সাদা" to listOf("white"), "কমলা" to listOf("orange"), "বেগুনি" to listOf("purple"),
        "গোলাপি" to listOf("pink"), "বাদামি" to listOf("brown"), "ধূসর" to listOf("gray"),
        "lal" to listOf("red"), "nil" to listOf("blue"), "sobuj" to listOf("green"), "shobuj" to listOf("green"),
        "holud" to listOf("yellow"), "halud" to listOf("yellow"), "kalo" to listOf("black"), "shada" to listOf("white"),
        "sada" to listOf("white"), "komola" to listOf("orange"), "beguni" to listOf("purple"), "golapi" to listOf("pink"),
        // media kinds
        "ছবি" to listOf("photo", "image", "picture"), "ভিডিও" to listOf("video"), "নথি" to listOf("document", "pdf"),
        "পিডিএফ" to listOf("pdf"), "স্ক্রিনশট" to listOf("screenshot"), "chobi" to listOf("photo", "image"),
        "video" to listOf("video"), "nothi" to listOf("document", "pdf"),
        // nature / things
        "নদী" to listOf("river", "water"), "সমুদ্র" to listOf("sea", "ocean", "beach"), "সাগর" to listOf("sea", "ocean"),
        "পাহাড়" to listOf("mountain", "hill"), "গাছ" to listOf("tree", "plant"), "ফুল" to listOf("flower"),
        "পাখি" to listOf("bird"), "বিড়াল" to listOf("cat"), "কুকুর" to listOf("dog"), "গরু" to listOf("cow"),
        "আকাশ" to listOf("sky"), "সূর্য" to listOf("sun", "sunset"), "রাত" to listOf("night"), "মেঘ" to listOf("cloud"),
        "nodi" to listOf("river", "water"), "shagor" to listOf("sea", "ocean"), "sagor" to listOf("sea", "ocean"),
        "pahar" to listOf("mountain", "hill"), "gach" to listOf("tree", "plant"), "ful" to listOf("flower"),
        "phul" to listOf("flower"), "pakhi" to listOf("bird"), "biral" to listOf("cat"), "kukur" to listOf("dog"),
        "akash" to listOf("sky"), "surjo" to listOf("sun", "sunset"), "megh" to listOf("cloud"),
        // people / life
        "মানুষ" to listOf("person", "people"), "শিশু" to listOf("baby", "child"), "বাচ্চা" to listOf("baby", "child"),
        "খাবার" to listOf("food", "dish"), "গাড়ি" to listOf("car", "vehicle"), "বাড়ি" to listOf("house", "building"),
        "বিয়ে" to listOf("wedding"), "জন্মদিন" to listOf("birthday"), "ভ্রমণ" to listOf("travel", "trip"),
        "পরিবার" to listOf("family"), "বই" to listOf("book"), "রসিদ" to listOf("receipt", "invoice"),
        "টাকা" to listOf("money", "cash"), "manush" to listOf("person", "people"), "khabar" to listOf("food", "dish"),
        "gari" to listOf("car", "vehicle"), "bari" to listOf("house", "building"), "biye" to listOf("wedding"),
        "jonmodin" to listOf("birthday"), "bhromon" to listOf("travel", "trip"), "poribar" to listOf("family"),
        "boi" to listOf("book"), "roshid" to listOf("receipt", "invoice"), "taka" to listOf("money", "cash")
    )

    fun expand(token: String): Set<String> {
        val out = HashSet<String>()
        out.add(token)
        map[token]?.let { out.addAll(it) }
        return out
    }
}

object ColorTools {
    val NAMES = listOf("red", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "brown", "black", "white", "gray")

    fun name(h: Float, s: Float, v: Float): String = when {
        v < 0.16f -> "black"
        s < 0.12f -> if (v > 0.85f) "white" else "gray"
        s < 0.18f && v > 0.9f -> "white"
        h < 15f || h >= 345f -> "red"
        h < 40f -> if (v < 0.65f) "brown" else "orange"
        h < 70f -> "yellow"
        h < 165f -> "green"
        h < 200f -> "teal"
        h < 255f -> "blue"
        h < 290f -> "purple"
        else -> "pink"
    }

    /** Returns (top two colour names, average RGB of the dominant colour). */
    fun analyze(src: Bitmap): Pair<String, Int> {
        val bmp = Bitmap.createScaledBitmap(src, 24, 24, true)
        val px = IntArray(24 * 24)
        bmp.getPixels(px, 0, 24, 0, 0, 24, 24)
        val counts = HashMap<String, Int>()
        val rs = HashMap<String, Long>()
        val gs = HashMap<String, Long>()
        val bs = HashMap<String, Long>()
        val hsv = FloatArray(3)
        for (p in px) {
            Color.colorToHSV(p, hsv)
            val n = name(hsv[0], hsv[1], hsv[2])
            counts[n] = (counts[n] ?: 0) + 1
            rs[n] = (rs[n] ?: 0L) + Color.red(p)
            gs[n] = (gs[n] ?: 0L) + Color.green(p)
            bs[n] = (bs[n] ?: 0L) + Color.blue(p)
        }
        if (bmp !== src) bmp.recycle()
        val sorted = counts.entries.sortedByDescending { it.value }
        val top = sorted.first()
        val names = ArrayList<String>()
        names.add(top.key)
        if (sorted.size > 1 && sorted[1].value >= 0.2f * px.size) names.add(sorted[1].key)
        val c = top.value.toLong()
        val rgb = Color.rgb((rs[top.key]!! / c).toInt(), (gs[top.key]!! / c).toInt(), (bs[top.key]!! / c).toInt())
        return names.joinToString(",") to rgb
    }
}
