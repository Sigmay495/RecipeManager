package jp.local.recipemanager.data.local.v2

import android.content.Context
import androidx.room.withTransaction
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.dialect.Dialects
import jp.local.recipemanager.core.json.JsonInput
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class DraftBaseV2(val entityType: String, val entityId: String, val revision: Long)
data class DraftConflictV2(val entityType: String, val entityId: String, val reason: String)
data class DraftResumeV2(val draft: DraftEntityV2, val conflicts: List<DraftConflictV2>) {
    val canConfirm get() = conflicts.isEmpty()
}

/** Durable draft storage; stale content is retained, never silently confirmed or discarded. */
class DraftRepositoryV2(
    private val database: RecipeManagerDatabaseV2,
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mapper = JsonInput.mapper()
    private val schema = run {
        val source = context.applicationContext.assets.open("backup-2.0.schema.json").use(mapper::readTree)
        JsonInput.requireLocalReferences(source)
        val wrapper = mapper.createObjectNode().apply {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            set<JsonNode>("\$defs", source.path("\$defs")); put("\$ref", "#/\$defs/draft")
        }
        SchemaRegistry.withDialect(Dialects.getDraft202012()) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }.getSchema(wrapper)
    }
    private val dao get() = database.draftDao()

    suspend fun all(): List<DraftEntityV2> = dao.all()
    suspend fun find(kind: String, targetKey: String): DraftEntityV2? = dao.target(kind, targetKey)

    suspend fun captureBases(kind: String, targetKey: String, payload: ObjectNode): List<DraftBaseV2> = database.withTransaction {
        val probe = DraftEntityV2(UUID.randomUUID().toString(), kind, targetKey, 1, payload.toString(), "[]", Instant.now(clock).toString())
        validate(probe)
        val current = revisions()
        val required = requiredBases(probe)
        required.map { (type, id) -> DraftBaseV2(type, id, current[type to id] ?: error("下書きの参照が切れています")) }
    }

    suspend fun save(
        kind: String, targetKey: String, payload: ObjectNode, bases: List<DraftBaseV2>, expectedUpdatedAt: String? = null,
    ): DraftEntityV2 = database.withTransaction {
        val old = dao.target(kind, targetKey)
        require(old?.updatedAt == expectedUpdatedAt) { "下書きが別の操作で変更されています。再読込してください" }
        val row = DraftEntityV2(old?.id ?: UUID.randomUUID().toString(), kind, targetKey, 1, payload.toString(),
            mapper.writeValueAsString(bases), later(old?.updatedAt))
        val refs = validate(row)
        val current = revisions()
        require(refs.all { (it.entityType to it.entityId) in current } && bases.all { (it.entityType to it.entityId) in current }) { "下書きの参照が切れています" }
        val keys = bases.map { it.entityType to it.entityId }.toSet()
        require(requiredBases(row).all { it in keys }) { "下書きの基準情報が不足しています" }
        if (old != null && old.payloadJson == row.payloadJson && old.baseRevisionsJson == row.baseRevisionsJson) return@withTransaction old
        if (old == null) dao.insert(row) else dao.update(row)
        dao.clearReferences(row.id)
        if (refs.isNotEmpty()) dao.insertReferences(refs.toList())
        row
    }

    suspend fun replace(
        kind: String, targetKey: String, payload: ObjectNode, bases: List<DraftBaseV2>, expectedUpdatedAt: String?, confirmed: Boolean,
    ): DraftEntityV2 {
        require(confirmed) { "下書きの置換には確認が必要です" }
        return save(kind, targetKey, payload, bases, expectedUpdatedAt)
    }

    suspend fun resume(id: String): DraftResumeV2 = database.withTransaction {
        val row = dao.find(id) ?: error("下書きが見つかりません")
        val refs = validate(row)
        val current = revisions()
        val conflicts = payload(row).path("conflicts").map { DraftConflictV2(it.path("entityType").asText(), it.path("entityId").asText(), it.path("reason").asText()) }.toMutableList()
        bases(row).forEach { base ->
            if (current[base.entityType to base.entityId] != base.revision)
                conflicts += DraftConflictV2(base.entityType, base.entityId, "保存後に参照先が変更または削除されています")
        }
        refs.filter { (it.entityType to it.entityId) !in current }.forEach {
            conflicts += DraftConflictV2(it.entityType, it.entityId, "参照先が見つかりません")
        }
        if (refs != dao.references(id).toSet()) conflicts += DraftConflictV2(row.kind, row.id, "下書きの参照索引が一致しません")
        if (!requiredBases(row).all { key -> bases(row).any { (it.entityType to it.entityId) == key } })
            conflicts += DraftConflictV2(row.kind, row.id, "下書きの基準情報が不足しています")
        DraftResumeV2(row, conflicts.distinct())
    }

    suspend fun discard(id: String, expectedUpdatedAt: String, confirmed: Boolean) = database.withTransaction {
        require(confirmed) { "下書きの破棄には確認が必要です" }
        val row = dao.find(id) ?: error("下書きが見つかりません")
        require(row.updatedAt == expectedUpdatedAt) { "下書きが変更されています。再読込してください" }
        remove(row.id)
    }

    internal suspend fun ready(id: String, expectedUpdatedAt: String, kind: String): DraftEntityV2 {
        val resumed = resume(id)
        require(resumed.draft.kind == kind && resumed.draft.updatedAt == expectedUpdatedAt) { "下書きの種類または更新日時が一致しません" }
        require(resumed.canConfirm) { "下書きに競合があります。内容を再確認してください" }
        return resumed.draft
    }

    internal suspend fun remove(id: String) { dao.clearReferences(id); dao.delete(id) }
    internal fun payload(row: DraftEntityV2): ObjectNode = parse(row.payloadJson) as ObjectNode
    internal fun bases(row: DraftEntityV2): List<DraftBaseV2> = parse(row.baseRevisionsJson).map {
        DraftBaseV2(it.path("entityType").asText(), it.path("entityId").asText(), it.path("revision").asLong())
    }

    private fun validate(row: DraftEntityV2): Set<DraftReferenceEntityV2> {
        val node = mapper.createObjectNode().apply {
            put("id", row.id); put("kind", row.kind); put("targetKey", row.targetKey); put("payloadVersion", row.payloadVersion)
            set<JsonNode>("payload", parse(row.payloadJson)); set<JsonNode>("baseRevisions", parse(row.baseRevisionsJson)); put("updatedAt", row.updatedAt)
        }
        val errors = schema.validate(node)
        require(errors.isEmpty()) { "下書き形式が不正です: ${errors.take(3).joinToString { it.message }}" }
        return DraftFoodMergeSupport.references(row)
    }

    private suspend fun requiredBases(row: DraftEntityV2): Set<Pair<String, String>> = buildSet {
        DraftFoodMergeSupport.references(row).forEach { add(it.entityType to it.entityId) }
        if (row.kind == "menu") {
            val week = database.menuDao().week(row.targetKey)
            if (week != null) {
                add("menu" to week.id)
                database.menuDao().days(week.id).forEach { add("menuDay" to it.id) }
            }
        }
    }

    private suspend fun revisions(): Map<Pair<String, String>, Long> {
        val references = database.referenceDao()
        return buildMap {
            references.recipes().forEach { put("recipe" to it.id, it.revision) }
            references.foods().forEach { put("food" to it.id, it.revision) }
            references.menus().forEach { put("menu" to it.id, it.revision) }
            references.days().forEach { put("menuDay" to it.id, it.revision) }
        }
    }
    private fun parse(value: String): JsonNode = JsonInput.parse(value.toByteArray(Charsets.UTF_8), JsonInput.BACKUP_LIMIT, mapper)
    private fun later(previous: String?): String = Instant.now(clock).let { now ->
        val old = previous?.let(Instant::parse); (if (old != null && now <= old) old.plusNanos(1) else now).toString()
    }
}
