package io.github.brunofin.caldavtasks.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.brunofin.caldavtasks.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound AES-GCM, atomic writes, no backup/credential export, no hardcoded encryption key. */
class SecureStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "accounts-and-cache.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("caldav-tasks-v1", null) as? SecretKey)?.let { return it }
        // Never generate a replacement key if encrypted data already exists.
        check(!file.baseFile.exists()) { "The device encryption key is unavailable." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("caldav-tasks-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): Snapshot {
        if (!file.baseFile.exists()) return Snapshot()
        val bytes = file.readFully()
        require(bytes.size > 13 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return decode(JSONObject(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)))
    }
    fun write(state: Snapshot) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = byteArrayOf(1) + cipher.iv + cipher.doFinal(encode(state).toString().toByteArray())
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun array(items: List<JSONObject>) = JSONArray().apply { items.forEach(::put) }
    private fun objects(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
    private fun encode(s: Snapshot) = JSONObject().apply {
        put("version", 1)
        put("lastSync", s.lastSync)
        put("errors", JSONObject(s.errors))
        put("accounts", array(s.accounts.map { a -> JSONObject().apply {
            put("id", a.id); put("name", a.name); put("url", a.url); put("username", a.username)
            put("password", a.password); put("enabled", a.enabled)
        } }))
        put("lists", array(s.lists.map { l -> JSONObject().apply {
            put("id", l.id); put("accountId", l.accountId); put("name", l.name); put("url", l.url)
            put("taskOnly", l.taskOnly); put("writable", l.writable)
        } }))
        put("tasks", array(s.tasks.map { t -> JSONObject().apply {
            put("id", t.id); put("listId", t.listId); put("uid", t.uid); put("title", t.title)
            put("completed", t.completed); put("parentUid", t.parentUid ?: JSONObject.NULL)
            put("recurring", t.recurring); put("url", t.url); put("etag", t.etag)
            put("calendar", t.calendar); put("editable", t.editable)
        } }))
    }
    private fun decode(o: JSONObject): Snapshot {
        require(o.getInt("version") == 1)
        return Snapshot(
            accounts = objects(o.getJSONArray("accounts")).map { a -> Account(a.getString("id"), a.getString("name"),
                a.getString("url"), a.getString("username"), a.getString("password"), a.getBoolean("enabled")) },
            lists = objects(o.getJSONArray("lists")).map { l -> TaskList(l.getString("id"), l.getString("accountId"),
                l.getString("name"), l.getString("url"), l.getBoolean("taskOnly"), l.getBoolean("writable")) },
            tasks = objects(o.getJSONArray("tasks")).map { t -> Task(t.getString("id"), t.getString("listId"),
                t.getString("uid"), t.getString("title"), t.getBoolean("completed"),
                if (t.isNull("parentUid")) null else t.getString("parentUid"), t.getBoolean("recurring"),
                t.getString("url"), t.getString("etag"), t.getString("calendar"), t.getBoolean("editable")) },
            errors = o.getJSONObject("errors").let { e -> e.keys().asSequence().associateWith { e.getString(it) } },
            lastSync = o.optLong("lastSync"),
        )
    }
}
