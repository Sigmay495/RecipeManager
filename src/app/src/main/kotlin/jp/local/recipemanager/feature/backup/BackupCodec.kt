package jp.local.recipemanager.feature.backup

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.*
import com.networknt.schema.dialect.Dialects
import jp.local.recipemanager.data.local.*
import jp.local.recipemanager.domain.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import jp.local.recipemanager.core.text.TextNormalizer

data class ParsedBackup(val createdAt: Instant, val rows: BackupRows, val counts: Map<String, Int>)
sealed interface BackupParseResult { data class Success(val backup: ParsedBackup) : BackupParseResult; data class Failure(val message: String) : BackupParseResult }

class BackupCodec(context: Context) {
    private val mapper = ObjectMapper()
    private val schema = run {
        val root = context.assets.open("backup.schema.json").use(mapper::readTree)
        val config = SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()
        SchemaRegistry.withDialect(Dialects.getDraft202012()) { it.schemaRegistryConfig(config) }.getSchema(root)
    }

    fun encode(rows: BackupRows, createdAt: Instant, appVersion: String, masterVersion: String): ByteArray {
        val root = mapper.createObjectNode().apply {
            put("schemaVersion", "1.0"); put("fileType", "full-backup"); put("createdAt", createdAt.toString()); put("appVersion", appVersion); put("masterVersion", masterVersion)
        }
        val data = root.putObject("data")
        data.putArray("recipes").also { a -> rows.recipes.forEach { r -> a.addObject().apply { put("id",r.id);put("name",r.name);put("normalizedName",r.normalizedName);put("category",r.category.json());nullableInt("cookingTimeMinutes",r.cookingTimeMinutes);put("effortLevel",r.effortLevel.name.lowercase());nullableText("sourceUrl",r.sourceUrl);put("memo",r.memo);nullableText("lastCookedAt",r.lastCookedAt?.toString());nullableText("initialLastCookedAt",r.initialLastCookedAt?.toString());putArray("nutritionGroups").also { n -> r.nutritionGroups.forEach { n.add(it.name.lowercase()) } };put("status",r.status.name.lowercase());nullableText("deletedAt",r.deletedAt?.toString());put("createdAt",r.createdAt.toString());put("updatedAt",r.updatedAt.toString()) } } }
        data.putArray("recipeIngredients").also { a -> rows.ingredients.forEach { i -> a.addObject().apply { put("id",i.id);put("recipeId",i.recipeId);put("order",i.displayOrder);put("name",i.name);put("normalizedName",i.normalizedName);nullableDecimal("amount",i.amount);put("unit",i.unit);put("note",i.note);nullableText("foodMasterId",i.foodMasterId) } } }
        data.putArray("weeklyMenus").also { a -> rows.menus.forEach { x -> a.addObject().apply { put("id",x.id);put("weekStart",x.weekStart.toString());put("createdAt",x.createdAt.toString());put("updatedAt",x.updatedAt.toString()) } } }
        data.putArray("menuDays").also { a -> rows.days.forEach { x -> a.addObject().apply { put("id",x.id);put("weeklyMenuId",x.weeklyMenuId);put("date",x.date.toString());put("noMeal",x.noMeal);put("noMealReason",x.noMealReason);put("noMealMemo",x.noMealMemo) } } }
        data.putArray("menuItems").also { a -> rows.menuItems.forEach { x -> a.addObject().apply { put("id",x.id);put("menuDayId",x.menuDayId);put("recipeId",x.recipeId);put("recipeNameSnapshot",x.recipeNameSnapshot);put("role",x.role.json()) } } }
        data.putArray("cookingHistories").also { a -> rows.histories.forEach { x -> a.addObject().apply { put("id",x.id);put("cookedDate",x.cookedDate.toString());put("createdAt",x.createdAt.toString());put("updatedAt",x.updatedAt.toString()) } } }
        data.putArray("historyItems").also { a -> rows.historyItems.forEach { x -> a.addObject().apply { put("id",x.id);put("historyId",x.historyId);put("recipeId",x.recipeId);put("recipeNameSnapshot",x.recipeNameSnapshot);put("role",x.role.json()) } } }
        data.putArray("shoppingLists").also { a -> rows.shoppingLists.forEach { x -> a.addObject().apply { put("id",x.id);put("weekStart",x.weekStart.toString());put("sourceMenuUpdatedAt",x.sourceMenuUpdatedAt.toString());put("createdAt",x.createdAt.toString());put("updatedAt",x.createdAt.toString()) } } }
        data.putArray("shoppingItems").also { a -> rows.shoppingItems.forEach { x -> a.addObject().apply { put("id",x.id);put("shoppingListId",x.shoppingListId);put("name",x.name);put("normalizedName",x.normalizedName);nullableDecimal("amount",x.amount);put("unit",x.unit);putArray("recipeNames").also { names -> x.recipeNames.split('、').filter(String::isNotBlank).forEach(names::add) };put("checked",x.checked);put("excluded",x.excluded) } } }
        data.putArray("userFoodMasters").also { a -> rows.userMasters.forEach { x -> a.addObject().apply { put("id",x.master.id);put("name",x.master.name);put("normalizedName",x.master.normalizedName);putArray("aliases").also { n -> x.aliases.forEach { n.add(it.alias) } };putArray("foodGroups").also { n -> x.groups.map { it.foodGroup.macro() }.distinct().forEach(n::add) };putArray("seasonMonths").also { n -> x.seasons.forEach { n.add(it.month) } };putNull("salesAmount");put("salesUnit",x.master.salesUnit);put("isMainIngredient",x.master.isMainIngredient);put("userEditable",true);put("createdAt",createdAt.toString());put("updatedAt",createdAt.toString()) } } }
        val settings = rows.settings.associate { it.key to it.value }
        data.putObject("settings").apply { put("backupReminderIntervalDays",settings["backupReminderIntervalDays"]?.toIntOrNull() ?: 30);nullableText("lastBackupAt",settings["lastBackupAt"]);put("holidayDataVersion",masterVersion) }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root)
    }

    fun parse(bytes: ByteArray): BackupParseResult {
        if (bytes.size > 50 * 1024 * 1024) return BackupParseResult.Failure("ファイルサイズは50MB以内にしてください")
        val root = try { mapper.readTree(bytes) } catch (_: Exception) { return BackupParseResult.Failure("JSONの構文が正しくありません") }
        if (root.path("schemaVersion").asText() != "1.0") return BackupParseResult.Failure("対応していないバックアップ形式です")
        val errors = schema.validate(root).map { "${it.instanceLocation}: ${it.message}" }
        if (errors.isNotEmpty()) return BackupParseResult.Failure("バックアップ形式が不正です\n${errors.take(5).joinToString("\n")}")
        return runCatching { decode(root) }.fold({ BackupParseResult.Success(it) }, { BackupParseResult.Failure(it.message ?: "復元データが不正です") })
    }

    private fun decode(root: JsonNode): ParsedBackup {
        val d = root.path("data"); val recipes = d.path("recipes").map { n -> RecipeEntity(n.t("id"),n.t("name"),n.t("normalizedName"),category(n.t("category")),n.nullInt("cookingTimeMinutes"),EffortLevel.valueOf(n.t("effortLevel").uppercase()),n.nullText("sourceUrl"),n.t("memo"),n.nullText("lastCookedAt")?.let(LocalDate::parse),n.nullText("initialLastCookedAt")?.let(LocalDate::parse),n.path("nutritionGroups").map { FoodGroup.valueOf(it.asText().uppercase()) }.toSet(),RecipeStatus.valueOf(n.t("status").uppercase()),n.nullText("deletedAt")?.let(Instant::parse),Instant.parse(n.t("createdAt")),Instant.parse(n.t("updatedAt"))) }
        val ingredients = d.path("recipeIngredients").map { n -> RecipeIngredientEntity(n.t("id"),n.t("recipeId"),n.path("order").asInt(),n.t("name"),n.t("normalizedName"),n.nullDecimal("amount"),n.t("unit"),n.t("note"),n.nullText("foodMasterId")) }
        val menus=d.path("weeklyMenus").map{n->WeeklyMenuEntity(n.t("id"),LocalDate.parse(n.t("weekStart")),Instant.parse(n.t("createdAt")),Instant.parse(n.t("updatedAt")))}
        val days=d.path("menuDays").map{n->MenuDayEntity(n.t("id"),n.t("weeklyMenuId"),LocalDate.parse(n.t("date")),n.path("noMeal").asBoolean(),n.t("noMealReason"),n.t("noMealMemo"))}
        val menuItems=d.path("menuItems").map{n->MenuItemEntity(n.t("id"),n.t("menuDayId"),n.t("recipeId"),n.t("recipeNameSnapshot"),role(n.t("role")))}
        val histories=d.path("cookingHistories").map{n->CookingHistoryEntity(n.t("id"),LocalDate.parse(n.t("cookedDate")),Instant.parse(n.t("createdAt")),Instant.parse(n.t("updatedAt")))}
        val historyItems=d.path("historyItems").map{n->HistoryItemEntity(n.t("id"),n.t("historyId"),n.t("recipeId"),n.t("recipeNameSnapshot"),role(n.t("role")))}
        val lists=d.path("shoppingLists").map{n->ShoppingListEntity(n.t("id"),LocalDate.parse(n.t("weekStart")),Instant.parse(n.t("sourceMenuUpdatedAt")),Instant.parse(n.t("createdAt")))}
        val shopping=d.path("shoppingItems").map{n->ShoppingItemEntity(n.t("id"),n.t("shoppingListId"),n.t("name"),n.t("normalizedName"),n.nullDecimal("amount"),n.t("unit"),n.path("recipeNames").joinToString("、"){it.asText()},n.path("checked").asBoolean(),n.path("excluded").asBoolean())}
        val masters=d.path("userFoodMasters").map{n-> val id=n.t("id"); FoodMasterWithDetails(FoodMasterEntity(id,n.t("name"),n.t("normalizedName"),n.t("salesUnit"),n.path("isMainIngredient").asBoolean(),true),n.path("aliases").map{FoodMasterAliasEntity(id,it.asText(),TextNormalizer.normalize(it.asText()))},n.path("foodGroups").map{FoodMasterGroupEntity(id,macroGroup(it.asText()))},n.path("seasonMonths").map{FoodMasterSeasonEntity(id,it.asInt())})}
        val s=d.path("settings"); val settings=listOf(SettingEntity("backupReminderIntervalDays",s.path("backupReminderIntervalDays").asInt().toString()),SettingEntity("lastBackupAt",s.nullText("lastBackupAt").orEmpty()))
        val rows=BackupRows(recipes,ingredients,menus,days,menuItems,histories,historyItems,lists,shopping,masters,settings)
        validateReferences(rows)
        val counts=mapOf("レシピ" to recipes.size,"献立" to menus.size,"履歴" to histories.size,"買い物リスト" to lists.size)
        return ParsedBackup(Instant.parse(root.t("createdAt")),rows,counts)
    }

    private fun validateReferences(r: BackupRows) {
        val recipes=r.recipes.map{it.id}.toSet(); val masterIds=r.userMasters.map{it.master.id}.toSet()
        require(r.ingredients.all{it.recipeId in recipes}){"材料から存在しないレシピへの参照があります"}
        val menus=r.menus.map{it.id}.toSet(); require(r.days.all{it.weeklyMenuId in menus}){"献立日の参照が切れています"}
        val days=r.days.map{it.id}.toSet(); require(r.menuItems.all{it.menuDayId in days && it.recipeId in recipes}){"献立料理の参照が切れています"}
        val histories=r.histories.map{it.id}.toSet(); require(r.historyItems.all{it.historyId in histories && it.recipeId in recipes}){"履歴の参照が切れています"}
        val lists=r.shoppingLists.map{it.id}.toSet(); require(r.shoppingItems.all{it.shoppingListId in lists}){"買い物リストの参照が切れています"}
        require(r.ingredients.filter{it.foodMasterId?.startsWith("builtin-") != true}.all{it.foodMasterId==null || it.foodMasterId in masterIds}){"利用者食材マスターの参照が切れています"}
    }

    private fun ObjectNode.nullableText(k:String,v:String?){if(v==null)putNull(k)else put(k,v)}; private fun ObjectNode.nullableInt(k:String,v:Int?){if(v==null)putNull(k)else put(k,v)}; private fun ObjectNode.nullableDecimal(k:String,v:BigDecimal?){if(v==null)putNull(k)else put(k,v)}
    private fun JsonNode.t(k:String)=path(k).asText(); private fun JsonNode.nullText(k:String)=get(k)?.takeUnless(JsonNode::isNull)?.asText(); private fun JsonNode.nullInt(k:String)=get(k)?.takeUnless(JsonNode::isNull)?.asInt(); private fun JsonNode.nullDecimal(k:String)=get(k)?.takeUnless(JsonNode::isNull)?.decimalValue()
    private fun RecipeCategory.json()=name.lowercase(); private fun MenuRole.json()=name.lowercase(); private fun category(v:String)=RecipeCategory.valueOf(v.uppercase()); private fun role(v:String)=MenuRole.valueOf(v.uppercase())
    private fun FoodGroup.macro()=when(this){FoodGroup.GRAIN->"staple";FoodGroup.MEAT,FoodGroup.SEAFOOD,FoodGroup.EGG,FoodGroup.SOY->"protein";FoodGroup.VEGETABLE,FoodGroup.MUSHROOM,FoodGroup.SEAWEED->"vegetable";FoodGroup.DAIRY->"dairy";FoodGroup.FRUIT->"fruit";else->"other"}
    private fun macroGroup(v:String)=when(v){"staple"->FoodGroup.GRAIN;"protein"->FoodGroup.SOY;"vegetable"->FoodGroup.VEGETABLE;"dairy"->FoodGroup.DAIRY;"fruit"->FoodGroup.FRUIT;else->FoodGroup.SEASONING}
}
