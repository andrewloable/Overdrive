package com.overdrive.app.server;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.speech.tts.TextToSpeech;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hacky test endpoint for AVAS speaker audio playback.
 * 
 * Tests whether the AVAS (exterior) speaker can play audio when the vehicle is off.
 * Uses BydDataCollector's multimedia device directly (same pattern as all other BYD HAL access).
 * 
 * Endpoints:
 *   POST /api/audio/test-avas  — force-enable AVAS speaker and play a test tone/TTS
 *   GET  /api/audio/avas-state — read current AVAS speaker state without changing anything
 */
public class AudioTestApiHandler {

    private static final String TAG = "AudioTestApi";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if (cleanPath.equals("/api/audio/avas-state") && method.equals("GET")) {
            handleGetState(out);
            return true;
        }

        if (cleanPath.equals("/api/audio/test-avas") && method.equals("POST")) {
            handleTestAvas(out, body);
            return true;
        }

        return false;
    }

    /**
     * GET /api/audio/avas-state — read-only state query.
     */
    private static void handleGetState(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            BydDataCollector collector = BydDataCollector.getInstance();

            response.put("multimediaAvailable", collector.isMultimediaAvailable());
            response.put("collectorInitialized", collector.isInitialized());

            if (!collector.isMultimediaAvailable()) {
                // Multimedia device returned null from getInstance() — 
                // this means the IVI audio service isn't running (car off / multimedia subsystem powered down).
                // We can still try playing audio through Android's standard audio path.
                response.put("success", true);
                response.put("warning", "Multimedia device unavailable (getInstance returned null). AVAS routing not possible, but standard audio output may still work.");
                response.put("canPlayStandardAudio", true);
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            Integer speakerState = collector.getExteriorSpeakerState();
            Integer avasSource = collector.getAVASSoundSource();

            response.put("success", true);
            response.put("exteriorSpeakerState", speakerState != null ? speakerState : JSONObject.NULL);
            response.put("exteriorSpeakerEnabled", speakerState != null ? speakerState == 1 : JSONObject.NULL);
            response.put("avasSource", avasSource != null ? avasSource : JSONObject.NULL);

        } catch (Exception e) {
            logger.warn("avas-state failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/audio/test-avas — force-enable AVAS and play audio.
     * 
     * Body (all optional):
     * {
     *   "mode": "tone" | "tts" | "file",   // default: "tone"
     *   "sourceType": 3,                     // AVAS source type to try (default: 3)
     *   "volume": 20,                        // media volume 0-39 (default: 20)
     *   "duration": 3000,                    // tone duration ms (default: 3000)
     *   "text": "Hello from AVAS",           // TTS text (default: "AVAS speaker test")
     *   "restore": true                      // restore original state after (default: true)
     * }
     */
    private static void handleTestAvas(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        JSONObject log = new JSONObject();

        try {
            // Parse request
            String mode = "tone";
            int sourceType = 3; // media
            int volume = 20;
            int duration = 3000;
            String ttsText = "AVAS speaker test";
            boolean restore = true;

            if (body != null && !body.isEmpty()) {
                JSONObject req = new JSONObject(body);
                mode = req.optString("mode", "tone");
                sourceType = req.optInt("sourceType", 3);
                volume = Math.min(req.optInt("volume", 20), 30); // safety cap
                duration = Math.min(req.optInt("duration", 3000), 10000); // max 10s
                ttsText = req.optString("text", "AVAS speaker test");
                restore = req.optBoolean("restore", true);
            }

            logger.info("test-avas: mode=" + mode + " source=" + sourceType +
                       " vol=" + volume + " dur=" + duration);

            BydDataCollector collector = BydDataCollector.getInstance();
            boolean multimediaAvailable = collector.isMultimediaAvailable();
            log.put("multimediaAvailable", multimediaAvailable);

            // Step 1: Save original state (only if multimedia device is available)
            Integer origSpeaker = null;
            Integer origSource = null;

            if (multimediaAvailable) {
                origSpeaker = collector.getExteriorSpeakerState();
                origSource = collector.getAVASSoundSource();

                log.put("originalState", new JSONObject()
                    .put("speakerState", origSpeaker != null ? origSpeaker : JSONObject.NULL)
                    .put("avasSource", origSource != null ? origSource : JSONObject.NULL));

                logger.info("test-avas: original: speaker=" + origSpeaker + " source=" + origSource);

                // Step 2: Force-enable AVAS speaker
                boolean speakerOk = collector.setExteriorSpeakerState(1);
                boolean sourceOk = collector.setAVASSoundSource(sourceType);

                log.put("setup", new JSONObject()
                    .put("speakerEnabled", speakerOk)
                    .put("avasSourceSet", sourceOk)
                    .put("sourceType", sourceType));

                logger.info("test-avas: setup: speaker=" + speakerOk + " source=" + sourceOk);
            } else {
                log.put("originalState", "skipped (multimedia device unavailable)");
                log.put("setup", "skipped (playing through standard Android audio output)");
                logger.info("test-avas: multimedia unavailable, playing through standard audio");
            }

            // Step 3: Play audio
            boolean playOk = false;

            switch (mode) {
                case "tts":
                    playOk = playTts(ttsText, duration + 2000);
                    break;
                case "file":
                    playOk = playFile(duration);
                    break;
                case "tone":
                default:
                    playOk = playTone(duration);
                    break;
            }

            log.put("playback", new JSONObject()
                .put("mode", mode)
                .put("success", playOk));

            logger.info("test-avas: playback " + mode + " = " + playOk);

            // Step 4: Restore original state
            if (restore && multimediaAvailable) {
                boolean restoreSpeaker = true;
                boolean restoreSource = true;

                if (origSpeaker != null) {
                    restoreSpeaker = collector.setExteriorSpeakerState(origSpeaker);
                }
                if (origSource != null) {
                    restoreSource = collector.setAVASSoundSource(origSource);
                }

                log.put("restore", new JSONObject()
                    .put("speaker", restoreSpeaker)
                    .put("source", restoreSource));
            }

            // Step 5: Verify final state
            if (multimediaAvailable) {
                Integer finalSpeaker = collector.getExteriorSpeakerState();
                Integer finalSource = collector.getAVASSoundSource();

                log.put("finalState", new JSONObject()
                    .put("speakerState", finalSpeaker != null ? finalSpeaker : JSONObject.NULL)
                    .put("avasSource", finalSource != null ? finalSource : JSONObject.NULL));
            }

            response.put("success", true);
            response.put("log", log);

        } catch (Exception e) {
            logger.warn("test-avas failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            response.put("log", log);
        }
        HttpResponse.sendJson(out, response.toString());
    }

    // ==================== AUDIO PLAYBACK ====================

    /**
     * Play a tone via ToneGenerator on STREAM_MUSIC.
     */
    private static boolean playTone(int durationMs) {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, durationMs);
            Thread.sleep(durationMs + 500);
            tg.release();
            logger.info("playTone: completed (" + durationMs + "ms)");
            return true;
        } catch (Exception e) {
            logger.warn("playTone failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Play TTS via Android TextToSpeech on STREAM_MUSIC.
     */
    private static boolean playTts(String text, int timeoutMs) {
        android.content.Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) {
            logger.warn("playTts: no context available");
            return false;
        }

        final CountDownLatch initLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicBoolean initOk = new AtomicBoolean(false);

        try {
            TextToSpeech tts = new TextToSpeech(ctx, status -> {
                initOk.set(status == TextToSpeech.SUCCESS);
                initLatch.countDown();
            });

            if (!initLatch.await(5, TimeUnit.SECONDS)) {
                logger.warn("playTts: TTS init timeout");
                tts.shutdown();
                return false;
            }

            if (!initOk.get()) {
                logger.warn("playTts: TTS init failed");
                tts.shutdown();
                return false;
            }

            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) { doneLatch.countDown(); }
                @Override public void onError(String utteranceId) { doneLatch.countDown(); }
            });

            android.os.Bundle params = new android.os.Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "avas_test");

            boolean completed = doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            tts.shutdown();

            logger.info("playTts: " + (completed ? "completed" : "timed out"));
            return completed;

        } catch (Exception e) {
            logger.warn("playTts failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Play a beep pattern using ToneGenerator (distinguishable from single tone).
     */
    private static boolean playFile(int durationMs) {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            for (int i = 0; i < 3; i++) {
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 500);
                Thread.sleep(700);
            }
            tg.release();
            logger.info("playFile (beep pattern): completed");
            return true;
        } catch (Exception e) {
            logger.warn("playFile failed: " + e.getMessage());
            return false;
        }
    }
}
