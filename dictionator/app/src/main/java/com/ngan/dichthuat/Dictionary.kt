package com.ngan.dichthuat

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class Entry(val word: String, val body: String)

/**
 * Offline dictionary stored in SQLite on the phone.
 *
 * Import formats (.txt / .dict, or the same inside .zip / .gz / .dict.dz):
 *  1. Hồ Ngọc Đức / FVDP style:
 *       @abandon /ə'bændən/
 *       * danh từ
 *       - sự phóng túng
 *       =with abandon+ phóng túng
 *       !idiom
 *  2. Tab-separated: word<TAB>meaning   (use \n inside the meaning for new lines)
 */
class Dictionary(context: Context) : SQLiteOpenHelper(context, "dictionary.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE e (k TEXT NOT NULL, w TEXT NOT NULL, b TEXT NOT NULL)")
        db.execSQL("CREATE INDEX ek ON e(k)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun key(s: String) = s.trim().lowercase()

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "e")

    fun lookup(query: String): List<Entry> {
        val k = key(query)
        if (k.isEmpty()) return emptyList()
        readableDatabase.rawQuery("SELECT w, b FROM e WHERE k = ? LIMIT 10", arrayOf(k)).use { c ->
            val out = ArrayList<Entry>()
            while (c.moveToNext()) out.add(Entry(c.getString(0), c.getString(1)))
            return out
        }
    }

    fun suggest(query: String): List<String> {
        val k = key(query)
        if (k.isEmpty()) return emptyList()
        readableDatabase.rawQuery(
            "SELECT DISTINCT w FROM e WHERE k > ? AND k < ? ORDER BY k LIMIT 15",
            arrayOf(k, k + "￿")
        ).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            return out
        }
    }

    /** Replaces the current dictionary. Returns the number of entries imported. */
    fun import(raw: InputStream, onProgress: (Int) -> Unit): Int {
        val input = unwrap(raw)
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            db.delete("e", null, null)
            val st = db.compileStatement("INSERT INTO e (k, w, b) VALUES (?, ?, ?)")
            fun add(w: String, b: String) {
                val word = w.trim()
                if (word.isEmpty()) return
                st.clearBindings()
                st.bindString(1, key(word))
                st.bindString(2, word)
                st.bindString(3, b.trim())
                st.executeInsert()
                n++
                if (n % 2000 == 0) onProgress(n)
            }

            var word: String? = null
            val body = StringBuilder()
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.lineSequence().forEach { rawLine ->
                    val line = rawLine.removePrefix("﻿").trimEnd()
                    when {
                        line.startsWith("@") -> {
                            word?.let { add(it, body.toString()) }
                            body.setLength(0)
                            val head = line.substring(1)
                            val slash = head.indexOf(" /")
                            if (slash >= 0) {
                                word = head.substring(0, slash)
                                body.append(head.substring(slash + 1).trim()).append('\n')
                            } else {
                                word = head
                            }
                        }
                        word != null -> body.append(line).append('\n')
                        line.contains('\t') -> {
                            val i = line.indexOf('\t')
                            add(line.substring(0, i), line.substring(i + 1).replace("\\n", "\n"))
                        }
                    }
                }
            }
            word?.let { add(it, body.toString()) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    /** Opens .zip (first file) and .gz transparently. */
    private fun unwrap(raw: InputStream): InputStream {
        val s = BufferedInputStream(raw)
        s.mark(4)
        val b0 = s.read()
        val b1 = s.read()
        s.reset()
        return when {
            b0 == 0x1f && b1 == 0x8b -> GZIPInputStream(s)
            b0 == 'P'.code && b1 == 'K'.code -> {
                // Find the dictionary data inside the zip (skip .index, readme, folders)
                val z = ZipInputStream(s)
                var e = z.nextEntry
                while (e != null) {
                    val name = e.name.lowercase()
                    if (!e.isDirectory && (name.endsWith(".dict.dz") || name.endsWith(".dict") ||
                            name.endsWith(".txt") || name.endsWith(".tsv"))) break
                    e = z.nextEntry
                }
                if (e == null) throw IllegalArgumentException("No .dict or .txt file inside the zip")
                if (e.name.lowercase().endsWith(".dz")) GZIPInputStream(z) else z
            }
            else -> s
        }
    }
}
