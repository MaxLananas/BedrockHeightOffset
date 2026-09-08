package fr.buildtheearth.skywindow;

import fr.buildtheearth.skywindow.command.SkyWindowCommand;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionInitializeEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCommandsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostReloadEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreReloadEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;

/**
 * SkyWindow: a Geyser extension that lets Bedrock clients build, move and interact at any
 * height of a Java world far beyond the client's native range, without rubber-banding.
 *
 * <p>The old implementation patched Bedrock packets on the wire to fake coordinates; that approach is
 * abandoned entirely (see README "Why the old design was removed"). This rewrite translates absolute Y
 * exactly once, on Geyser's Java-side protocol pipeline, per player, within the client's own
 * negotiated dimension range.</p>
 */
public final class SkyWindowExtension implements Extension {
    private final SkyWindowCore core = new SkyWindowCore(this);

    // Geyser 2.x drives extension lifecycle through events only (no onEnable/onDisable hooks):
    // start on post-init so the platform pieces exist, re-apply on reload, stop on shutdown, and
    // override disable() for the /geyser extensions disable path.
    @Override
    public void disable() {
        core.stop();
        Extension.super.disable();
    }

    public SkyWindowCore core() {
        return core;
    }

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        core.start();
    }

    @Subscribe
    public void onPostReload(GeyserPostReloadEvent event) {
        // Covers "enabled mid-session via /geyser extensions" and re-enable after a reload.
        core.reload();
    }

    @Subscribe
    public void onPreReload(GeyserPreReloadEvent event) {
        // Re-read config and apply live: handlers consult this core per packet, so no reconnect is
        // needed to pick up new margins or timeouts. Disabling detaches everything cleanly.
        core.reload();
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        core.stop();
    }

    @Subscribe
    public void onSessionInitialize(SessionInitializeEvent event) {
        core.onSessionInitialized(event.connection());
    }

    @Subscribe
    public void onSessionJoin(SessionJoinEvent event) {
        core.onSessionJoined(event.connection());
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        core.onSessionDisconnected(event.connection());
    }

    @Subscribe
    public void onDefineCommands(GeyserDefineCommandsEvent event) {
        event.register(SkyWindowCommand.build(this));
    }
}
