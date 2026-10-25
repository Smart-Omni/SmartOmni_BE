package com.smartomni.integration.service.tiktok;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Logs a WARN and writes the "email" to a LOCAL sink (a file plus a bounded in-memory list). No mail is sent:
 * resolving the Tenant Admin's address needs the Auth domain and an SMTP relay, which are not part of this
 * phase. Messages hold identifiers and a reason code only, never a token or secret.
 */
@Slf4j
@Component
public class LocalSinkReconnectNotifier implements ReconnectNotifier {

    private static final int MAX_IN_MEMORY = 100;

    private final Path sinkFile;
    private final List<String> recent = new ArrayList<>();

    public LocalSinkReconnectNotifier(
            @Value("${smartomni.notifications.local-email-sink-file:${java.io.tmpdir}/smartomni-local-email-sink/connection-alerts.log}")
            String sinkFile) {
        this.sinkFile = Path.of(sinkFile);
    }

    @Override
    public void reconnectRequired(Long tenantId, Long connectionId, String reasonCode) {
        log.warn("TikTok connection needs reconnect: tenant={} connection={} reason={}", tenantId, connectionId, reasonCode);
        String message = Instant.now() + " to=tenant-admin(tenant " + tenantId + ") subject=\"Reconnect your TikTok Shop\""
                + " connection=" + connectionId + " reason=" + reasonCode;
        synchronized (recent) {
            if (recent.size() >= MAX_IN_MEMORY) {
                recent.remove(0);
            }
            recent.add(message);
        }
        try {
            Files.createDirectories(sinkFile.getParent());
            Files.writeString(sinkFile, message + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("Local email sink not writable ({})", e.getClass().getSimpleName());
        }
    }

    public List<String> recentMessages() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }
}
