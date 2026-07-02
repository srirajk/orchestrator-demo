package ai.conduit.chat.chat;

import java.io.IOException;
import java.io.InputStream;

/**
 * An open streaming response from the gateway: the HTTP status plus the raw body
 * {@link InputStream}. The caller inspects {@link #successful()} before consuming
 * the stream, and must {@link #close()} it.
 */
public final class GatewayStream implements AutoCloseable {

    private final int status;
    private final InputStream body;

    public GatewayStream(int status, InputStream body) {
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public boolean successful() {
        return status / 100 == 2;
    }

    public InputStream body() {
        return body;
    }

    @Override
    public void close() {
        try {
            if (body != null) {
                body.close();
            }
        } catch (IOException ignored) {
            // best-effort close
        }
    }
}
