package com.overdrive.app.daemon.telegram;

import org.json.JSONObject;

/**
 * Handles /update — checks GitHub releases, shows current vs remote version,
 * and (on confirmation) kicks off the same install pipeline used by the webapp.
 *
 * Flow:
 *   /update                → IPC CHECK_UPDATE → reply with status + Install button
 *   button "🔄 Install Now" → callback "up:install" → IPC INSTALL_UPDATE
 *
 * The install runs in CameraDaemon's process (via SurveillanceIpcServer).
 * That process dies mid-install, which is the signal to the webapp/Telegram
 * that progress has moved past the point of no return. The new process boots
 * with the post-update hint planted, so the next "Tunnel URL" message reads
 * "🔄 Overdrive updated to X" instead of generic "URL changed".
 */
public class UpdateCommandHandler implements TelegramCommandHandler {

    private static final int CAMERA_IPC_PORT = 19877;
    // Server-side handlers do a sync GitHub call (~12s for check, up to 20s
    // before kicking off the install). Default 5s socket timeout in the
    // Telegram daemon's IPC client would always trip before the response
    // arrives, so we ask for a longer read timeout.
    private static final int CHECK_TIMEOUT_MS = 18_000;
    private static final int INSTALL_TIMEOUT_MS = 25_000;

    @Override
    public boolean canHandle(String command) {
        return "/update".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        // Sub-command: /update install — usually arrives via the inline button
        // (callback "cmd:/update install"), but we also accept the typed form
        // so power-users can skip the confirmation step.
        if (args.length > 1 && "install".equalsIgnoreCase(args[1])) {
            handleInstall(chatId, ctx);
            return;
        }
        handleCheck(chatId, ctx);
    }

    private void handleCheck(long chatId, CommandContext ctx) {
        ctx.sendMessage(chatId, "🔍 Checking for updates…");

        JSONObject req = new JSONObject();
        try { req.put("command", "CHECK_UPDATE"); } catch (Exception ignored) {}

        JSONObject resp = ctx.sendIpcCommand(CAMERA_IPC_PORT, req, CHECK_TIMEOUT_MS);
        if (resp == null) {
            ctx.sendMessage(chatId, "⚠️ Could not reach update service.\n\n" +
                    "The camera daemon may not be running. Try `/daemon camera start`.");
            return;
        }
        if (!resp.optBoolean("success", false)) {
            ctx.sendMessage(chatId, "⚠️ Update check failed: " +
                    resp.optString("error", "unknown"));
            return;
        }

        boolean available = resp.optBoolean("available", false);
        String currentVersion = resp.optString("currentVersion", "?");
        String remoteVersion = resp.optString("remoteVersion", "?");

        if (resp.has("error")) {
            ctx.sendMessage(chatId, "⚠️ Update check error: " + resp.optString("error"));
            return;
        }

        if (!available) {
            ctx.sendMessage(chatId,
                    "✅ *Up to date*\n_OverDrive " + currentVersion + "_");
            return;
        }

        String releaseNotes = resp.optString("releaseNotes", "").trim();
        StringBuilder sb = new StringBuilder();
        sb.append("⬆️ *Update available*\n\n")
          .append("*Current:* ").append(currentVersion).append("\n")
          .append("*Latest:* ").append(remoteVersion).append("\n");
        if (!releaseNotes.isEmpty()) {
            // Telegram caption/text limit is 4096; trim release notes
            // aggressively because they're often a markdown changelog.
            String trimmed = releaseNotes.length() > 600
                    ? releaseNotes.substring(0, 600) + "…"
                    : releaseNotes;
            // GitHub release bodies are user-authored Markdown (often with
            // **bold**, ```fenced code```, mid-token underscores, [links]())
            // which Telegram's legacy Markdown parser rejects with
            // "can't parse entities" — the whole sendMessage 400s and the
            // user is left staring at "🔍 Checking for updates…" forever.
            // Strip the control chars so the body lands as plain text inside
            // the Telegram-Markdown envelope.
            sb.append("\n*Release notes:*\n").append(stripMarkdown(trimmed)).append("\n");
        }
        sb.append("\n_Install takes ~2 minutes. The bot will restart and post a new tunnel URL when done._");

        String[][][] buttons = {
                {{"🔄 Install Now", "cmd:/update install"}},
                {{"❌ Cancel", "cmd:/help"}}
        };
        ctx.sendMessageWithButtons(chatId, sb.toString(), buttons);
    }

    // Local one-shot guard: if the user spam-taps "Install Now" before the
    // daemons start dying (~2-3s window), block the second tap from sending a
    // duplicate IPC. Reset to false on failure paths so a bad first attempt
    // doesn't lock the user out forever.
    private static final java.util.concurrent.atomic.AtomicBoolean installRequested =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void handleInstall(long chatId, CommandContext ctx) {
        if (!installRequested.compareAndSet(false, true)) {
            ctx.sendMessage(chatId,
                    "⏳ Install already in progress — wait for the post-update tunnel URL message.");
            return;
        }

        ctx.sendMessage(chatId,
                "⏳ *Installing update…*\n\n" +
                "Daemons will stop, the APK will install, and the device will\n" +
                "relaunch. This bot will go silent for ~2 minutes; you'll get a\n" +
                "fresh tunnel URL message once it's back.");

        JSONObject req = new JSONObject();
        try { req.put("command", "INSTALL_UPDATE"); } catch (Exception ignored) {}

        JSONObject resp = ctx.sendIpcCommand(CAMERA_IPC_PORT, req, INSTALL_TIMEOUT_MS);
        if (resp == null) {
            installRequested.set(false);
            ctx.sendMessage(chatId,
                    "⚠️ Could not start install — camera daemon unreachable.");
            return;
        }
        if (!resp.optBoolean("success", false)) {
            installRequested.set(false);
            ctx.sendMessage(chatId,
                    "⚠️ Install rejected: " + resp.optString("error", "unknown"));
            return;
        }
        // Success path: daemons are now dying. No further messages from this
        // bot until the new process boots and sends the post-update tunnel URL.
        // We intentionally leave installRequested=true; the process will be
        // killed and reborn, so the new instance starts with a fresh AtomicBoolean.
        ctx.log("Update install scheduled via Telegram (remote=" +
                resp.optString("remoteVersion", "?") + ")");
    }

    /**
     * Neutralize Markdown control characters in user-supplied content
     * (GitHub release notes) so the Telegram legacy-Markdown parser
     * doesn't reject the whole message. We strip rather than escape
     * because backslash-escaping is itself spotty in legacy Markdown,
     * and the user just wants to read the notes — formatting fidelity
     * isn't worth the parse failures.
     */
    private static String stripMarkdown(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Control chars Telegram legacy treats as entity boundaries.
            if (c == '*' || c == '_' || c == '`' || c == '[' || c == ']') continue;
            out.append(c);
        }
        return out.toString();
    }
}
