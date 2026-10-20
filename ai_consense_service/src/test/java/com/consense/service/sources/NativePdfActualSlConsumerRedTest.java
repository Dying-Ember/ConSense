package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Root-exported actual authored public wires; all five requirements are positive.
 * This class does not claim Java Long document registration or source quality. */
class NativePdfActualSlConsumerRedTest {
    @Test void actualSlProseEnvelopeMustPass() throws Exception {
        ObjectNode wire = wire("prose"), before = wire.deepCopy();
        assertDoesNotThrow(() -> NativePdfCanonicalContract.validateEnvelope(wire));
        assertEquals(before, wire);
    }

    @Test void actualSlTableAndPageAssetEnvelopeMustPass() throws Exception {
        ObjectNode wire = wire("table"), before = wire.deepCopy();
        assertFalse(wire.path("assetRefs").isEmpty());
        assertTrue(wire.at("/chunks/0/nativeTableGroupsRef").isObject());
        assertDoesNotThrow(() -> NativePdfCanonicalContract.validateEnvelope(wire));
        assertEquals(before, wire);
    }

    @Test void actualSlProseStandaloneChunkMustPass() throws Exception {
        ObjectNode wire = wire("prose");
        assertDoesNotThrow(() -> NativePdfCanonicalContract.validateChunk(wire.path("chunks").get(0), wire.at("/source/sourceSha256").asText()));
    }

    @Test void actualSlTableAndPageAssetStandaloneChunkMustPass() throws Exception {
        ObjectNode wire = wire("table");
        assertDoesNotThrow(() -> NativePdfCanonicalContract.validateChunk(wire.path("chunks").get(0), wire.at("/source/sourceSha256").asText()));
    }

    @Test void currentPythonCapabilityDeclarationMustPass() {
        // Authored exact declaration of current oer_native_pdf_server.py; no HTTP call.
        ObjectNode oer = JsonUtils.mapper().createObjectNode(), c = oer.putObject("nativePdfCanonical");
        c.put("schema", NativePdfCanonicalContract.SCHEMA).put("producerKind", "native_pdf")
                .put("projectionVersion", "source-reviewed-native-pdf-cas-public-projection-v1")
                .put("storageShape", "cas-native-pdf-joint-adopted-public-v1")
                .put("projectionEndpoint", "/project/native-pdf").put("projectionInputs", "actual-cas-three-descriptors-only")
                .put("artifactReadValidation", "actual-cas-every-read").put("maximumMessageBytes", 16777216)
                .put("tableAndAppearanceText", "source_reviewed_whole_table_groups; appearance_text_unsupported")
                .put("visualReturnStatus", "not_published_by_native_pdf_transport");
        assertDoesNotThrow(() -> NativePdfCanonicalContract.validateCapability(oer));
    }

    private static ObjectNode wire(String kind) throws Exception {
        byte[] manifestBytes = bytes("public-wires.json");
        assertEquals(36885, manifestBytes.length);
        assertEquals("6626652c0218c36d2df5128f205ce677181a859f8a99fcc3db119699b7b2a12d", CanonicalJson.sha(manifestBytes));
        ObjectNode manifest = (ObjectNode)JsonUtils.parse(new String(manifestBytes, StandardCharsets.UTF_8));
        assertEquals("EXPORTED_SAVED_INTERNALS_PUBLISHED_AND_COLD_HYDRATED", manifest.path("status").asText());
        byte[] wire = bytes(kind + "-public-wire.json");
        assertEquals(manifest.at("/publicWires/" + kind + "/byteCount").asLong(), (long)wire.length);
        assertEquals(manifest.at("/publicWires/" + kind + "/sha256").asText(), CanonicalJson.sha(wire));
        ObjectNode parsed = (ObjectNode)JsonUtils.parse(new String(wire, StandardCharsets.UTF_8));
        assertFalse(parsed.path("humanConfirmed").asBoolean(true));
        assertFalse(parsed.path("semanticScopeVerified").asBoolean(true));
        assertFalse(parsed.at("/transportValidation/qualityCertified").asBoolean(true));
        return parsed;
    }

    private static byte[] bytes(String name) throws Exception {
        try (InputStream input = NativePdfActualSlConsumerRedTest.class.getResourceAsStream("/oer/native-pdf-actual-sl/" + name)) {
            assertNotNull(input, name);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] block = new byte[8192]; int count;
            while ((count = input.read(block)) != -1) output.write(block, 0, count);
            return output.toByteArray();
        }
    }
}
