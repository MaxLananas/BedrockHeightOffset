package io.github.maxlananas.hauteur.pipeline;

import org.geysermc.geyser.GeyserLogger;

/** {@link Diagnostics} backed by Geyser's logger. */
public final class GeyserDiagnostics implements Diagnostics {
    private final GeyserLogger logger;

    public GeyserDiagnostics(GeyserLogger logger) {
        this.logger = java.util.Objects.requireNonNull(logger);
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void error(String message, Throwable cause) {
        logger.error(message, cause);
    }
}
