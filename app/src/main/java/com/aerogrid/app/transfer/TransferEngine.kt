package com.aerogrid.app.transfer

enum class TransferStep(val label: String) {
    KMZ_GENERATED("KMZ generated"), KMZ_VALIDATED("KMZ validated"), METHOD_UNAVAILABLE("Transfer method unavailable"),
    PERMISSION_GRANTED("Transfer permission granted"), FOLDER_FOUND("DJI Fly mission folder found"), PLACEHOLDER_SELECTED("Placeholder selected"),
    BACKUP_CREATED("Backup created"), FILE_COPIED("File copied"), FILE_VERIFIED("File verified"), READY("Ready for DJI Fly review"),
    FAILED("Transfer failed"), RESTORED("Original mission restored"),
}

data class StepLog(val step: TransferStep, val detail: String = "", val time: Long = System.currentTimeMillis())

data class DjiMission(val id: String, val path: String, val sizeBytes: Long, val modifiedSec: Long)

/** Abstract access to DJI Fly's waypoint storage (implemented by Shizuku shell, SAF, or test fakes). */
interface MissionFs {
    val name: String
    /** Returns the verified waypoint root directory, or null if not accessible. */
    suspend fun probe(): String?
    suspend fun list(root: String): List<DjiMission>
    suspend fun sha256(path: String): String?
    /** Copy [from] to [to] (both inside storage). */
    suspend fun copy(from: String, to: String): Boolean
    /** Write a local app file (already staged) over [to]. */
    suspend fun writeStaged(stagedPath: String, to: String): Boolean
    fun backupPathFor(m: DjiMission, time: Long): String
    suspend fun listBackups(): List<String>
}

class TransferEngine(private val fs: MissionFs) {
    data class Result(val success: Boolean, val log: List<StepLog>, val backupPath: String?)

    companion object {
        val CANDIDATE_ROOTS = listOf(
            "/storage/emulated/0/Android/data/dji.go.v5/files/waypoint",
            "/sdcard/Android/data/dji.go.v5/files/waypoint",
        )
        const val DJI_FLY_PACKAGE = "dji.go.v5"
    }

    /**
     * Replace the placeholder [target] with the staged KMZ: back up, verify backup, write, verify hash,
     * and restore the backup if anything fails after the original was touched.
     */
    suspend fun replace(target: DjiMission, stagedPath: String, expectedSha: String, now: Long = System.currentTimeMillis()): Result {
        val log = ArrayList<StepLog>()
        log += StepLog(TransferStep.PLACEHOLDER_SELECTED, target.id)
        val originalSha = fs.sha256(target.path)
        if (originalSha == null) { log += StepLog(TransferStep.FAILED, "Cannot read placeholder ${target.path}"); return Result(false, log, null) }
        val backup = fs.backupPathFor(target, now)
        if (!fs.copy(target.path, backup) || fs.sha256(backup) != originalSha) {
            log += StepLog(TransferStep.FAILED, "Backup could not be created or verified — placeholder left untouched")
            return Result(false, log, null)
        }
        log += StepLog(TransferStep.BACKUP_CREATED, backup)
        val wrote = fs.writeStaged(stagedPath, target.path)
        if (wrote) log += StepLog(TransferStep.FILE_COPIED, target.path)
        val after = if (wrote) fs.sha256(target.path) else null
        if (wrote && after == expectedSha) {
            log += StepLog(TransferStep.FILE_VERIFIED, "SHA-256 ${expectedSha.take(16)}…")
            log += StepLog(TransferStep.READY, "Force-close and reopen DJI Fly, then open the placeholder mission and inspect it")
            return Result(true, log, backup)
        }
        log += StepLog(TransferStep.FAILED, if (!wrote) "Write failed" else "Verification failed (hash mismatch)")
        val restored = fs.copy(backup, target.path) && fs.sha256(target.path) == originalSha
        log += if (restored) StepLog(TransferStep.RESTORED, "Placeholder restored from $backup") else StepLog(TransferStep.FAILED, "Automatic restore failed — backup kept at $backup")
        return Result(false, log, backup)
    }

    suspend fun restore(backupPath: String, target: DjiMission): Boolean {
        val sha = fs.sha256(backupPath) ?: return false
        return fs.copy(backupPath, target.path) && fs.sha256(target.path) == sha
    }
}

/** Parse "path|size|mtime" lines from `stat -c '%n|%s|%Y'`. */
fun parseStatLines(out: String): List<DjiMission> = out.lines().mapNotNull { l ->
    val parts = l.trim().split('|'); if (parts.size < 3 || !parts[0].endsWith(".kmz")) return@mapNotNull null
    DjiMission(parts[0].substringAfterLast('/').removeSuffix(".kmz"), parts[0], parts[1].toLongOrNull() ?: 0, parts[2].toLongOrNull() ?: 0)
}.sortedByDescending { it.modifiedSec }

/** Which on-device transfer methods can be offered, with a truthful reason when unavailable. */
object TransferAvailability {
    data class Method(val id: String, val available: Boolean, val reason: String)

    fun evaluate(sdk: Int, djiInstalled: Boolean, shizukuInstalled: Boolean, shizukuRunning: Boolean, shizukuGranted: Boolean, safGranted: Boolean): List<Method> {
        val a = when {
            !djiInstalled -> Method("shizuku", false, "DJI Fly is not installed")
            !shizukuInstalled -> Method("shizuku", false, "Install Shizuku to enable authorised direct transfer")
            !shizukuRunning -> Method("shizuku", false, "Start Shizuku via Wireless debugging")
            !shizukuGranted -> Method("shizuku", false, "Authorise AeroGrid in Shizuku")
            else -> Method("shizuku", true, "Ready")
        }
        val b = when {
            sdk > 32 -> Method("saf", false, "Android 13+ does not allow document access to Android/data")
            !djiInstalled -> Method("saf", false, "DJI Fly is not installed")
            !safGranted -> Method("saf", false, "Grant access to the DJI Fly data folder")
            else -> Method("saf", true, "Ready")
        }
        return listOf(a, b, Method("external", true, "Export KMZ to Downloads / share / PC transfer"))
    }
}
