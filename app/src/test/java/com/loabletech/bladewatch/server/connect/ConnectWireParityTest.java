package net.bladewatch.app.server.connect;

import com.google.protobuf.util.JsonFormat;

import net.bladewatch.app.grpc.v1.GetAcDiagnosticsResponse;
import net.bladewatch.app.grpc.v1.GetChargeCapResponse;
import net.bladewatch.app.grpc.v1.GetChargingScheduleResponse;
import net.bladewatch.app.grpc.v1.GetDatesResponse;
import net.bladewatch.app.grpc.v1.GetGpsLocationResponse;
import net.bladewatch.app.grpc.v1.GetGpsTraceResponse;
import net.bladewatch.app.grpc.v1.GetInflightStatusResponse;
import net.bladewatch.app.grpc.v1.GetStatsResponse;
import net.bladewatch.app.grpc.v1.GetSummaryResponse;
import net.bladewatch.app.grpc.v1.GetSurveillanceStatusResponse;
import net.bladewatch.app.grpc.v1.GetTelemetryRequest;
import net.bladewatch.app.grpc.v1.GetTelemetryResponse;
import net.bladewatch.app.grpc.v1.ListRecordingsResponse;
import net.bladewatch.app.grpc.v1.MoveWindowRequest;
import net.bladewatch.app.grpc.v1.RecordingEntry;
import net.bladewatch.app.grpc.v1.RecordingType;
import net.bladewatch.app.grpc.v1.SetAdasRequest;
import net.bladewatch.app.grpc.v1.SetChargeCapRequest;
import net.bladewatch.app.grpc.v1.SetClimateRequest;
import net.bladewatch.app.grpc.v1.SetConfigRequest;
import net.bladewatch.app.grpc.v1.SetLightsRequest;
import net.bladewatch.app.grpc.v1.SetQualityRequest;
import net.bladewatch.app.grpc.v1.SetQualityResponse;
import net.bladewatch.app.grpc.v1.SetSeatRequest;
import net.bladewatch.app.grpc.v1.SetViewModeRequest;

import org.junit.Assert;
import org.junit.Test;

/**
 * Per-RPC wire-parity contract tests for the Connect/gRPC <-> REST bridge
 * (epic BladeWatch-86a).
 *
 * <p>The Connect dispatcher passes the request body verbatim to each REST impl
 * and writes the impl's JSON response verbatim to the wire — there is no
 * server-side proto re-encoding. Parity therefore depends entirely on the
 * client's JsonFormat: REQUESTS are serialized with {@link JsonFormat#printer()}
 * (camelCase json-names, default scalars OMITTED) and RESPONSES are parsed with
 * {@link JsonFormat#parser()}{@code .ignoringUnknownFields()} (unknown keys
 * dropped to defaults). These tests use the same JsonFormat config the client
 * uses, so they lock the exact JSON shapes each handler/impl must read/emit.
 *
 * <p>Each "drops ..." test feeds the pre-fix RAW REST shape and asserts the
 * proto field silently defaults — i.e. reverting a fix re-breaks the contract.
 */
public class ConnectWireParityTest {

    // Mirror the connect-kotlin Google-Java JSON strategy: compact output, default
    // scalars omitted on print, unknown fields ignored on parse.
    private static final JsonFormat.Printer PRINTER =
            JsonFormat.printer().omittingInsignificantWhitespace();
    private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();

    // The Android org.json stub is not available in JVM unit tests, so the
    // request tests assert against the raw compact JSON string directly.
    private static String json(com.google.protobuf.MessageOrBuilder msg) throws Exception {
        return PRINTER.print(msg);
    }

    private static void assertHasKey(String json, String key) {
        Assert.assertTrue("expected key \"" + key + "\" in " + json,
                json.contains("\"" + key + "\":"));
    }

    private static void assertNoKey(String json, String key) {
        Assert.assertFalse("unexpected key \"" + key + "\" in " + json,
                json.contains("\"" + key + "\":"));
    }

    private static void assertField(String json, String pair) {
        Assert.assertTrue("expected " + pair + " in " + json, json.contains(pair));
    }

    // ==================== REQUEST serialization (proto -> JSON) ====================

    @Test
    public void setClimate_emitsProtoCamelCaseKeys() throws Exception {
        // BladeWatch-4xo: handler must read setpointC/fanLevel/maxCooling/
        // restoreAcOn/restoreTempC/restoreFanLevel, not temp/fan/restoreTemp.
        String j = json(SetClimateRequest.newBuilder()
                .setAction("max_cooling")
                .setMaxCooling(true)
                .setSetpointC(24)
                .setFanLevel(5)
                .setRestoreAcOn(true)
                .setRestoreTempC(18)
                .setRestoreFanLevel(2)
                .build());
        assertHasKey(j, "setpointC");
        assertHasKey(j, "fanLevel");
        assertHasKey(j, "maxCooling");
        assertHasKey(j, "restoreAcOn");
        assertHasKey(j, "restoreTempC");
        assertHasKey(j, "restoreFanLevel");
        // Legacy web-UI keys must NOT appear in the proto wire form.
        assertNoKey(j, "temp");
        assertNoKey(j, "fan");
        assertNoKey(j, "restoreTemp");
    }

    @Test
    public void setClimate_disableMaxCooling_omitsFalseScalar() throws Exception {
        // Disabling max cooling => maxCooling=false, omitted by JsonFormat. The
        // handler must treat absence-with-restore-params as a disable.
        String j = json(SetClimateRequest.newBuilder()
                .setAction("max_cooling")
                .setMaxCooling(false)
                .setRestoreTempC(22)
                .build());
        assertNoKey(j, "maxCooling");
        assertHasKey(j, "restoreTempC");
    }

    @Test
    public void setSeat_emitsSeatIndex() throws Exception {
        // BladeWatch-rxe: handler must read seatIndex, not position.
        String j = json(SetSeatRequest.newBuilder()
                .setSeatIndex(2).setAction("heating").setLevel(1).build());
        assertField(j, "\"seatIndex\":2");
        assertNoKey(j, "position");
    }

    @Test
    public void moveWindow_emitsWindowIndexAndDirection() throws Exception {
        // BladeWatch-flt: handler must read windowIndex/direction, not area/command.
        String j = json(MoveWindowRequest.newBuilder()
                .setWindowIndex(2).setDirection("open").setTargetPercent(50).build());
        assertField(j, "\"windowIndex\":2");
        assertField(j, "\"direction\":\"open\"");
        assertField(j, "\"targetPercent\":50");
        assertNoKey(j, "area");
        assertNoKey(j, "command");
    }

    @Test
    public void moveWindow_allWindows_omitsZeroIndex() throws Exception {
        // windowIndex=0 ("all") is a default scalar and is omitted; the handler
        // must default the area to 0 when windowIndex is absent.
        String j = json(MoveWindowRequest.newBuilder()
                .setWindowIndex(0).setDirection("open").build());
        assertNoKey(j, "windowIndex");
        assertField(j, "\"direction\":\"open\"");
    }

    @Test
    public void setViewMode_emitsCamelCaseViewMode() throws Exception {
        // BladeWatch-hxu: impl must read viewMode, not view_mode.
        String j = json(SetViewModeRequest.newBuilder().setViewMode(3).build());
        assertField(j, "\"viewMode\":3");
        assertNoKey(j, "view_mode");
    }

    @Test
    public void getTelemetry_emitsTripId() throws Exception {
        // BladeWatch-re7: impl must read tripId, not id. (int64 prints as a string.)
        String j = json(GetTelemetryRequest.newBuilder().setTripId(5).build());
        assertHasKey(j, "tripId");
        assertNoKey(j, "id");
    }

    @Test
    public void setChargeCap_disable_emitsEnabledFalse() throws Exception {
        // BladeWatch-ljk: enabled is `optional bool` so an explicit false is put
        // on the wire (a plain proto3 bool would omit it, making disable a no-op).
        String j = json(SetChargeCapRequest.newBuilder().setEnabled(false).build());
        assertField(j, "\"enabled\":false");
    }

    @Test
    public void tripsSetConfig_emitsPresenceFlags() throws Exception {
        // BladeWatch-s9h: handler must honor hasEnabled/hasElectricityRate so a
        // false/0 value can be saved despite default-scalar omission.
        String j = json(SetConfigRequest.newBuilder()
                .setEnabled(false).setHasEnabled(true)
                .setElectricityRate(0.0).setHasElectricityRate(true)
                .build());
        assertField(j, "\"hasEnabled\":true");
        assertField(j, "\"hasElectricityRate\":true");
        assertNoKey(j, "enabled");
        assertNoKey(j, "electricityRate");
    }

    @Test
    public void setQuality_emitsCodecAndFps() throws Exception {
        // BladeWatch-j9ir: handler must read codec/fps, not recordingCodec/cameraFps.
        String j = json(SetQualityRequest.newBuilder()
                .setCodec("H265").setFps(20).setRecordingQuality("HIGH").build());
        assertField(j, "\"codec\":\"H265\"");
        assertField(j, "\"fps\":20");
        assertNoKey(j, "recordingCodec");
        assertNoKey(j, "cameraFps");
    }

    // ==================== RESPONSE parsing (JSON -> proto) ====================

    @Test
    public void surveillanceStatus_flatShapePopulates() throws Exception {
        // BladeWatch-dyp: impl reshapes the nested REST {status:{...}} to flat keys.
        GetSurveillanceStatusResponse.Builder b = GetSurveillanceStatusResponse.newBuilder();
        PARSER.merge("{\"pipelineRunning\":true,\"surveillanceActive\":true}", b);
        Assert.assertTrue(b.getPipelineRunning());
        Assert.assertTrue(b.getSurveillanceActive());
    }

    @Test
    public void surveillanceStatus_nestedRestShapeDropsFields() throws Exception {
        // Pre-fix RAW shape: success + nested status are unknown keys -> defaults.
        GetSurveillanceStatusResponse.Builder b = GetSurveillanceStatusResponse.newBuilder();
        PARSER.merge("{\"success\":true,\"status\":{\"active\":true,\"enabled\":true}}", b);
        Assert.assertFalse("pipeline_running drops", b.getPipelineRunning());
        Assert.assertFalse("surveillance_active drops", b.getSurveillanceActive());
    }

    @Test
    public void gpsLocation_locationJsonPopulates() throws Exception {
        // BladeWatch-yds: impl moves the location object into the locationJson string.
        GetGpsLocationResponse.Builder b = GetGpsLocationResponse.newBuilder();
        PARSER.merge("{\"success\":true,\"locationJson\":\"{\\\"lat\\\":1.0}\",\"googleMapsUrl\":\"u\"}", b);
        Assert.assertFalse("location_json populated", b.getLocationJson().isEmpty());
    }

    @Test
    public void gpsLocation_rawLocationKeyDropped() throws Exception {
        GetGpsLocationResponse.Builder b = GetGpsLocationResponse.newBuilder();
        PARSER.merge("{\"success\":true,\"location\":{\"lat\":1.0}}", b);
        Assert.assertTrue("location_json drops", b.getLocationJson().isEmpty());
    }

    @Test
    public void acDiagnostics_rawJsonPopulates() throws Exception {
        // BladeWatch-7i3: impl moves the ac object into the rawJson string.
        GetAcDiagnosticsResponse.Builder b = GetAcDiagnosticsResponse.newBuilder();
        PARSER.merge("{\"success\":true,\"rawJson\":\"{\\\"k\\\":1}\"}", b);
        Assert.assertFalse(b.getRawJson().isEmpty());
    }

    @Test
    public void listRecordings_typeEnumHasEventsAndTotalPopulate() throws Exception {
        // BladeWatch-5lf: impl maps type->enum name, derives hasEvents, totalCount->total.
        ListRecordingsResponse.Builder b = ListRecordingsResponse.newBuilder();
        PARSER.merge("{\"recordings\":[{\"type\":\"RECORDING_TYPE_SENTRY\",\"hasEvents\":true}],"
                + "\"total\":5}", b);
        Assert.assertEquals(5, b.getTotal());
        RecordingEntry e = b.getRecordings(0);
        Assert.assertEquals(RecordingType.RECORDING_TYPE_SENTRY, e.getType());
        Assert.assertTrue(e.getHasEvents());
    }

    @Test
    public void listRecordings_rawShapeDropsTotal() throws Exception {
        // Pre-fix RAW shape: totalCount is an unknown key -> total stays 0.
        ListRecordingsResponse.Builder b = ListRecordingsResponse.newBuilder();
        PARSER.merge("{\"recordings\":[],\"totalCount\":5}", b);
        Assert.assertEquals("total drops", 0, b.getTotal());
    }

    @Test
    public void getDates_repeatedStringPopulates() throws Exception {
        // BladeWatch-9rf: impl maps each {date,...} object to its date string.
        GetDatesResponse.Builder b = GetDatesResponse.newBuilder();
        PARSER.merge("{\"dates\":[\"2026-06-08\",\"2026-06-07\"]}", b);
        Assert.assertEquals(2, b.getDatesCount());
        Assert.assertEquals("2026-06-08", b.getDates(0));
    }

    @Test
    public void inflightStatus_statusPopulates() throws Exception {
        // BladeWatch-820: impl derives a status string from inflight/filename.
        GetInflightStatusResponse.Builder b = GetInflightStatusResponse.newBuilder();
        PARSER.merge("{\"status\":\"recording\"}", b);
        Assert.assertEquals("recording", b.getStatus());
    }

    @Test
    public void inflightStatus_rawShapeDropsAllFields() throws Exception {
        // Pre-fix RAW shape: filename/inflight/sizeBytes are unknown -> empty status.
        GetInflightStatusResponse.Builder b = GetInflightStatusResponse.newBuilder();
        PARSER.merge("{\"filename\":\"a.mp4\",\"inflight\":true,\"sizeBytes\":10}", b);
        Assert.assertTrue("status drops", b.getStatus().isEmpty());
    }

    @Test
    public void setQualityResponse_recordingQualityAndCodecPopulate() throws Exception {
        // BladeWatch-j9ir: impl maps recordingBitrate->recordingQuality; recordingCodec
        // already maps to codec (json_name=recordingCodec).
        SetQualityResponse.Builder b = SetQualityResponse.newBuilder();
        PARSER.merge("{\"success\":true,\"recordingQuality\":\"HIGH\",\"recordingCodec\":\"H265\"}", b);
        Assert.assertEquals("HIGH", b.getRecordingQuality());
        Assert.assertEquals("H265", b.getCodec());
    }

    @Test
    public void chargeCap_triStateNotProbedStaysUnset() throws Exception {
        // BladeWatch-ygg0: optional fields let a parsed JSON null stay unset, so the
        // client can tell "not probed" from a real 0/false.
        GetChargeCapResponse.Builder notProbed = GetChargeCapResponse.newBuilder();
        PARSER.merge("{\"success\":true,\"percent\":null,\"enabled\":null,\"supported\":null}", notProbed);
        Assert.assertFalse("percent unset when null", notProbed.hasPercent());
        Assert.assertFalse("enabled unset when null", notProbed.hasEnabled());
        Assert.assertFalse("supported unset when null", notProbed.hasSupported());

        GetChargeCapResponse.Builder probed = GetChargeCapResponse.newBuilder();
        PARSER.merge("{\"success\":true,\"percent\":80,\"enabled\":false,\"supported\":true}", probed);
        Assert.assertTrue(probed.hasPercent());
        Assert.assertEquals(80, probed.getPercent());
        Assert.assertTrue("enabled set even when false", probed.hasEnabled());
        Assert.assertFalse(probed.getEnabled());
    }

    @Test
    public void chargingSchedule_supportedAndReasonPopulate() throws Exception {
        // BladeWatch-ygg0: supported/reason fields added so the client can detect
        // the unsupported state instead of an empty schedule.
        GetChargingScheduleResponse.Builder b = GetChargingScheduleResponse.newBuilder();
        PARSER.merge("{\"success\":true,\"supported\":false,\"reason\":\"cloud_not_configured\"}", b);
        Assert.assertFalse(b.getSupported());
        Assert.assertEquals("cloud_not_configured", b.getReason());
    }

    // ---- BladeWatch-lnx6: parity for the high-reshape RPCs not yet covered ----

    @Test
    public void getStats_nestedStatsPopulates() throws Exception {
        // impl reshapes flat REST {normalCount, sentrySize, ...} into nested stats{}
        // with renamed fields (normalCount->recordingsCount, sentrySize->surveillanceSizeBytes).
        GetStatsResponse.Builder b = GetStatsResponse.newBuilder();
        PARSER.merge("{\"stats\":{\"recordingsCount\":3,\"recordingsSizeBytes\":1000,"
                + "\"surveillanceCount\":2,\"surveillanceSizeBytes\":2000,"
                + "\"proximityCount\":1,\"proximitySizeBytes\":500,"
                + "\"totalCount\":6,\"totalSizeBytes\":3500}}", b);
        Assert.assertEquals(3, b.getStats().getRecordingsCount());
        Assert.assertEquals(1000L, b.getStats().getRecordingsSizeBytes());
        Assert.assertEquals(2, b.getStats().getSurveillanceCount());
        Assert.assertEquals(2000L, b.getStats().getSurveillanceSizeBytes());
        Assert.assertEquals(6, b.getStats().getTotalCount());
        Assert.assertEquals(3500L, b.getStats().getTotalSizeBytes());
    }

    @Test
    public void getStats_rawFlatRestShapeDropsEverything() throws Exception {
        // Pre-fix RAW shape: flat keys with different names, no stats{} nesting -> all default.
        GetStatsResponse.Builder b = GetStatsResponse.newBuilder();
        PARSER.merge("{\"normalCount\":3,\"normalSize\":1000,\"sentryCount\":2,"
                + "\"sentrySize\":2000,\"totalCount\":6,\"totalSize\":3500}", b);
        Assert.assertEquals("recordingsCount drops", 0, b.getStats().getRecordingsCount());
        Assert.assertEquals("totalSizeBytes drops", 0L, b.getStats().getTotalSizeBytes());
        Assert.assertEquals("un-nested totalCount drops", 0, b.getStats().getTotalCount());
    }

    @Test
    public void getSummary_rollupJsonBlobPopulates() throws Exception {
        // impl wraps each REST rollup object into a single rollupJson string blob.
        GetSummaryResponse.Builder b = GetSummaryResponse.newBuilder();
        PARSER.merge("{\"success\":true,\"summary\":[{\"rollupJson\":\"{\\\"tripCount\\\":5}\"}]}", b);
        Assert.assertEquals(1, b.getSummaryCount());
        Assert.assertTrue(b.getSummary(0).getRollupJson().contains("tripCount"));
    }

    @Test
    public void getSummary_rawRollupObjectsDropBlob() throws Exception {
        // Pre-fix RAW shape: bare rollup objects (no rollupJson wrapper) -> blob empty.
        GetSummaryResponse.Builder b = GetSummaryResponse.newBuilder();
        PARSER.merge("{\"success\":true,\"summary\":[{\"tripCount\":5,\"totalDistanceKm\":10.0}]}", b);
        Assert.assertEquals(1, b.getSummaryCount());
        Assert.assertTrue("bare rollup object drops to empty rollupJson",
                b.getSummary(0).getRollupJson().isEmpty());
    }

    @Test
    public void getTelemetry_sampleJsonBlobPopulates() throws Exception {
        // impl wraps each REST telemetry sample object into a single sampleJson blob.
        GetTelemetryResponse.Builder b = GetTelemetryResponse.newBuilder();
        PARSER.merge("{\"success\":true,\"telemetry\":[{\"sampleJson\":\"{\\\"t\\\":1,\\\"s\\\":50}\"}]}", b);
        Assert.assertEquals(1, b.getTelemetryCount());
        Assert.assertTrue(b.getTelemetry(0).getSampleJson().contains("\"s\":50"));
    }

    @Test
    public void getTelemetry_rawSampleObjectsDropBlob() throws Exception {
        // Pre-fix RAW shape: bare sample objects (no sampleJson wrapper) -> blob empty.
        GetTelemetryResponse.Builder b = GetTelemetryResponse.newBuilder();
        PARSER.merge("{\"success\":true,\"telemetry\":[{\"t\":1,\"s\":50}]}", b);
        Assert.assertEquals(1, b.getTelemetryCount());
        Assert.assertTrue(b.getTelemetry(0).getSampleJson().isEmpty());
    }

    @Test
    public void getGpsTrace_latLonObjectsPopulate() throws Exception {
        // impl reshapes positional [[lat,lon],...] into {lat,lon} objects.
        GetGpsTraceResponse.Builder b = GetGpsTraceResponse.newBuilder();
        PARSER.merge("{\"success\":true,\"gps\":[{\"lat\":1.5,\"lon\":2.5}]}", b);
        Assert.assertEquals(1, b.getGpsCount());
        Assert.assertEquals(1.5, b.getGps(0).getLat(), 0.0001);
        Assert.assertEquals(2.5, b.getGps(0).getLon(), 0.0001);
    }

    @Test
    public void getGpsTrace_rawPositionalArraysFailToParse() {
        // Pre-fix RAW shape: positional [lat,lon] arrays cannot parse as GpsPoint messages;
        // this is why the impl must reshape them. A type mismatch throws (not a silent drop).
        GetGpsTraceResponse.Builder b = GetGpsTraceResponse.newBuilder();
        try {
            PARSER.merge("{\"success\":true,\"gps\":[[1.5,2.5]]}", b);
            Assert.fail("positional [lat,lon] arrays must not parse as GpsPoint objects");
        } catch (Exception expected) {
            // expected: InvalidProtocolBufferException (array where message expected)
        }
    }

    // ==================== VEH E1b: optional bool on / optional int32 target_percent ====================

    @Test
    public void setLights_disableDrl_emitsOnFalse() throws Exception {
        // BladeWatch-pcfs: SetLightsRequest.on is optional so on=false must appear on the wire.
        // A plain proto3 bool would be omitted (default-scalar omission), preventing the handler
        // from distinguishing a disable from an absent field.
        String j = json(SetLightsRequest.newBuilder().setAction("dayTimeLight").setOn(false).build());
        assertField(j, "\"on\":false");
        assertHasKey(j, "action");
    }

    @Test
    public void setLights_enableDrl_emitsOnTrue() throws Exception {
        String j = json(SetLightsRequest.newBuilder().setAction("dayTimeLight").setOn(true).build());
        assertField(j, "\"on\":true");
    }

    @Test
    public void setAdas_disableSlw_emitsOnFalse() throws Exception {
        // BladeWatch-pcfs: SetAdasRequest.on is optional so on=false must appear on the wire.
        String j = json(SetAdasRequest.newBuilder().setAction("speedLimitWarning").setOn(false).build());
        assertField(j, "\"on\":false");
        assertHasKey(j, "action");
    }

    @Test
    public void setAdas_enableSlw_emitsOnTrue() throws Exception {
        String j = json(SetAdasRequest.newBuilder().setAction("speedLimitWarning").setOn(true).build());
        assertField(j, "\"on\":true");
    }

    @Test
    public void moveWindow_zeroTargetPercent_emitsTargetPercent() throws Exception {
        // BladeWatch-pcfs: MoveWindowRequest.target_percent is optional so 0% (fully closed
        // preset) must appear on the wire. Without optional, target_percent=0 would be omitted
        // and the handler would fall through to the direction code path instead of the
        // closed-loop positioning path.
        String j = json(MoveWindowRequest.newBuilder().setWindowIndex(1).setTargetPercent(0).build());
        assertField(j, "\"targetPercent\":0");
    }
}
