package net.bladewatch.app.server;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for ModelsApiHandler pure validation functions (BladeWatch-e5ou).
 * Covers: valid color, invalid color variants, valid model id, unknown id,
 * traversal attempt via model_id.
 */
public class ModelsApiHandlerValidationTest {

    /** Minimal manifest with one known model id. */
    private static JSONObject makeManifest(String... ids) throws Exception {
        JSONArray models = new JSONArray();
        for (String id : ids) {
            JSONObject m = new JSONObject();
            m.put("id", id);
            m.put("file", id + ".glb");
            models.put(m);
        }
        JSONObject manifest = new JSONObject();
        manifest.put("version", 1);
        manifest.put("default", ids.length > 0 ? ids[0] : "");
        manifest.put("models", models);
        return manifest;
    }

    // ==================== Color validation ====================

    @Test
    public void validColor_sixHexDigits() {
        Assert.assertTrue(ModelsApiHandler.isValidColor("#E8E8EC"));
    }

    @Test
    public void validColor_lowerCase() {
        Assert.assertTrue(ModelsApiHandler.isValidColor("#aabbcc"));
    }

    @Test
    public void validColor_allZeros() {
        Assert.assertTrue(ModelsApiHandler.isValidColor("#000000"));
    }

    @Test
    public void invalidColor_wrongLetters_GGGGGG() {
        Assert.assertFalse(ModelsApiHandler.isValidColor("#GGGGGG"));
    }

    @Test
    public void invalidColor_missingHash() {
        Assert.assertFalse(ModelsApiHandler.isValidColor("E8E8EC"));
    }

    @Test
    public void invalidColor_eightChars() {
        Assert.assertFalse(ModelsApiHandler.isValidColor("#E8E8EC00"));
    }

    @Test
    public void invalidColor_null() {
        Assert.assertFalse(ModelsApiHandler.isValidColor(null));
    }

    @Test
    public void invalidColor_empty() {
        Assert.assertFalse(ModelsApiHandler.isValidColor(""));
    }

    // ==================== Model ID validation ====================

    @Test
    public void validModelId_knownId() throws Exception {
        JSONObject manifest = makeManifest("destroyer", "seal");
        Assert.assertTrue(ModelsApiHandler.isValidModelId(manifest, "destroyer"));
        Assert.assertTrue(ModelsApiHandler.isValidModelId(manifest, "seal"));
    }

    @Test
    public void invalidModelId_unknownId() throws Exception {
        JSONObject manifest = makeManifest("destroyer");
        Assert.assertFalse(ModelsApiHandler.isValidModelId(manifest, "unknown-car"));
    }

    @Test
    public void invalidModelId_traversalAttempt() throws Exception {
        JSONObject manifest = makeManifest("destroyer");
        // A traversal id like "../etc/passwd" must not match any manifest entry
        Assert.assertFalse(ModelsApiHandler.isValidModelId(manifest, "../etc/passwd"));
    }

    @Test
    public void invalidModelId_emptyString() throws Exception {
        JSONObject manifest = makeManifest("destroyer");
        Assert.assertFalse(ModelsApiHandler.isValidModelId(manifest, ""));
    }

    @Test
    public void invalidModelId_nullManifest() {
        Assert.assertFalse(ModelsApiHandler.isValidModelId(null, "destroyer"));
    }

    @Test
    public void invalidModelId_nullId() throws Exception {
        JSONObject manifest = makeManifest("destroyer");
        Assert.assertFalse(ModelsApiHandler.isValidModelId(manifest, null));
    }
}
