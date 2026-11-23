package jp.local.recipemanager.data.local.v2

import android.content.Context
import androidx.room.withTransaction
import com.fasterxml.jackson.databind.JsonNode
import jp.local.recipemanager.core.json.JsonInput
import jp.local.recipemanager.feature.backup.BackupFormatAdapter
import jp.local.recipemanager.feature.backup.BackupFormatResult
import jp.local.recipemanager.feature.backup.BackupPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

class BackupPreviewV2 internal constructor(
    val createdAt: String,
    val counts: Map<String, Int>,
    val warnings: List<String>,
    internal val canonicalJson: String,
    internal val destinationFingerprint: String,
)

sealed interface BackupPreviewResultV2 {
    data class Ready(val preview: BackupPreviewV2) : BackupPreviewResultV2
    data class Rejected(val message: String) : BackupPreviewResultV2
}

sealed interface BackupWriteResultV2 {
    data class Saved(val completedAt: String) : BackupWriteResultV2
    data object Cancelled : BackupWriteResultV2
    data class Failed(val message: String) : BackupWriteResultV2
}

/** Staged v2 backup service. No production database or SAF UI is switched by this class. */
class BackupRepositoryV2(
    private val database: RecipeManagerDatabaseV2,
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
    private val appVersion: String = "2.00",
    private val legacyMasterMappings: Map<String, Map<String, String>> = emptyMap(),
) {
    private val mapper = JsonInput.mapper()
    private val codec = BackupCodecV2()
    private val legacySchema = context.applicationContext.assets.open("backup-1.0.schema.json").use(mapper::readTree)
    private val currentSchema = context.applicationContext.assets.open("backup-2.0.schema.json").use(mapper::readTree)
    private val dao get() = database.backupDao()

    /** A coherent immutable byte snapshot. Generating bytes alone never marks a backup saved. */
    suspend fun createBackup(): ByteArray = withContext(Dispatchers.IO) {
        val rows = dao.snapshot()
        currentCoroutineContext().ensureActive()
        val bytes = codec.encode(rows, Instant.now(clock), appVersion)
        val validated = adapter(rows).parse(bytes)
        require(validated is BackupFormatResult.Ready) { (validated as BackupFormatResult.Rejected).message }
        currentCoroutineContext().ensureActive()
        bytes
    }

    /** Owns and closes the provider stream. Completion is recorded only after close succeeds. */
    suspend fun exportTo(openDestination: () -> OutputStream?): BackupWriteResultV2 = withContext(Dispatchers.IO) {
        try {
            val bytes = createBackup()
            currentCoroutineContext().ensureActive()
            val destination = openDestination() ?: return@withContext BackupWriteResultV2.Cancelled
            destination.use { it.write(bytes); it.flush() }
            currentCoroutineContext().ensureActive()
            val completed = Instant.now(clock).toString()
            database.withTransaction { dao.putSetting("lastBackupAt", completed) }
            BackupWriteResultV2.Saved(completed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupWriteResultV2.Failed(e.message ?: "バックアップを保存できませんでした")
        }
    }

    suspend fun read(stream: InputStream): BackupPreviewResultV2 = withContext(Dispatchers.IO) {
        try {
            val bytes = stream.use { JsonInput.read(it, JsonInput.BACKUP_LIMIT) }
            preview(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupPreviewResultV2.Rejected(e.message ?: "ファイルを読み込めませんでした")
        }
    }

    suspend fun preview(bytes: ByteArray): BackupPreviewResultV2 = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val rows = dao.snapshot()
        val result = adapter(rows).parse(bytes)
        currentCoroutineContext().ensureActive()
        when (result) {
            is BackupFormatResult.Ready -> BackupPreviewResultV2.Ready(BackupPreviewV2(
                result.document.path("createdAt").asText(), counts(result.document), result.warnings.toList(),
                result.document.toString(), fingerprint(rows),
            ))
            is BackupFormatResult.Rejected -> BackupPreviewResultV2.Rejected(result.message)
        }
    }

    suspend fun restore(preview: BackupPreviewV2, confirmed: Boolean): Map<String, Int> = withContext(Dispatchers.IO) {
        require(confirmed) { "全件置換には確認が必要です" }
        database.withTransaction {
            val local = dao.snapshot()
            require(fingerprint(local) == preview.destinationFingerprint) { "確認中に復元先のデータが変更されています。バックアップを再読込してください" }
            val checked = adapter(local).parse(preview.canonicalJson.toByteArray(Charsets.UTF_8))
            require(checked is BackupFormatResult.Ready) { (checked as BackupFormatResult.Rejected).message }
            currentCoroutineContext().ensureActive()
            dao.replace(codec.decode(checked, local))
            counts(checked.document)
        }
    }

    private fun adapter(rows: BackupRowsV2): BackupFormatAdapter {
        val aliases = rows.builtInAliases.groupBy { it.foodMasterId }
        val presets = rows.foods.filterNot { it.userEditable }.map { BackupPreset(it.id, it.name, aliases[it.id].orEmpty().map { a -> a.alias }) }
        val version = rows.metadata.firstOrNull { it.key == "food_master_version" }?.value ?: error("内蔵材料の版情報がありません")
        return BackupFormatAdapter(legacySchema, currentSchema, presets, version, legacyMasterMappings)
    }

    private fun fingerprint(rows: BackupRowsV2): String = MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(rows)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun counts(root: JsonNode): Map<String, Int> = linkedMapOf(
        "レシピ" to root.path("data").path("recipes").size(),
        "利用者材料" to root.path("data").path("userFoodMasters").size(),
        "利用者別名" to root.path("data").path("userFoodAliases").size(),
        "献立" to root.path("data").path("weeklyMenus").size(),
        "履歴" to root.path("data").path("cookingHistories").size(),
        "買い物リスト" to root.path("data").path("shoppingLists").size(),
        "下書き" to root.path("data").path("drafts").size(),
    )
}
