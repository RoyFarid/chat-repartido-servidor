package com.chat.server;

import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import org.json.JSONObject;

import java.io.IOException;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@ServerEndpoint("/chat")
public class ChatEndpoint {

    private static final Set<Session> sesiones =
            Collections.synchronizedSet(new HashSet<>());

    // Executor for background tasks (pdf worker, etc.). Size configurable with -Dchat.worker.pool.size=N
    private static final int WORKER_POOL_SIZE = Integer.getInteger("chat.worker.pool.size", 4);
    private static final ExecutorService WORKER_POOL = Executors.newFixedThreadPool(WORKER_POOL_SIZE);

    // Uploads in progress: fileId -> state
    private static final Map<String, UploadState> uploads = new ConcurrentHashMap<>();
    // For each session, which fileId we expect next as a binary chunk (set by upload_chunk_meta)
    private static final Map<Session, AtomicReference<String>> pendingBinary = new ConcurrentHashMap<>();

    private static class UploadState {
        final Path tmpPath;
        final FileOutputStream os;
        final long expectedSize;
        long received = 0;
        final String name;

        UploadState(Path tmpPath, FileOutputStream os, long expectedSize, String name) {
            this.tmpPath = tmpPath;
            this.os = os;
            this.expectedSize = expectedSize;
            this.name = name;
        }

        synchronized void write(ByteBuffer buf) throws IOException {
            int len = buf.remaining();
            byte[] b = new byte[len];
            buf.get(b);
            os.write(b);
            received += len;
        }

        synchronized void close() throws IOException {
            os.flush();
            os.close();
        }
    }

    static {
        // Shutdown executor gracefully on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            WORKER_POOL.shutdown();
            try {
                if (!WORKER_POOL.awaitTermination(3, TimeUnit.SECONDS)) {
                    WORKER_POOL.shutdownNow();
                }
            } catch (InterruptedException e) {
                WORKER_POOL.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, "chat-endpoint-shutdown"));
    }

    @OnOpen
    public void onOpen(Session session) {
        sesiones.add(session);
        // preparar estructura para recibir binarios (metadata + binary alternado)
        pendingBinary.put(session, new AtomicReference<>(null));
        System.out.println("Cliente conectado. Total: " + sesiones.size());
    }

    @OnMessage
    public void onMessage(String mensaje, Session session) {
        try {
            JSONObject data = new JSONObject(mensaje);
            String tipo = data.optString("type", "");
            if ("upload_start".equals(tipo)) {
                String fileId = data.optString("fileId", "");
                String name = data.optString("name", "uploaded.bin");
                long size = data.optLong("size", -1);
                try {
                    Path tmp = Files.createTempFile("upload-", "-" + name);
                    FileOutputStream os = new FileOutputStream(tmp.toFile());
                    UploadState st = new UploadState(tmp, os, size, name);
                    uploads.put(fileId, st);
                    System.out.printf("Iniciada subida: %s (fileId=%s, expected=%d) -> %s%n", name, fileId, size, tmp.toString());
                    JSONObject started = new JSONObject();
                    started.put("type", "system");
                    started.put("event", "upload_started");
                    started.put("fileId", fileId);
                    started.put("file", name);
                    broadcast(started.toString());
                } catch (IOException e) {
                    System.err.println("No se pudo iniciar upload: " + e.getMessage());
                }
                return;
            } else if ("upload_chunk_meta".equals(tipo)) {
                String fileId = data.optString("fileId", "");
                AtomicReference<String> ref = pendingBinary.get(session);
                if (ref != null) {
                    ref.set(fileId);
                }
                return;
            }
            if ("chat".equals(tipo)) {
                String user = data.optString("user", "Anon");
                String text = data.optString("text", "");
                System.out.printf("[%s] %s%n", user, text);
                JSONObject respuesta = new JSONObject();
                respuesta.put("type", "chat");
                respuesta.put("user", user);
                respuesta.put("text", text);
                broadcast(respuesta.toString());
            } else if ("upload_chunk".equals(tipo)) {
                String name = data.optString("name", "unknown");
                int part = data.optInt("part", -1);
                System.out.printf("Recibiendo parte %d de archivo %s (simulado)%n", part, name);
                // informar que se está recibiendo (no se guarda nada)
                JSONObject sys = new JSONObject();
                sys.put("type", "system");
                sys.put("event", "upload_receiving");
                sys.put("file", name);
                sys.put("part", part);
                broadcast(sys.toString());
            } else if ("upload_end".equals(tipo)) {
                String fileId = data.optString("fileId", "");
                if (!fileId.isEmpty()) {
                    UploadState st = uploads.remove(fileId);
                    if (st != null) {
                        try {
                            st.close();
                            System.out.printf("Subida terminada (real) del archivo %s -> %s (recibidos=%d)%n", st.name, st.tmpPath.toString(), st.received);
                            JSONObject done = new JSONObject();
                            done.put("type", "system");
                            done.put("event", "upload_done");
                            done.put("file", st.name);
                            done.put("path", st.tmpPath.toString());
                            broadcast(done.toString());
                        } catch (IOException e) {
                            System.err.println("Error cerrando upload: " + e.getMessage());
                        }
                    }
                } else {
                    String name = data.optString("name", "unknown");
                    System.out.printf("Subida terminada (simulado) del archivo %s%n", name);
                    JSONObject done = new JSONObject();
                    done.put("type", "system");
                    done.put("event", "upload_done");
                    done.put("file", name);
                    broadcast(done.toString());
                }
            } else if ("create_pdf".equals(tipo)) {
                String title = data.optString("title", "Sin título");
                System.out.printf("Solicitud de creación de PDF: %s (simulado)%n", title);
                // avisar inmediatamente
                JSONObject notify = new JSONObject();
                notify.put("type", "system");
                notify.put("event", "pdf_creating");
                notify.put("title", title);
                broadcast(notify.toString());
                // lanzar trabajo simulado en el pool para no bloquear
                WORKER_POOL.submit(() -> {
                    try {
                        Thread.sleep(3000);
                        System.out.printf("PDF listo (simulado): %s%n", title);
                        JSONObject ready = new JSONObject();
                        ready.put("type", "system");
                        ready.put("event", "pdf_ready");
                        ready.put("title", title);
                        broadcast(ready.toString());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        } catch (Exception e) {
            System.out.println("Error procesando mensaje: " + e.getMessage());
        }
    }

    @OnMessage
    public void onBinaryMessage(ByteBuffer data, Session session) {
        AtomicReference<String> ref = pendingBinary.get(session);
        String fileId = null;
        if (ref != null) fileId = ref.getAndSet(null);
        if (fileId == null) {
            System.out.println("Binary message received but no pending fileId for session");
            return;
        }
        UploadState st = uploads.get(fileId);
        if (st == null) {
            System.out.println("No upload state for fileId=" + fileId);
            return;
        }
        try {
            st.write(data);
            // optionally inform progress
            JSONObject prog = new JSONObject();
            prog.put("type", "system");
            prog.put("event", "upload_receiving");
            prog.put("file", st.name);
            prog.put("received", st.received);
            broadcast(prog.toString());
        } catch (IOException e) {
            System.err.println("Error escribiendo chunk para fileId=" + fileId + ": " + e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session) {
        sesiones.remove(session);
        System.out.println("Cliente desconectado. Total: " + sesiones.size());
    }

    @OnError
    public void onError(Session session, Throwable t) {
        System.err.println("Error: " + t.getMessage());
    }

    private void broadcast(String mensaje) {
        synchronized (sesiones) {
            List<Session> toRemove = new ArrayList<>();
            for (Session s : sesiones) {
                if (s.isOpen()) {
                    try {
                        // enviar de forma asíncrona para evitar bloqueos del servidor
                        // añadimos un SendHandler para detectar fallos y limpiar la sesión si es necesario
                        s.getAsyncRemote().sendText(mensaje, new SendHandler() {
                            @Override
                            public void onResult(SendResult result) {
                                if (result.getException() != null) {
                                    System.err.println("Error enviando mensaje a cliente (async): " + result.getException().getMessage());
                                    try {
                                        sesiones.remove(s);
                                        s.close();
                                    } catch (Exception ex) {
                                        // ignore
                                    }
                                }
                            }
                        });
                    } catch (Exception e) {
                        System.err.println("Error iniciando envío async a cliente: " + e.getMessage());
                        toRemove.add(s);
                    }
                } else {
                    toRemove.add(s);
                }
            }
            if (!toRemove.isEmpty()) {
                sesiones.removeAll(toRemove);
            }
        }
    }
}
