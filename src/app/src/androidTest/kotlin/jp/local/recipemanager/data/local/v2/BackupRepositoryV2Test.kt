package jp.local.recipemanager.data.local.v2

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import jp.local.recipemanager.core.json.JsonInput
import jp.local.recipemanager.feature.backup.BackupCodec
import jp.local.recipemanager.data.local.*
import jp.local.recipemanager.domain.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupRepositoryV2Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mapper = JsonInput.mapper()
    private val now = Instant.parse("2026-11-23T01:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Asia/Tokyo"))
    private val start = LocalDate.parse("2026-11-23")
    private fun repo(db: RecipeManagerDatabaseV2) = BackupRepositoryV2(db, context, clock)

    @Test fun allUserDataRoundTripsWithReferencesExactAmountsStatesAndRebuiltCaches() = runBlocking {
        withTwoDatabases { source, target ->
            rich(source); rich(target)
            val before = source.backupDao().snapshot()
            val local = target.backupDao().snapshot()
            val bytes = repo(source).createBackup()
            val root = mapper.readTree(bytes)
            assertFalse(root.path("data").path("recipes").first().has("lastCookedAt"))
            assertFalse(root.path("data").has("metadata"))
            assertEquals(1, root.path("data").path("userFoodMasters").size())
            assertTrue(root.path("data").path("userFoodMasters").first().path("isMainIngredient").asBoolean())
            val service = repo(target)
            val preview = ready(service.preview(bytes))
            assertEquals(3, preview.counts["下書き"])
            service.restore(preview, true)
            val after = target.backupDao().snapshot()
            assertEquals(before.recipes, after.recipes)
            assertEquals(before.ingredients, after.ingredients)
            assertEquals(before.menus, after.menus); assertEquals(before.days, after.days)
            assertEquals(before.menuItems, after.menuItems)
            assertEquals(before.histories, after.histories); assertEquals(before.historyItems, after.historyItems)
            assertEquals(before.outcomes, after.outcomes); assertEquals(before.postponements, after.postponements)
            assertEquals(before.shoppingLists, after.shoppingLists); assertEquals(before.shoppingItems, after.shoppingItems)
            assertEquals(before.foods, after.foods); assertEquals(before.userAliases, after.userAliases)
            assertEquals(before.groups, after.groups); assertEquals(before.seasons, after.seasons)
            assertEquals(before.drafts, after.drafts); assertEquals(before.references.toSet(), after.references.toSet())
            assertEquals(local.builtInAliases, after.builtInAliases)
            assertEquals(local.metadata, after.metadata); assertEquals(local.holidays, after.holidays)
            assertEquals(now.toString(), setting(target))
            assertEquals("14", after.settings.first { it.key == "backupReminderIntervalDays" }.value)
            assertEquals("0.24691357802469135780246913578", after.shoppingItems.single { it.unit == "g" }.amount)
            assertTrue(after.shoppingItems.single { it.unit == "g" }.hasUnknownAmount)
            assertTrue(after.shoppingItems.all { it.checked && it.excluded })
            assertTrue(after.histories.any { it.id == "history-2026-11-20" })
            val drafts = DraftRepositoryV2(target, context, clock)
            assertFalse(drafts.resume(after.drafts.first { it.kind == "recipe" }.id).canConfirm)
            assertEquals(2, after.recipes.size) // no draft was silently committed
            assertForeignKeys(target)
        }
    }

    @Test fun exportWritesOutsideTransactionAndMarksSuccessAfterCloseOnly() = runBlocking {
        withDatabase { db ->
            rich(db)
            val before = db.backupDao().snapshot()
            var closed = false
            val destination = object : ByteArrayOutputStream() {
                override fun close() { assertEquals("2026-01-01T00:00:00Z", runBlocking { setting(db) }); closed = true; super.close() }
            }
            val result = repo(db).exportTo {
                runBlocking {
                    val old = db.recipeDao().find(before.recipes.first().id)!!.recipe
                    RecipeRepositoryV2(db, clock).save(RecipeInputV2("出力開始後の変更", old.category,
                        listOf(RecipeIngredientInputV2("材料", BigDecimal.ONE)), old.id, old.revision))
                }
                destination
            }
            assertTrue(result.toString(), result is BackupWriteResultV2.Saved)
            assertTrue(closed)
            assertEquals(now.toString(), setting(db))
            val exported = mapper.readTree(destination.toByteArray()).path("data").path("recipes")
            assertEquals(before.recipes.map { it.name }.toSet(), exported.map { it.path("name").asText() }.toSet())
            assertTrue(db.backupDao().recipes().any { it.name == "出力開始後の変更" })
        }
    }

    @Test fun writeFlushCloseFailuresAndDestinationCancellationNeverAdvanceLastBackup() = runBlocking {
        withDatabase { db ->
            rich(db)
            for (phase in listOf("write", "flush", "close")) {
                var closed = false
                val result = repo(db).exportTo {
                    object : OutputStream() {
                        override fun write(value: Int) { if (phase == "write") throw IOException(phase) }
                        override fun flush() { if (phase == "flush") throw IOException(phase) }
                        override fun close() { closed = true; if (phase == "close") throw IOException(phase) }
                    }
                }
                assertTrue(result.toString(), result is BackupWriteResultV2.Failed)
                assertTrue(closed)
                assertEquals("2026-01-01T00:00:00Z", setting(db))
            }
            assertEquals(BackupWriteResultV2.Cancelled, repo(db).exportTo { null })
            assertEquals("2026-01-01T00:00:00Z", setting(db))
        }
    }

    @Test fun coroutineCancellationAfterWritingDoesNotMarkBackupSaved() = runBlocking {
        withDatabase { db ->
            rich(db)
            supervisorScope {
                val operation = async {
                    val job = currentCoroutineContext().job
                    repo(db).exportTo { object : ByteArrayOutputStream() { override fun close() { super.close(); job.cancel() } } }
                }
                assertTrue(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
            }
            assertEquals("2026-01-01T00:00:00Z", setting(db))
        }
    }

    @Test fun previewRejectsMalformedVersionsReferencesDuplicateIdsAndInvalidUnknownWithoutWrites() = runBlocking {
        withDatabase { db ->
            rich(db)
            val service = repo(db); val before = db.backupDao().snapshot()
            val valid = mapper.readTree(service.createBackup()) as ObjectNode
            val cases = listOf(
                "{".toByteArray(),
                valid.deepCopy().put("schemaVersion", "9.0").toString().toByteArray(),
                valid.deepCopy().apply { row(this, "recipeIngredients").put("recipeId", UUID.randomUUID().toString()) }.toString().toByteArray(),
                valid.deepCopy().apply { row(this, "recipeIngredients").put("order", Long.MAX_VALUE) }.toString().toByteArray(),
                valid.deepCopy().apply { (path("data").path("recipes") as com.fasterxml.jackson.databind.node.ArrayNode).add(row(this, "recipes").deepCopy()) }.toString().toByteArray(),
                valid.deepCopy().apply { row(this, "shoppingItems").putNull("amount").put("hasUnknownAmount", false) }.toString().toByteArray(),
            )
            for (bytes in cases) {
                assertTrue(service.preview(bytes) is BackupPreviewResultV2.Rejected)
                assertEquals(before, db.backupDao().snapshot())
            }
            assertEquals(before, db.backupDao().snapshot())
        }
    }

    @Test fun confirmationAndUnchangedDestinationAreRequiredAndInputBytesCannotMutatePreview() = runBlocking {
        withDatabase { db ->
            rich(db)
            val service = repo(db); val bytes = service.createBackup()
            val preview = ready(service.preview(bytes))
            val before = db.backupDao().snapshot()
            reject { service.restore(preview, false) }
            assertEquals(before, db.backupDao().snapshot())
            bytes.fill(0)
            service.restore(preview, true) // immutable verified candidate
            val next = ready(service.preview(service.createBackup()))
            val shopping = ShoppingRepositoryV2(db, clock)
            val item = shopping.week(start).data!!.items.first()
            shopping.updateState(item, checked = !item.checked)
            val changed = db.backupDao().snapshot()
            reject { service.restore(next, true) }
            assertEquals(changed, db.backupDao().snapshot())
        }
    }

    @Test fun failureLateInReplacementRollsBackEveryTableIncludingLocalSettings() = runBlocking {
        withTwoDatabases { source, target ->
            rich(source); rich(target)
            val before = target.backupDao().snapshot()
            val service = repo(target)
            val preview = ready(service.preview(repo(source).createBackup()))
            target.openHelper.writableDatabase.execSQL("""CREATE TEMP TRIGGER reject_restore BEFORE INSERT ON drafts
                BEGIN SELECT RAISE(ABORT,'injected restore failure'); END""")
            val failure = runCatching { service.restore(preview, true) }.exceptionOrNull()
            assertNotNull(failure); assertTrue(failure.toString().contains("injected restore failure"))
            assertEquals(before, target.backupDao().snapshot())
            assertForeignKeys(target)
        }
    }

    @Test fun restoredDraftsAndShoppingSurviveFileDatabaseReopen() = runBlocking {
        val name = "backup-test-${UUID.randomUUID()}.db"
        withDatabase { source ->
            rich(source)
            val bytes = repo(source).createBackup()
            try {
                var target = Room.databaseBuilder(context, RecipeManagerDatabaseV2::class.java, name).build()
                try { seed(target); repo(target).restore(ready(repo(target).preview(bytes)), true) } finally { target.close() }
                target = Room.databaseBuilder(context, RecipeManagerDatabaseV2::class.java, name).build()
                try {
                    assertEquals(3, target.draftDao().all().size)
                    assertTrue(ShoppingRepositoryV2(target).week(start).data!!.items.any { it.amount != null && it.hasUnknownAmount })
                    assertEquals(3, ready(repo(target).preview(repo(target).createBackup())).counts["下書き"])
                    assertForeignKeys(target)
                } finally { target.close() }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun actualLegacyCodecOutputRestoresIdsAliasesPrecisionAndChecksWithoutInventedGroups() = runBlocking {
        withDatabase { target ->
            val r = uuid(); val f = uuid(); val h = "history-2026-11-20"
            val list = uuid()
            val legacy = BackupRows(
                listOf(RecipeEntity(r, "旧料理", "旧料理", RecipeCategory.ONE_DISH, null, EffortLevel.NORMAL, null, "",
                    LocalDate.parse("2020-01-01"), LocalDate.parse("2020-01-01"), emptySet(), RecipeStatus.ACTIVE, null, now, now)),
                listOf(RecipeIngredientEntity(uuid(), r, 0, "旧材料", "旧材料", BigDecimal("0.12345678901234567890123456789"), "g", "", f)),
                emptyList(), emptyList(), emptyList(),
                listOf(CookingHistoryEntity(h, LocalDate.parse("2026-11-20"), now, now)),
                listOf(HistoryItemEntity(uuid(), h, r, "旧料理", MenuRole.ONE_DISH)),
                listOf(ShoppingListEntity(list, start, now, now)),
                listOf(ShoppingItemEntity(uuid(), list, "旧材料", "旧材料", null, "g", "旧料理", true, true)),
                listOf(FoodMasterWithDetails(FoodMasterEntity(f, "旧材料", "旧材料", "袋", true, true),
                    listOf(FoodMasterAliasEntity(f, "Ａ Ｂ", "ab")), emptyList(), listOf(FoodMasterSeasonEntity(f, 12)))),
                listOf(SettingEntity("backupReminderIntervalDays", "60")),
            )
            val bytes = BackupCodec(context).encode(legacy, now, "1.00", "test-master")
            val service = repo(target)
            val preview = ready(service.preview(bytes))
            assertTrue(preview.warnings.any { it.contains("食品群") })
            service.restore(preview, true)
            val data = target.backupDao().snapshot()
            assertEquals(r, data.recipes.single().id)
            assertEquals("2026-11-20", data.recipes.single().lastCookedAt)
            assertEquals("0.12345678901234567890123456789", data.ingredients.single().amount)
            assertEquals("history-2026-11-20", data.histories.single().id)
            assertEquals("Ａ Ｂ", data.userAliases.single().alias)
            assertEquals(f, target.foodDao().search("ab").single().id)
            assertTrue(data.shoppingItems.single().checked && data.shoppingItems.single().excluded)
            assertTrue(data.shoppingItems.single().hasUnknownAmount)
            assertNull(data.shoppingItems.single().amount)
            assertTrue(data.shoppingLists.single().needsRegeneration)
            assertEquals("", data.foods.single { it.userEditable }.baseUnit)
            assertTrue(target.foodDao().groups(f).isEmpty())
            assertForeignKeys(target)
        }
    }

    @Test fun oldPresetMappingIsRevalidatedAndCurrentPresetDataIsRetained() = runBlocking {
        withDatabase { db ->
            rich(db)
            val drafts = DraftRepositoryV2(db, context, clock)
            val old = db.draftDao().all().single { it.kind == "recipe" }
            val payload = mapper.readTree(old.payloadJson) as ObjectNode
            (payload.path("form").path("ingredients").first() as ObjectNode).put("foodMasterId", "builtin-rice")
            drafts.save("recipe", old.targetKey, payload, drafts.captureBases("recipe", old.targetKey, payload), old.updatedAt)
            val root = mapper.readTree(repo(db).createBackup()) as ObjectNode
            root.put("masterVersion", "old-master")
            val bytes = root.toString().replace("builtin-rice", "builtin-old-rice").toByteArray()
            assertTrue(repo(db).preview(bytes) is BackupPreviewResultV2.Rejected)
            val service = BackupRepositoryV2(db, context, clock, legacyMasterMappings = mapOf("old-master" to mapOf("builtin-old-rice" to "builtin-rice")))
            val preview = ready(service.preview(bytes))
            val original = db.backupDao().snapshot()
            service.restore(preview, true)
            val after = db.backupDao().snapshot()
            assertEquals(original.foods.filterNot { it.userEditable }, after.foods.filterNot { it.userEditable })
            assertEquals(original.builtInAliases, after.builtInAliases)
            assertTrue(after.ingredients.any { it.foodMasterId == "builtin-rice" })
            assertTrue(after.shoppingItems.any { it.foodMasterId == "builtin-rice" })
            assertTrue(after.references.any { it.entityType == "food" && it.entityId == "builtin-rice" })
            val restored = after.drafts.single { it.kind == "recipe" }
            assertTrue(restored.payloadJson.contains("builtin-rice"))
            assertTrue(restored.baseRevisionsJson.contains("builtin-rice"))
            assertFalse(restored.payloadJson.contains("builtin-old-rice"))
            assertForeignKeys(db)
        }
    }

    @Test fun providerReadAndCloseFailuresAreRejectedWithoutChangingSavedData() = runBlocking {
        withDatabase { db ->
            rich(db)
            val service = repo(db)
            val bytes = service.createBackup()
            val before = db.backupDao().snapshot()
            for (phase in listOf("read", "close")) {
                var closed = false
                val stream = object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (phase == "read") throw IOException("provider read failure")
                        return super.read(buffer, offset, length)
                    }
                    override fun close() { closed = true; if (phase == "close") throw IOException("provider close failure") }
                }
                assertTrue(service.read(stream) is BackupPreviewResultV2.Rejected)
                assertTrue(closed)
                assertEquals(before, db.backupDao().snapshot())
            }
            assertTrue(service.read(ByteArrayInputStream(bytes)) is BackupPreviewResultV2.Ready)
            assertEquals(before, db.backupDao().snapshot())
        }
    }

    @Test fun invalidStoredDataIsRejectedBeforeOpeningOutputDestination() = runBlocking {
        withDatabase { db ->
            rich(db)
            db.openHelper.writableDatabase.execSQL("UPDATE recipes SET category = 'unknown'")
            var opened = false
            val result = repo(db).exportTo { opened = true; ByteArrayOutputStream() }
            assertTrue(result is BackupWriteResultV2.Failed)
            assertFalse(opened)
            assertEquals("2026-01-01T00:00:00Z", setting(db))
        }
    }

    @Test fun emptyBackupReplacesAllUserDataAndPreservesInstalledReferenceData() = runBlocking {
        withTwoDatabases { source, target ->
            rich(target)
            val local = target.backupDao().snapshot()
            repo(target).restore(ready(repo(target).preview(repo(source).createBackup())), true)
            val after = target.backupDao().snapshot()
            assertTrue(after.recipes.isEmpty() && after.ingredients.isEmpty() && after.days.isEmpty())
            assertTrue(after.histories.isEmpty() && after.outcomes.isEmpty() && after.postponements.isEmpty())
            assertTrue(after.shoppingItems.isEmpty() && after.drafts.isEmpty() && after.references.isEmpty() && after.userAliases.isEmpty())
            assertEquals(local.foods.filterNot { it.userEditable }, after.foods)
            assertEquals(local.metadata, after.metadata); assertEquals(local.holidays, after.holidays)
            assertForeignKeys(target)
        }
    }

    @Test fun concurrentWriterCannotProduceMixedBackupSnapshot() = runBlocking {
        withDatabase { db ->
            rich(db)
            val dish = db.backupDao().recipes().first()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.IO) {
                db.withTransaction {
                    db.openHelper.writableDatabase.execSQL("UPDATE recipes SET memo = '同時更新' WHERE id = ?", arrayOf(dish.id))
                    entered.complete(Unit); release.await()
                    db.openHelper.writableDatabase.execSQL("UPDATE recipeIngredients SET note = '同時更新' WHERE recipeId = ?", arrayOf(dish.id))
                }
            }
            entered.await()
            val reader = async { repo(db).createBackup() }
            yield(); release.complete(Unit); writer.await()
            val d = mapper.readTree(reader.await()).path("data")
            assertEquals("同時更新", d.path("recipes").single { it.path("id").asText() == dish.id }.path("memo").asText())
            assertTrue(d.path("recipeIngredients").filter { it.path("recipeId").asText() == dish.id }.all { it.path("note").asText() == "同時更新" })
        }
    }

    private suspend fun rich(db: RecipeManagerDatabaseV2) {
        val food = FoodRepositoryV2(db).save(FoodInputV2("材料", listOf("別名"), setOf("EGG", "VEGETABLE"), "g", setOf(1, 12), true))
        val recipes = RecipeRepositoryV2(db, clock)
        val a = recipes.save(RecipeInputV2("精密料理", "ONE_DISH", listOf(
            RecipeIngredientInputV2("材料", BigDecimal("0.12345678901234567890123456789"), "g", foodMasterId = food.id),
            RecipeIngredientInputV2("材料", null, "g", foodMasterId = food.id),
            RecipeIngredientInputV2("材料", BigDecimal("1.2"), "kg", foodMasterId = food.id),
        )))
        val b = recipes.save(RecipeInputV2("米料理", "ONE_DISH", listOf(RecipeIngredientInputV2("米", BigDecimal.ONE, "合", foodMasterId = "builtin-rice"))))
        val menus = MenuRepositoryV2(db, clock)
        for (offset in 0L..2L) {
            val week = menus.week(start); val day = week.days.single { it.day.date == start.plusDays(offset).toString() }.day
            menus.saveDay(MenuDayInputV2(start.plusDays(offset), false, listOf(MenuSelectionV2(if (offset == 2L) b.id else a.id, "ONE_DISH", offset == 0L)), dayLocked = offset == 0L),
                week.menu?.revision, day.revision.takeIf { day.id.isNotEmpty() })
        }
        var week = menus.week(start)
        val history = HistoryRepositoryV2(db, clock)
        val cooked = week.days[1]
        history.recordCooking(cooked.day.id, cooked.day.revision, mapOf(cooked.items.single().id to a.id))
        week = menus.week(start)
        val postponedDay = week.days[2]
        val pending = history.postpone(postponedDay.items.single().id, start.plusDays(3), postponedDay.day.revision)
        week = menus.week(start)
        history.confirmPostponement(pending.id, pending.updatedAt, week.days[2].day.revision,
            MenuDayInputV2(start.plusDays(3), false, listOf(MenuSelectionV2(b.id, "ONE_DISH"))), week.menu!!.revision,
            week.days[3].day.revision.takeIf { week.days[3].day.id.isNotEmpty() }, true)
        val legacyHistory = CookingHistoryEntityV2("history-2026-11-20", "2026-11-20", now.toString(), now.toString())
        db.historyDao().insertHeader(legacyHistory)
        db.historyDao().insertItem(HistoryItemEntityV2(uuid(), legacyHistory.id, b.id, "旧名の米料理", "ONE_DISH"))
        db.historyDao().recalculateLastCooked(b.id)
        val shopping = ShoppingRepositoryV2(db, clock)
        shopping.generate(start).data.items.forEach { shopping.updateState(it, true, true) }
        val drafts = DraftRepositoryV2(db, context, clock)
        val payload = mapper.readTree("""{"form":{"name":"","category":"one_dish","cookingTimeMinutes":"未完","effortLevel":"normal","sourceUrl":"https:","memo":"途中","proposalPriority":"normal","ingredients":[{"localKey":"line","name":"材料","amount":"入力中","unit":"g","note":"","foodMasterId":"${food.id}","foodCandidateKey":null}]},"foodCandidates":[],"conflicts":[{"entityType":"food","entityId":"${uuid()}","reason":"統合前の競合"}]}""") as ObjectNode
        drafts.save("recipe", a.id, payload, drafts.captureBases("recipe", a.id, payload))
        drafts.save("import", "singleton", mapper.readTree("""{"sourceJson":"元の入力","entries":[],"foodCandidates":[],"conflicts":[]}""") as ObjectNode, emptyList())
        val menuPayload = mapper.createObjectNode().apply {
            putArray("conflicts"); putArray("warnings").add("未採用"); put("seed", "19")
            putArray("days").also { days -> (0L..6L).forEach { offset -> days.addObject().apply {
                put("date", start.plusDays(offset).toString()); put("noMeal", true); put("requirementOrigin", "explicit"); put("dayLocked", false); putArray("items")
            } } }
        }
        drafts.save("menu", start.toString(), menuPayload, drafts.captureBases("menu", start.toString(), menuPayload))
        db.backupDao().putSetting("backupReminderIntervalDays", "14")
        db.backupDao().putSetting("lastBackupAt", "2026-01-01T00:00:00Z")
    }

    private suspend fun seed(db: RecipeManagerDatabaseV2) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO metadata VALUES ('food_master_version','test-master'),('holiday_data_version','local-holidays'),('device_test','keep')")
        sql.execSQL("INSERT INTO holidays VALUES ('2026-01-01','元日',2026)")
        db.foodDao().insertMaster(FoodMasterEntityV2("builtin-rice", "米", "米", "合", true, false, 1))
        sql.execSQL("INSERT INTO foodMasterAliases VALUES ('builtin-rice','白米','白米')")
        db.foodDao().insertGroups(listOf(FoodMasterGroupEntityV2("builtin-rice", "GRAIN")))
        db.foodDao().insertSeasons(listOf(FoodMasterSeasonEntityV2("builtin-rice", 9)))
    }
    private suspend fun withDatabase(block: suspend (RecipeManagerDatabaseV2) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, RecipeManagerDatabaseV2::class.java).build()
        try { seed(db); block(db) } finally { db.close() }
    }
    private suspend fun withTwoDatabases(block: suspend (RecipeManagerDatabaseV2, RecipeManagerDatabaseV2) -> Unit) {
        withDatabase { a -> withDatabase { b -> block(a, b) } }
    }
    private suspend fun setting(db: RecipeManagerDatabaseV2) = db.backupDao().settings().firstOrNull { it.key == "lastBackupAt" }?.value
    private fun ready(result: BackupPreviewResultV2): BackupPreviewV2 {
        assertTrue(result.toString(), result is BackupPreviewResultV2.Ready)
        return (result as BackupPreviewResultV2.Ready).preview
    }
    private suspend fun reject(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("検証で拒否する必要があります: $failure", failure is IllegalArgumentException || failure is IllegalStateException)
    }
    private fun row(root: ObjectNode, table: String) = root.path("data").path(table).first() as ObjectNode
    private fun uuid() = UUID.randomUUID().toString()
    private fun assertForeignKeys(db: RecipeManagerDatabaseV2) = db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
}
