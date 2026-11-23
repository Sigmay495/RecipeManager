import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.dialect.Dialects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Standalone contract checks; never opens the app database or modifies samples. */
public class SchemaContractCheck {
    static final ObjectMapper mapper = new ObjectMapper();
    static int checks;
    static Schema compile(JsonNode root) {
        var config = SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
        return SchemaRegistry.withDialect(Dialects.getDraft202012(), b -> b.schemaRegistryConfig(config)).getSchema(root);
    }
    static void expect(String label, Schema schema, JsonNode data, boolean valid) {
        var errors = schema.validate(data);
        if (errors.isEmpty() != valid) throw new AssertionError(label + ": " + errors);
        checks++;
        System.out.println("PASS " + label);
    }
    static ObjectNode row(JsonNode root, String array, int index) {
        return (ObjectNode)root.path("data").path(array).get(index);
    }
    static void reject(String name, Schema schema, JsonNode sample, Consumer<ObjectNode> mutation) {
        ObjectNode changed = sample.deepCopy();
        mutation.accept(changed);
        expect(name, schema, changed, false);
    }
    static void walkRefs(JsonNode schema, JsonNode node) {
        if (node.isObject()) {
            if (node.has("$ref")) {
                String value = node.get("$ref").asText();
                if (!value.startsWith("#/")) throw new AssertionError("Nonlocal ref: " + value);
                if (schema.at(value.substring(1)).isMissingNode()) throw new AssertionError("Unresolved ref: " + value);
            }
            if (node.path("type").asText().equals("object") && !node.has("additionalProperties"))
                throw new AssertionError("Object without additionalProperties rule");
        }
        if (node.isContainerNode()) node.forEach(child -> walkRefs(schema, child));
    }
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        Path schemas = repo.resolve("document/schemas");
        byte[] oldBytes = Files.readAllBytes(schemas.resolve("backup.schema.json"));
        if (!java.util.Arrays.equals(oldBytes, Files.readAllBytes(schemas.resolve("backup-1.0.schema.json"))))
            throw new AssertionError("Legacy schema was not preserved byte-for-byte");
        checks++;
        JsonNode oldTree = mapper.readTree(oldBytes);
        JsonNode newTree = mapper.readTree(schemas.resolve("backup-2.0.schema.json").toFile());
        JsonNode importTree = mapper.readTree(schemas.resolve("recipe-import.schema.json").toFile());
        try (var metaStream = SchemaContractCheck.class.getResourceAsStream("/draft/2020-12/schema")) {
            if (metaStream == null) throw new AssertionError("Bundled Draft 2020-12 metaschema missing");
            Schema meta = compile(mapper.readTree(metaStream));
            expect("1.0 Draft 2020-12 metaschema",meta,oldTree,true);
            expect("2.0 Draft 2020-12 metaschema",meta,newTree,true);
            expect("Import Draft 2020-12 metaschema",meta,importTree,true);
        }
        for (JsonNode tree : new JsonNode[]{oldTree,newTree,importTree}) { walkRefs(tree,tree); compile(tree); checks++; }
        Schema v2 = compile(newTree);
        Schema v1 = compile(oldTree);
        Schema recipeImport = compile(importTree);
        JsonNode sample = mapper.readTree(repo.resolve("document/samples/backup-2.0.sample.json").toFile());
        expect("complete 2.0 sample",v2,sample,true);
        expect("2.0 not accepted as 1.0",v1,sample,false);
        expect("existing Import 1.0 sample",recipeImport,mapper.readTree(repo.resolve("document/samples/recipe-import.sample.json").toFile()),true);
        JsonNode roundTrip = mapper.readTree(mapper.writeValueAsBytes(sample));
        if (!sample.equals(roundTrip)) throw new AssertionError("JSON round-trip changed sample");
        expect("JSON round-trip preserves aliases, quantities, drafts",v2,roundTrip,true);
        reject("unsupported version",v2,sample,r->r.put("schemaVersion","3.0"));
        reject("wrong fileType",v2,sample,r->r.put("fileType","recipe-import"));
        reject("unknown top-level property",v2,sample,r->r.put("extra",true));
        reject("missing required aliases array",v2,sample,r->((ObjectNode)r.path("data")).remove("userFoodAliases"));
        reject("invalid UUID",v2,sample,r->row(r,"recipes",0).put("id","not-a-uuid"));
        reject("legacy history ID outside history",v2,sample,r->row(r,"recipes",0).put("id","history-2026-09-28"));
        reject("invalid calendar date",v2,sample,r->row(r,"menuDays",0).put("date","2026-02-30"));
        reject("non-UTC timestamp",v2,sample,r->r.put("createdAt","2026-10-03T09:00:00+09:00"));
        reject("invalid UTC timestamp",v2,sample,r->r.put("createdAt","2026-10-03T99:00:00Z"));
        reject("retired priority enum typo",v2,sample,r->row(r,"recipes",0).put("proposalPriority","deleted"));
        reject("revision zero",v2,sample,r->row(r,"recipes",0).put("revision",0));
        reject("revision fraction",v2,sample,r->row(r,"recipes",0).put("revision",1.5));
        reject("old recipe status",v2,sample,r->row(r,"recipes",0).put("status","active"));
        reject("old hand-entered date",v2,sample,r->row(r,"recipes",0).put("initialLastCookedAt","2026-10-01"));
        reject("cache not exported",v2,sample,r->row(r,"recipes",0).putNull("lastCookedAt"));
        reject("numeric amount loses decimal contract",v2,sample,r->row(r,"recipeIngredients",0).put("amount",1.25));
        reject("zero amount",v2,sample,r->row(r,"recipeIngredients",0).put("amount","0.000"));
        reject("negative amount",v2,sample,r->row(r,"recipeIngredients",0).put("amount","-1"));
        reject("exponential amount",v2,sample,r->row(r,"recipeIngredients",0).put("amount","1e3"));
        reject("unknown quantity must be flagged",v2,sample,r->row(r,"shoppingItems",2).put("hasUnknownAmount",false));
        reject("unknown flag is required",v2,sample,r->row(r,"shoppingItems",1).remove("hasUnknownAmount"));
        reject("fingerprint unknown requires regeneration",v2,sample,r->row(r,"shoppingLists",0).put("needsRegeneration",false));
        reject("cooked requires history",v2,sample,r->row(r,"planOutcomes",0).putNull("historyItemId"));
        reject("changed requires history",v2,sample,r->row(r,"planOutcomes",1).putNull("historyItemId"));
        reject("postponed cannot contain history",v2,sample,r->row(r,"planOutcomes",2).put("historyItemId",row(r,"historyItems",0).path("id").asText()));
        reject("confirmed postponement requires target",v2,sample,r->row(r,"postponedPlans",0).putNull("targetMenuItemId"));
        reject("pending postponement cannot contain target",v2,sample,r->row(r,"postponedPlans",1).put("targetMenuItemId",row(r,"menuItems",0).path("id").asText()));
        reject("noMeal cannot be day locked",v2,sample,r->row(r,"menuDays",5).put("dayLocked",true));
        reject("unknown origin",v2,sample,r->row(r,"menuDays",0).put("requirementOrigin","unknown"));
        reject("aliases cannot be nested in master",v2,sample,r->row(r,"userFoodMasters",0).putArray("aliases"));
        reject("old sales unit",v2,sample,r->row(r,"userFoodMasters",0).put("salesUnit","個"));
        reject("unknown food group",v2,sample,r->row(r,"userFoodMasters",0).putArray("foodGroups").add("unknown"));
        reject("duplicate food groups",v2,sample,r->row(r,"userFoodMasters",0).putArray("foodGroups").add("egg").add("egg"));
        reject("unknown draft kind",v2,sample,r->row(r,"drafts",0).put("kind","unknown"));
        reject("unsupported draft payload version",v2,sample,r->row(r,"drafts",0).put("payloadVersion",2));
        reject("wrong import singleton key",v2,sample,r->row(r,"drafts",1).put("targetKey","new"));
        reject("wrong recipe target key",v2,sample,r->row(r,"drafts",0).put("targetKey","singleton"));
        reject("typed draft payload required",v2,sample,r->row(r,"drafts",0).putObject("payload"));
        reject("unknown draft payload property",v2,sample,r->((ObjectNode)row(r,"drafts",0).path("payload")).put("extra",true));
        reject("conflict reason required",v2,sample,r->((ObjectNode)row(r,"drafts",0).path("payload").path("conflicts").get(0)).remove("reason"));
        reject("draft form preserves strings only",v2,sample,r->((ObjectNode)row(r,"drafts",0).path("payload").path("form")).put("cookingTimeMinutes",15));
        reject("menu draft requires seven days",v2,sample,r->((com.fasterxml.jackson.databind.node.ArrayNode)row(r,"drafts",2).path("payload").path("days")).remove(6));
        ObjectNode empty = sample.deepCopy();
        var emptyData=(ObjectNode)empty.path("data");
        newTree.path("properties").path("data").path("required").forEach(k->{if(!k.asText().equals("settings"))emptyData.putArray(k.asText());});
        expect("empty user data",v2,empty,true);
        ObjectNode legacy = mapper.createObjectNode();
        legacy.put("schemaVersion","1.0");legacy.put("fileType","full-backup");legacy.put("createdAt","2026-10-03T00:00:00Z");legacy.put("appVersion","1.00");legacy.put("masterVersion","2026.09");
        var legacyData=legacy.putObject("data");
        oldTree.path("properties").path("data").path("required").forEach(k->{if(!k.asText().equals("settings"))legacyData.putArray(k.asText());});
        legacyData.set("settings",sample.path("data").path("settings").deepCopy());
        // Actual v1 codec emits legacy date-based history IDs and nested aliases.
        legacyData.withArray("cookingHistories").add(sample.path("data").path("cookingHistories").get(0).deepCopy());
        ObjectNode oldMaster=(ObjectNode)sample.path("data").path("userFoodMasters").get(0).deepCopy();
        oldMaster.remove("baseUnit");oldMaster.remove("revision");oldMaster.putArray("aliases").add("たまご");oldMaster.putNull("salesAmount");oldMaster.put("salesUnit","個");oldMaster.putArray("foodGroups").add("protein");
        legacyData.withArray("userFoodMasters").add(oldMaster);
        expect("legacy history ID and nested material alias",v1,legacy,true);
        expect("legacy must be converted before v2",v2,legacy,false);
        ObjectNode builtin=sample.deepCopy();
        row(builtin,"recipeIngredients",0).put("foodMasterId","builtin-egg");
        expect("builtin food reference structurally allowed",v2,builtin,true);
        ObjectNode unresolved=sample.deepCopy();
        row(unresolved,"menuItems",0).put("recipeId","00000000-0000-4000-8000-999999999999");
        expect("reference existence deliberately belongs to Adapter",v2,unresolved,true);
        System.out.println("TOTAL " + checks + " checks passed. Adapter/Migration not implemented or tested.");
    }
}
