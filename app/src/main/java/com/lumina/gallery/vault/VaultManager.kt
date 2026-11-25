package com.lumina.gallery.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lumina.gallery.data.Media
import com.lumina.gallery.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

class VaultItem(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val relPath: String,
    val isVideo: Boolean,
    val added: Long
)

/**
 * Encrypted vault. Files are AES-256-GCM encrypted in 1 MB chunks with a hardware-backed Android Keystore key,
 * the item index is encrypted too, and the PIN is stored only as a salted PBKDF2 hash.
 */
class VaultManager(private val ctx: Context, private val prefs: Prefs) {
    var unlocked by mutableStateOf(false)
        private set
    var version by mutableIntStateOf(0)
        private set

    private val dir = File(ctx.filesDir, "vault").apply { mkdirs() }
    private val indexFile = File(dir, "index.lum")
    private val tmpDir = File(ctx.cacheDir, "vault_tmp")
    private var failures = 0
    private var lockedUntil = 0L

    val hasPin: Boolean get() = prefs.vaultHash.isNotEmpty()

    // ---------- PIN ----------
    private fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.vaultSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs.vaultHash = hash(pin, salt)
        unlocked = true
        version++
    }

    /** Returns null on success, otherwise a message for the user. */
    fun unlockWithPin(pin: String): String? {
        val now = SystemClock.elapsedRealtime()
        if (now < lockedUntil) return "Too many attempts. Try again in ${(lockedUntil - now) / 1000 + 1}s."
        val salt = Base64.decode(prefs.vaultSalt, Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(hash(pin, salt).toByteArray(), prefs.vaultHash.toByteArray())
        if (ok) {
            failures = 0
            unlocked = true
            version++
            return null
        }
        failures++
        if (failures >= 5) {
            failures = 0
            lockedUntil = now + 30_000
            return "Wrong PIN. Locked for 30 seconds."
        }
        return "Wrong PIN."
    }

    fun unlockWithBiometric() {
        if (hasPin) { unlocked = true; version++ }
    }

    fun lock() {
        if (unlocked) unlocked = false
        tmpDir.deleteRecursively()
    }

    // ---------- crypto ----------
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun encryptStream(input: InputStream, out: OutputStream): Long {
        val k = key()
        val buf = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val n = readFully(input, buf)
            if (n <= 0) break
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k)
            val enc = c.doFinal(buf, 0, n)
            val iv = c.iv
            out.write(intBytes(enc.size))
            out.write(intBytes(iv.size))
            out.write(iv)
            out.write(enc)
            total += n
            if (n < CHUNK) break
        }
        return total
    }

    private fun decryptStream(input: InputStream, out: OutputStream) {
        val k = key()
        val hdr = ByteArray(8)
        while (true) {
            if (readFully(input, hdr) < 8) break
            val encLen = bytesInt(hdr, 0)
            val ivLen = bytesInt(hdr, 4)
            val iv = ByteArray(ivLen)
            readFully(input, iv)
            val enc = ByteArray(encLen)
            readFully(input, enc)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            out.write(c.doFinal(enc))
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun intBytes(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun bytesInt(b: ByteArray, o: Int) =
        ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)

    // ---------- index ----------
    @Synchronized
    fun items(): List<VaultItem> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val bos = java.io.ByteArrayOutputStream()
            indexFile.inputStream().use { decryptStream(it, bos) }
            val arr = JSONArray(String(bos.toByteArray()))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                VaultItem(o.getString("id"), o.getString("name"), o.getString("mime"), o.getLong("size"),
                    o.optString("rel"), o.optBoolean("video"), o.optLong("added"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun saveIndex(list: List<VaultItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("size", it.size)
                .put("rel", it.relPath).put("video", it.isVideo).put("added", it.added))
        }
        indexFile.outputStream().use { encryptStream(arr.toString().toByteArray().inputStream(), it) }
        version++
    }

    // ---------- operations ----------
    /** Encrypts the given gallery items. Returns the ones that were stored successfully. */
    fun add(items: List<Media>): List<Media> {
        val ok = ArrayList<Media>()
        val list = items().toMutableList()
        for (m in items) {
            try {
                val id = java.util.UUID.randomUUID().toString()
                val f = File(dir, "$id.lum")
                val size = ctx.contentResolver.openInputStream(m.uri)?.use { i -> f.outputStream().use { o -> encryptStream(i, o) } } ?: continue
                list.add(VaultItem(id, m.name, m.mime, size, m.relPath, m.isVideo, System.currentTimeMillis()))
                ok.add(m)
            } catch (e: Exception) {
            }
        }
        saveIndex(list)
        return ok
    }

    fun decryptToBytes(item: VaultItem): ByteArray? = try {
        val bos = java.io.ByteArrayOutputStream()
        File(dir, "${item.id}.lum").inputStream().use { decryptStream(it, bos) }
        bos.toByteArray()
    } catch (e: Exception) {
        null
    }

    fun thumb(item: VaultItem): Bitmap? {
        if (item.isVideo || !item.mime.startsWith("image/")) return null
        val bytes = decryptToBytes(item) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / s > 600) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    fun full(item: VaultItem): Bitmap? {
        val bytes = decryptToBytes(item) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / s > 3000) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** Decrypts to a temp file in the cache (wiped when the vault locks) so external players can open it. */
    fun tempFile(item: VaultItem): File? = try {
        tmpDir.mkdirs()
        val f = File(tmpDir, item.name)
        File(dir, "${item.id}.lum").inputStream().use { i -> f.outputStream().use { o -> decryptStream(i, o) } }
        f
    } catch (e: Exception) {
        null
    }

    fun decryptTo(item: VaultItem, out: OutputStream): Boolean = try {
        File(dir, "${item.id}.lum").inputStream().use { decryptStream(it, out) }
        true
    } catch (e: Exception) {
        false
    }

    fun remove(ids: Collection<String>) {
        ids.forEach { File(dir, "$it.lum").delete() }
        saveIndex(items().filter { it.id !in ids })
    }

    companion object {
        private const val ALIAS = "lumina_vault_key"
        private const val CHUNK = 1024 * 1024
    }
}
