package com.aerogrid.app.transfer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Method A: Shizuku (started via Wireless debugging — no root). Runs file operations as the ADB shell user. */
class ShizukuFs : MissionFs {
    override val name = "Shizuku (authorised ADB shell)"

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val BACKUP_DIR = "/storage/emulated/0/Download/AeroGrid/DJI_backups"
        fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess
        fun running() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        fun granted() = running() && runCatching { !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        fun request(code: Int) = runCatching { Shizuku.requestPermission(code) }.isSuccess
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    suspend fun sh(cmd: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            p.errorStream.bufferedReader().readText()
            p.waitFor() to out
        } catch (e: Throwable) { -1 to (e.cause?.message ?: e.message ?: "Shizuku error") }
    }

    override suspend fun probe(): String? {
        if (!granted()) return null
        for (r in TransferEngine.CANDIDATE_ROOTS) if (sh("[ -d ${q(r)} ] && echo OK").second.trim() == "OK") return r
        return null
    }

    override suspend fun list(root: String): List<DjiMission> =
        parseStatLines(sh("for f in ${q(root)}/*/*.kmz ${q(root)}/*.kmz; do [ -f \"\$f\" ] && stat -c '%n|%s|%Y' \"\$f\"; done").second)

    override suspend fun sha256(path: String): String? {
        val (c, o) = sh("sha256sum ${q(path)}"); return if (c == 0) o.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.length == 64 } else null
    }

    override suspend fun copy(from: String, to: String): Boolean =
        sh("mkdir -p \"\$(dirname ${q(to)})\" && cat ${q(from)} > ${q(to)}").first == 0

    override suspend fun writeStaged(stagedPath: String, to: String) = copy(stagedPath, to)
    override fun backupPathFor(m: DjiMission, time: Long) = "$BACKUP_DIR/${m.id}_$time.kmz"
    override suspend fun listBackups(): List<String> = sh("ls -1t ${q(BACKUP_DIR)} 2>/dev/null").second.lines().filter { it.endsWith(".kmz") }.map { "$BACKUP_DIR/$it" }
}

/** Method B: Storage Access Framework grant on Android/data/dji.go.v5 (only possible on Android 12L and older). */
class SafFs(private val ctx: Context, private val tree: Uri) : MissionFs {
    override val name = "Android document access (SAF)"

    companion object {
        val supported get() = Build.VERSION.SDK_INT <= 32
        fun initialUri(): Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Android/data/dji.go.v5")
        fun openTreeIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    private fun open(path: String): InputStream? = if (path.startsWith("/")) File(path).takeIf { it.exists() }?.inputStream() else ctx.contentResolver.openInputStream(Uri.parse(path))
    private fun openOut(path: String): OutputStream? = if (path.startsWith("/")) File(path).apply { parentFile?.mkdirs() }.outputStream() else ctx.contentResolver.openOutputStream(Uri.parse(path), "wt")

    override suspend fun probe(): String? = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return@withContext null
        val wp = when (root.name) {
            "waypoint" -> root
            "files" -> root.findFile("waypoint")
            else -> root.findFile("files")?.findFile("waypoint")
        }
        wp?.takeIf { it.isDirectory && it.canWrite() }?.uri?.toString()
    }

    override suspend fun list(root: String): List<DjiMission> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(root)) ?: DocumentFile.fromSingleUri(ctx, Uri.parse(root)) ?: return@withContext emptyList()
        val wpDir = if (dir.name == "waypoint") dir else DocumentFile.fromTreeUri(ctx, tree)?.let { r -> r.findFile("files")?.findFile("waypoint") ?: r.findFile("waypoint") } ?: dir
        wpDir.listFiles().flatMap { f -> if (f.isDirectory) f.listFiles().toList() else listOf(f) }
            .filter { it.name?.endsWith(".kmz") == true }
            .map { DjiMission(it.name!!.removeSuffix(".kmz"), it.uri.toString(), it.length(), it.lastModified() / 1000) }
            .sortedByDescending { it.modifiedSec }
    }

    override suspend fun sha256(path: String): String? = withContext(Dispatchers.IO) {
        runCatching { open(path)?.use { s -> val md = MessageDigest.getInstance("SHA-256"); val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }; md.digest().joinToString("") { "%02x".format(it) } } }.getOrNull()
    }

    override suspend fun copy(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { open(from)!!.use { i -> openOut(to)!!.use { o -> i.copyTo(o) } }; true }.getOrDefault(false)
    }

    override suspend fun writeStaged(stagedPath: String, to: String) = copy(stagedPath, to)
    override fun backupPathFor(m: DjiMission, time: Long) = File(ctx.getExternalFilesDir("dji_backups"), "${m.id}_$time.kmz").absolutePath
    override suspend fun listBackups() = ctx.getExternalFilesDir("dji_backups")?.listFiles()?.sortedByDescending { it.lastModified() }?.map { it.absolutePath } ?: emptyList()
}

/** Method C helpers: export to Downloads, share, open DJI Fly. */
object ExternalTransfer {
    fun saveToDownloads(ctx: Context, name: String, bytes: ByteArray, mime: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AeroGrid")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create file in Downloads")
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            return "Download/AeroGrid/$name"
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AeroGrid").apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
        return "Download/AeroGrid/$name"
    }

    fun shareIntent(ctx: Context, files: List<File>, mime: String): Intent {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
        return (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris))
            .setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun djiFlyInstalled(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(TransferEngine.DJI_FLY_PACKAGE, 0) }.isSuccess
    fun openDjiFly(ctx: Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(TransferEngine.DJI_FLY_PACKAGE) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
    }

    const val WINDOWS_GUIDE = """Windows-assisted transfer (USB, no root):
1. In DJI Fly create and save a 2-waypoint placeholder mission, then close DJI Fly.
2. On the phone enable Developer options › USB debugging and connect it to the PC.
3. Install Android platform-tools on Windows (adb).
4. Copy the AeroGrid KMZ from Download/AeroGrid to the PC (or use adb pull).
5. List placeholders:   adb shell ls -lt /sdcard/Android/data/dji.go.v5/files/waypoint/
6. Back up:             adb pull /sdcard/Android/data/dji.go.v5/files/waypoint/<ID>/<ID>.kmz backup_<ID>.kmz
7. Replace:             adb push Mission_Block_01.kmz /sdcard/Android/data/dji.go.v5/files/waypoint/<ID>/<ID>.kmz
8. Reopen DJI Fly, open the placeholder and inspect every waypoint before flight.
The project includes tools/djifly_transfer.ps1 which automates steps 5–7 with a backup."""
}
