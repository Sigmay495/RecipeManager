package jp.local.recipemanager.data.local.v2

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fasterxml.jackson.databind.node.ObjectNode
import jp.local.recipemanager.core.json.JsonInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DraftRepositoryV2Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mapper = JsonInput.mapper()
    private val week = LocalDate.parse("2026-11-23")
    private val clock = Clock.fixed(Instant.parse("2026-11-23T01:00:00Z"), ZoneId.of("Asia/Tokyo"))

    @Test fun allThreeKindsAndUnfinishedInputSurviveClosingAndReopeningDatabase() = runBlocking {
        val name = "draft-test-${UUID.randomUUID()}.db"
        try {
            var db = Room.databaseBuilder(context, RecipeManagerDatabaseV2::class.java, name).build()
            val recipe = recipePayload().apply { (path("form") as ObjectNode).put("cookingTimeMinutes", "入力中").put("name", "") }
            val importing = mapper.createObjectNode().apply { put("sourceJson", "元入力"); putArray("entries"); putArray("foodCandidates"); putArray("conflicts") }
            try {
                val drafts = DraftRepositoryV2(db, context, clock)
                drafts.save("recipe", "new", recipe, emptyList())
                drafts.save("import", "singleton", importing, emptyList())
                drafts.save("menu", week.toString(), menuPayload(null), emptyList())
            } finally { db.close() }
            db = Room.databaseBuilder(context, RecipeManagerDatabaseV2::class.java, name).build()
            try {
                val drafts = DraftRepositoryV2(db, context, clock)
                assertEquals(3, drafts.all().size)
                assertEquals(recipe.toString(), drafts.find("recipe", "new")!!.payloadJson)
                assertEquals(importing.toString(), drafts.find("import", "singleton")!!.payloadJson)
                assertEquals("0", scalar(db, "SELECT COUNT(*) FROM recipes"))
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun optimisticUpdatesNoOpReplacementAndExplicitDiscardProtectOtherEditors() = runBlocking {
        withDatabase { db ->
            val drafts = DraftRepositoryV2(db, context, clock)
            val payload = recipePayload()
            val first = drafts.save("recipe", "new", payload, emptyList())
            assertEquals(first, drafts.save("recipe", "new", payload, emptyList(), first.updatedAt))
            reject { drafts.save("recipe", "new", payload, emptyList()) }
            (payload.path("form") as ObjectNode).put("name", "変更")
            val changed = drafts.save("recipe", "new", payload, emptyList(), first.updatedAt)
            assertNotEquals(first.updatedAt, changed.updatedAt)
            reject { drafts.save("recipe", "new", payload, emptyList(), first.updatedAt) }
            reject { drafts.replace("recipe", "new", payload, emptyList(), changed.updatedAt, false) }
            reject { drafts.discard(changed.id, changed.updatedAt, false) }
            reject { drafts.discard(changed.id, first.updatedAt, true) }
            val invalid = payload.deepCopy().put("unknown", true)
            reject { drafts.save("recipe", "new", invalid, emptyList(), changed.updatedAt) }
            assertEquals(changed, drafts.find("recipe", "new"))
            drafts.discard(changed.id, changed.updatedAt, true)
            assertTrue(drafts.all().isEmpty())
        }
    }

    @Test fun changedTargetsStayAsConflictingDraftsInsteadOfBeingOverwrittenOrDiscarded() = runBlocking {
        withDatabase { db ->
            val recipes = RecipeRepositoryV2(db, clock)
            val saved = recipes.save(RecipeInputV2("料理", "MAIN", listOf(RecipeIngredientInputV2("材料", BigDecimal.ONE))))
            val drafts = DraftRepositoryV2(db, context, clock)
            val payload = recipePayload()
            val draft = drafts.save("recipe", saved.id, payload, drafts.captureBases("recipe", saved.id, payload))
            recipes.save(RecipeInputV2("別編集", "MAIN", listOf(RecipeIngredientInputV2("材料", BigDecimal.ONE)), saved.id, saved.revision))
            assertFalse(drafts.resume(draft.id).canConfirm)
            reject { DraftCommitRepositoryV2(db, context, clock).confirmRecipe(draft.id, draft.updatedAt, true) }
            assertEquals(draft.payloadJson, drafts.find("recipe", saved.id)!!.payloadJson)
            assertEquals("別編集", recipes.find(saved.id)!!.recipe.name)
        }
    }

    @Test fun confirmedRecipeCreatesCandidatesAtomicallyAndRemovesOnlyItsOwnDraft() = runBlocking {
        withDatabase { db ->
            val drafts = DraftRepositoryV2(db, context, clock)
            val commits = DraftCommitRepositoryV2(db, context, clock)
            val payload = recipePayload().apply {
                (path("form") as ObjectNode).put("cookingTimeMinutes", "途中")
                (path("form").path("ingredients").first() as ObjectNode).put("foodCandidateKey", "candidate")
                (path("foodCandidates") as com.fasterxml.jackson.databind.node.ArrayNode).addObject().apply {
                    put("localKey", "candidate"); put("name", "新材料"); putArray("aliases"); putArray("foodGroups").add("vegetable")
                    put("baseUnit", "g"); putArray("seasonMonths"); put("isMainIngredient", true)
                }
            }
            var draft = drafts.save("recipe", "new", payload, emptyList())
            reject { commits.confirmRecipe(draft.id, draft.updatedAt, true) }
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM foodMasters"))
            assertEquals("1", scalar(db, "SELECT COUNT(*) FROM drafts"))
            (payload.path("form") as ObjectNode).put("cookingTimeMinutes", "20")
            draft = drafts.save("recipe", "new", payload, emptyList(), draft.updatedAt)
            reject { commits.confirmRecipe(draft.id, draft.updatedAt, false) }
            val saved = commits.confirmRecipe(draft.id, draft.updatedAt, true)
            assertEquals("VEGETABLE", saved.nutritionGroups)
            assertEquals("1", scalar(db, "SELECT COUNT(*) FROM foodMasters"))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM drafts"))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM draftReferences"))
        }
    }

    @Test fun materialMergeKeepsStoredDraftUsableWithUpdatedReferencesAndBaselines() = runBlocking {
        withDatabase { db ->
            val foods = FoodRepositoryV2(db)
            val source = foods.save(FoodInputV2("旧材料"))
            val target = foods.save(FoodInputV2("新材料"))
            val payload = recipePayload().apply { (path("form").path("ingredients").first() as ObjectNode).put("foodMasterId", source.id) }
            val drafts = DraftRepositoryV2(db, context, clock)
            val draft = drafts.save("recipe", "new", payload, drafts.captureBases("recipe", "new", payload))
            foods.merge(source.id, target.id, source.revision, target.revision, true)
            val resumed = drafts.resume(draft.id)
            assertTrue(Instant.parse(resumed.draft.updatedAt) > Instant.parse(draft.updatedAt))
            assertTrue(resumed.conflicts.toString(), resumed.canConfirm)
            assertTrue(resumed.draft.payloadJson.contains(target.id))
            assertFalse(resumed.draft.payloadJson.contains(source.id))
            val saved = DraftCommitRepositoryV2(db, context, clock).confirmRecipe(draft.id, resumed.draft.updatedAt, true)
            assertEquals(target.id, RecipeRepositoryV2(db).find(saved.id)!!.ingredients.single().foodMasterId)
        }
    }

    @Test fun importLoadWritesNoMastersAndRequiresConfirmationToReplaceExistingReview() = runBlocking {
        withDatabase { db ->
            val commits = DraftCommitRepositoryV2(db, context, clock)
            val first = commits.beginImport(importBytes("料理A", "料理B"))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM recipes"))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM foodMasters"))
            reject { commits.beginImport(importBytes("料理C"), first.draft.updatedAt) }
            reject { commits.beginImport("{}".toByteArray(), first.draft.updatedAt, true) }
            reject { commits.beginImport(ByteArrayInputStream(ByteArray(JsonInput.IMPORT_LIMIT + 1))) }
            assertEquals(first.draft, db.draftDao().find(first.draft.id))
            val replaced = commits.beginImport(importBytes("料理C"), first.draft.updatedAt, true)
            assertEquals(first.draft.id, replaced.draft.id)
            assertEquals(1, replaced.entries.size)
        }
    }

    @Test fun importRevalidatesEditedFormsAndAddsNormallyWithSharedFoodCandidateOnce() = runBlocking {
        withDatabase { db ->
            val commits = DraftCommitRepositoryV2(db, context, clock)
            val loaded = commits.beginImport(importBytes("料理A", "料理B"))
            val drafts = DraftRepositoryV2(db, context, clock)
            val payload = mapper.readTree(loaded.draft.payloadJson) as ObjectNode
            payload.path("entries").forEach { (it.path("form") as ObjectNode).put("proposalPriority", "high") }
            val edited = drafts.save("import", "singleton", payload, drafts.captureBases("import", "singleton", payload), loaded.draft.updatedAt)
            val checked = commits.revalidateImport(edited.id, edited.updatedAt)
            val result = commits.confirmImport(checked.draft.id, checked.draft.updatedAt, true)
            assertEquals(2, result.addedRecipeIds.size)
            assertEquals("1", scalar(db, "SELECT COUNT(*) FROM foodMasters"))
            assertEquals("2", scalar(db, "SELECT COUNT(*) FROM recipes WHERE proposalPriority='normal'"))
            assertEquals("1.234567890123456789", scalar(db, "SELECT amount FROM recipeIngredients LIMIT 1"))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM drafts"))
        }
    }

    @Test fun invalidEntriesRemainEditableAndCanBeSkippedWithoutLosingValidEntries() = runBlocking {
        withDatabase { db ->
            val source = mapper.readTree(importBytes("料理A", "料理B")) as ObjectNode
            (source.path("recipes")[1] as ObjectNode).put("category", "invalid")
            val commits = DraftCommitRepositoryV2(db, context, clock)
            val loaded = commits.beginImport(mapper.writeValueAsBytes(source))
            val payload = mapper.readTree(loaded.draft.payloadJson)
            assertEquals("error", payload.path("entries")[1].path("validationState").asText())
            assertEquals("skip", payload.path("entries")[1].path("decision").asText())
            val result = commits.confirmImport(loaded.draft.id, loaded.draft.updatedAt, true)
            assertEquals(1, result.addedRecipeIds.size)
            assertEquals(1, result.skipped)
        }
    }

    @Test fun invalidImportedFormCanBeCorrectedRevalidatedAndConfirmed() = runBlocking {
        withDatabase { db ->
            val source = mapper.readTree(importBytes("料理A")) as ObjectNode
            (source.path("recipes")[0] as ObjectNode).put("category", "bad")
            val commits = DraftCommitRepositoryV2(db, context, clock)
            val loaded = commits.beginImport(mapper.writeValueAsBytes(source))
            val drafts = DraftRepositoryV2(db, context, clock)
            val payload = mapper.readTree(loaded.draft.payloadJson) as ObjectNode
            (payload.path("entries")[0].path("form") as ObjectNode).put("category", "main")
            (payload.path("entries")[0] as ObjectNode).put("decision", "add")
            val edited = drafts.save("import", "singleton", payload, drafts.captureBases("import", "singleton", payload), loaded.draft.updatedAt)
            val reviewed = commits.revalidateImport(edited.id, edited.updatedAt)
            assertTrue(reviewed.entries.single().errors.isEmpty())
            assertEquals(1, commits.confirmImport(reviewed.draft.id, reviewed.draft.updatedAt, true).addedRecipeIds.size)
        }
    }

    @Test fun retiredDuplicateIsNeverRestoredOrOverwrittenAndExplicitAddUsesNewId() = runBlocking {
        withDatabase { db ->
            val old = RecipeRepositoryV2(db).save(RecipeInputV2("既存", "MAIN", listOf(RecipeIngredientInputV2("材料", BigDecimal.ONE)), sourceUrl = "https://example.com/0", proposalPriority = "retired"))
            val commits = DraftCommitRepositoryV2(db, context, clock)
            val loaded = commits.beginImport(importBytes("料理A"))
            assertTrue(loaded.entries.single().duplicates.single().retired)
            val payload = mapper.readTree(loaded.draft.payloadJson) as ObjectNode
            (payload.path("entries")[0] as ObjectNode).put("decision", "add")
            val drafts = DraftRepositoryV2(db, context, clock)
            val edited = drafts.save("import", "singleton", payload, drafts.captureBases("import", "singleton", payload), loaded.draft.updatedAt)
            reject { commits.confirmImport(edited.id, edited.updatedAt, true) }
            val key = payload.path("entries")[0].path("localKey").asText()
            val added = commits.confirmImport(edited.id, edited.updatedAt, true, setOf(key)).addedRecipeIds.single()
            assertNotEquals(old.id, added)
            assertEquals(old, RecipeRepositoryV2(db).find(old.id)!!.recipe)
            assertEquals("normal", RecipeRepositoryV2(db).find(added)!!.recipe.proposalPriority)
        }
    }

    @Test fun duplicatesAddedAfterReviewAreDetectedAgainBeforeWriting() = runBlocking {
        withDatabase { db ->
            val commits = DraftCommitRepositoryV2(db, context, clock)
            val loaded = commits.beginImport(importBytes("料理A"))
            RecipeRepositoryV2(db).save(RecipeInputV2("後から追加", "MAIN", listOf(RecipeIngredientInputV2("材料")), sourceUrl = "https://example.com/0"))
            reject { commits.confirmImport(loaded.draft.id, loaded.draft.updatedAt, true) }
            assertEquals("1", scalar(db, "SELECT COUNT(*) FROM recipes"))
            val reviewed = commits.revalidateImport(loaded.draft.id, loaded.draft.updatedAt)
            assertEquals(1, reviewed.entries.single().duplicates.size)
            val keys = reviewed.entries.map { it.localKey }.toSet()
            assertEquals(1, commits.confirmImport(reviewed.draft.id, reviewed.draft.updatedAt, true, keys).addedRecipeIds.size)
        }
    }

    @Test fun sameFileDuplicatesRequireExplicitAcknowledgementForEachAddition() = runBlocking {
        withDatabase { db ->
            val source = mapper.readTree(importBytes("料理A", "料理B")) as ObjectNode
            (source.path("recipes")[1] as ObjectNode).put("sourceUrl", "https://example.com/0")
            val commits = DraftCommitRepositoryV2(db, context, clock)
            val loaded = commits.beginImport(mapper.writeValueAsBytes(source))
            assertTrue(loaded.entries.all { it.duplicateEntryKeys.isNotEmpty() })
            val payload = mapper.readTree(loaded.draft.payloadJson) as ObjectNode
            payload.path("entries").forEach { (it as ObjectNode).put("decision", "add") }
            val drafts = DraftRepositoryV2(db, context, clock)
            val edited = drafts.save("import", "singleton", payload, drafts.captureBases("import", "singleton", payload), loaded.draft.updatedAt)
            reject { commits.confirmImport(edited.id, edited.updatedAt, true) }
            assertEquals(2, commits.confirmImport(edited.id, edited.updatedAt, true, loaded.entries.map { it.localKey }.toSet()).addedRecipeIds.size)
        }
    }

    @Test fun failureDuringImportRollsBackEarlierRecipesCandidatesAndKeepsDraft() = runBlocking {
        withDatabase { db ->
            val commits = DraftCommitRepositoryV2(db, context, clock)
            val loaded = commits.beginImport(importBytes("料理A", "料理B"))
            db.openHelper.writableDatabase.execSQL("""CREATE TEMP TRIGGER reject_recipe_b BEFORE INSERT ON recipes
                WHEN NEW.name='料理B' BEGIN SELECT RAISE(ABORT,'injected failure'); END""")
            assertNotNull(runCatching { commits.confirmImport(loaded.draft.id, loaded.draft.updatedAt, true) }.exceptionOrNull())
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM recipes"))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM foodMasters"))
            assertEquals(loaded.draft, db.draftDao().find(loaded.draft.id))
        }
    }

    @Test fun oneHundredImportedRecipesSucceedAndOneHundredOneAreRejectedWithoutReplacingDraft() = runBlocking {
        withDatabase { db ->
            val commits = DraftCommitRepositoryV2(db, context, clock)
            val loaded = commits.beginImport(importBytes(*(0 until 100).map { "料理$it" }.toTypedArray()))
            assertEquals(100, loaded.entries.size)
            reject { commits.beginImport(importBytes(*(0 until 101).map { "料理$it" }.toTypedArray()), loaded.draft.updatedAt, true) }
            assertEquals(loaded.draft, db.draftDao().find(loaded.draft.id))
            assertEquals(100, commits.confirmImport(loaded.draft.id, loaded.draft.updatedAt, true).addedRecipeIds.size)
            assertEquals("100", scalar(db, "SELECT COUNT(*) FROM recipes"))
            assertEquals("1", scalar(db, "SELECT COUNT(*) FROM foodMasters"))
        }
    }

    @Test fun menuDraftConfirmationIsAtomicAndRemovesDraftOnlyOnSuccess() = runBlocking {
        withDatabase { db ->
            val dish = RecipeRepositoryV2(db).save(RecipeInputV2("料理", "ONE_DISH", listOf(RecipeIngredientInputV2("材料"))))
            val drafts = DraftRepositoryV2(db, context, clock)
            val payload = menuPayload(dish.id)
            val second = payload.path("days")[1] as ObjectNode
            second.put("noMeal", false)
            second.putArray("items").addObject().apply { put("localKey", "bad"); put("recipeId", dish.id); put("role", "main"); put("locked", false) }
            var draft = drafts.save("menu", week.toString(), payload, drafts.captureBases("menu", week.toString(), payload))
            val commits = DraftCommitRepositoryV2(db, context, clock)
            val firstWarnings = commits.reviewMenu(draft.id, draft.updatedAt).evaluation.warnings.toSet()
            reject { commits.confirmMenu(draft.id, draft.updatedAt, true, firstWarnings) }
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM weeklyMenus"))
            assertNotNull(db.draftDao().find(draft.id))
            (second.path("items")[0] as ObjectNode).put("role", "one_dish")
            draft = drafts.save("menu", week.toString(), payload, drafts.captureBases("menu", week.toString(), payload), draft.updatedAt)
            val warnings = commits.reviewMenu(draft.id, draft.updatedAt).evaluation.warnings.toSet()
            val saved = commits.confirmMenu(draft.id, draft.updatedAt, true, warnings)
            assertEquals(7, saved.days.size)
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM drafts"))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM draftReferences"))
        }
    }

    @Test fun reopenedMenuDraftCannotChangeDaysThatElapsedWhileItWasPending() = runBlocking {
        withDatabase { db ->
            val dish = RecipeRepositoryV2(db).save(RecipeInputV2("料理", "ONE_DISH", listOf(RecipeIngredientInputV2("材料"))))
            val drafts = DraftRepositoryV2(db, context, clock)
            val payload = menuPayload(dish.id)
            val row = drafts.save("menu", week.toString(), payload, drafts.captureBases("menu", week.toString(), payload))
            val later = Clock.offset(clock, java.time.Duration.ofDays(1))
            val commits = DraftCommitRepositoryV2(db, context, later)
            val warnings = commits.reviewMenu(row.id, row.updatedAt).evaluation.warnings.toSet()
            reject { commits.confirmMenu(row.id, row.updatedAt, true, warnings) }
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM menuDays"))
            assertEquals(row, db.draftDao().find(row.id))
        }
    }

    @Test fun menuWarningsAreRecomputedWhenHistoryChangesWithoutRecipeRevisionChanges() = runBlocking {
        withDatabase { db ->
            val dish = RecipeRepositoryV2(db).save(RecipeInputV2("料理", "ONE_DISH", listOf(RecipeIngredientInputV2("材料"))))
            val drafts = DraftRepositoryV2(db, context, clock)
            val payload = menuPayload(dish.id)
            val row = drafts.save("menu", week.toString(), payload, drafts.captureBases("menu", week.toString(), payload))
            val commits = DraftCommitRepositoryV2(db, context, clock)
            val oldWarnings = commits.reviewMenu(row.id, row.updatedAt).evaluation.warnings.toSet()
            HistoryRepositoryV2(db, clock).recordManual(week, dish.id)
            assertTrue(drafts.resume(row.id).canConfirm)
            reject { commits.confirmMenu(row.id, row.updatedAt, true, oldWarnings) }
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM menuDays"))
            val warnings = commits.reviewMenu(row.id, row.updatedAt).evaluation.warnings.toSet()
            assertTrue(warnings.any { it.contains("2週間以内") })
            commits.confirmMenu(row.id, row.updatedAt, true, warnings)
            assertNull(db.draftDao().find(row.id))
        }
    }

    private fun recipePayload(): ObjectNode = mapper.createObjectNode().apply {
        putArray("foodCandidates"); putArray("conflicts")
        putObject("form").apply {
            put("name", "料理"); put("category", "main"); put("cookingTimeMinutes", ""); put("effortLevel", "normal")
            put("sourceUrl", ""); put("memo", "入力途中"); put("proposalPriority", "normal")
            putArray("ingredients").addObject().apply {
                put("localKey", "line"); put("name", "材料"); put("amount", "1.25"); put("unit", "g"); put("note", "")
                putNull("foodMasterId"); putNull("foodCandidateKey")
            }
        }
    }
    private fun menuPayload(recipeId: String?): ObjectNode = mapper.createObjectNode().apply {
        putArray("conflicts"); putArray("warnings"); put("seed", "19")
        putArray("days").also { days -> (0L..6L).forEach { offset -> days.addObject().apply {
            put("date", week.plusDays(offset).toString()); put("noMeal", offset != 0L || recipeId == null)
            put("requirementOrigin", "explicit"); put("dayLocked", false)
            putArray("items").also { if (offset == 0L && recipeId != null) it.addObject().apply {
                put("localKey", "item"); put("recipeId", recipeId); put("role", "one_dish"); put("locked", false)
            } }
        } } }
    }
    private fun importBytes(vararg names: String): ByteArray = mapper.writeValueAsBytes(mapper.createObjectNode().apply {
        put("schemaVersion", "1.0"); put("fileType", "recipe-import")
        putArray("recipes").also { recipes -> names.forEachIndexed { index, name -> recipes.addObject().apply {
            put("name", name); put("category", "main"); put("sourceUrl", "https://example.com/$index")
            putArray("ingredients").addObject().apply { put("name", "材料"); put("amount", BigDecimal("1.234567890123456789")); put("unit", "g") }
        } } }
    })
    private suspend fun withDatabase(block: suspend (RecipeManagerDatabaseV2) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, RecipeManagerDatabaseV2::class.java).build()
        try { block(db) } finally { db.close() }
    }
    private suspend fun reject(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertNotNull("操作を拒否する必要があります", error)
        assertTrue("入力・競合・容量の検証で停止する必要があります: $error", error is IllegalArgumentException || error is IllegalStateException || error is jp.local.recipemanager.core.json.JsonSizeException)
    }
    private fun scalar(db: RecipeManagerDatabaseV2, sql: String): String? = db.openHelper.writableDatabase.query(sql).use { check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0) }
}
