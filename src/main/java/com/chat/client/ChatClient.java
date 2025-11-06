package com.chat.client;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cliente de consola Java para el MVP.
 * - Hilo principal: lee comandos del usuario (/say, /upload, /pdf, /quit)
 * - Listener: MessageHandler que imprime mensajes recibidos
 */
public class ChatClient {

    public static void main(String[] args) throws Exception {
        String uri = "ws://localhost:8080/ws/chat";
        AtomicReference<Session> sessionRef = new AtomicReference<>();

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();

        Endpoint clientEndpoint = new Endpoint() {
            @Override
            public void onOpen(Session session, EndpointConfig config) {
                System.out.println("Conectado al servidor");
                session.addMessageHandler(new MessageHandler.Whole<String>() {
                    @Override
                    public void onMessage(String message) {
                        try {
                            JSONObject data = new JSONObject(message);
                            String type = data.optString("type", "");
                            if ("chat".equals(type)) {
                                System.out.printf("[%s] %s\n", data.optString("user", "Anon"), data.optString("text", ""));
                            } else if ("system".equals(type)) {
                                String ev = data.optString("event", "");
                                switch (ev) {
                                    case "upload_receiving":
                                        System.out.printf("[SYSTEM] Recibiendo parte %d de %s\n", data.optInt("part", -1), data.optString("file", ""));
                                        break;
                                    case "upload_done":
                                        System.out.printf("[SYSTEM] Subida terminada: %s\n", data.optString("file", ""));
                                        break;
                                    case "pdf_creating":
                                        System.out.printf("[SYSTEM] Creando PDF: %s\n", data.optString("title", ""));
                                        break;
                                    case "pdf_ready":
                                        System.out.printf("[SYSTEM] PDF listo: %s\n", data.optString("title", ""));
                                        break;
                                    default:
                                        System.out.println("[SYSTEM] " + data.toString());
                                }
                            } else {
                                System.out.println("RECV: " + data.toString());
                            }
                        } catch (Exception e) {
                            System.out.println("RECV (no-json): " + message);
                        }
                    }
                });
                sessionRef.set(session);
            }

            @Override
            public void onClose(Session session, javax.websocket.CloseReason closeReason) {
                System.out.println("Conexión cerrada: " + closeReason);
                sessionRef.set(null);
            }

            @Override
            public void onError(Session session, Throwable thr) {
                System.err.println("Error en websocket: " + thr.getMessage());
            }
        };

        container.connectToServer(clientEndpoint, ClientEndpointConfig.Builder.create().build(), URI.create(uri));

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.print("Nombre de usuario: ");
        String user = reader.readLine();
        if (user == null || user.isBlank()) user = "Anon";

        // enviar un join opcional
        Session sess = sessionRef.get();
        if (sess != null && sess.isOpen()) {
            JSONObject join = new JSONObject();
            join.put("type", "system");
            join.put("event", "join");
            join.put("user", user);
            sess.getAsyncRemote().sendText(join.toString());
        }

    System.out.println("Comandos: /say texto | /upload nombre.ext (simulado) | /uploadfile ruta/a/archivo | /pdf Titulo | /quit");
        while (true) {
            String line = reader.readLine();
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            Session s = sessionRef.get();
            if (line.startsWith("/say ")) {
                String text = line.substring(5);
                JSONObject chat = new JSONObject();
                chat.put("type", "chat");
                chat.put("user", user);
                chat.put("text", text);
                if (s != null && s.isOpen()) s.getAsyncRemote().sendText(chat.toString());
            } else if (line.startsWith("/upload ")) {
                String name = line.substring(8).trim();
                if (name.isEmpty()) {
                    System.out.println("Usa: /upload nombre.ext");
                    continue;
                }
                // worker thread
                new Thread(() -> {
                    try {
                        for (int i = 1; i <= 3; i++) {
                            JSONObject chunk = new JSONObject();
                            chunk.put("type", "upload_chunk");
                            chunk.put("name", name);
                            chunk.put("part", i);
                            Session ss = sessionRef.get();
                            if (ss != null && ss.isOpen()) ss.getAsyncRemote().sendText(chunk.toString());
                            Thread.sleep(1000);
                        }
                        JSONObject end = new JSONObject();
                        end.put("type", "upload_end");
                        end.put("name", name);
                        Session ss = sessionRef.get();
                        if (ss != null && ss.isOpen()) ss.getAsyncRemote().sendText(end.toString());
                        System.out.println("Subida simulada terminada para: " + name);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "upload-worker").start();
                System.out.println("Subida simulada iniciada para: " + name);
            } else if (line.startsWith("/uploadfile ")) {
                String ruta = line.substring(12).trim();
                File f = new File(ruta);
                if (!f.exists() || !f.isFile()) {
                    System.out.println("Archivo no encontrado: " + ruta);
                    continue;
                }
                String fileId = UUID.randomUUID().toString();
                String filename = f.getName();
                long size = f.length();
                // enviar upload_start
                JSONObject start = new JSONObject();
                start.put("type", "upload_start");
                start.put("fileId", fileId);
                start.put("name", filename);
                start.put("size", size);
                Session ss0 = sessionRef.get();
                if (ss0 != null && ss0.isOpen()) ss0.getAsyncRemote().sendText(start.toString());

                // enviar en hilo los chunks binarios
                new Thread(() -> {
                    try (FileInputStream fis = new FileInputStream(f)) {
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while ((r = fis.read(buf)) > 0) {
                            JSONObject meta = new JSONObject();
                            meta.put("type", "upload_chunk_meta");
                            meta.put("fileId", fileId);
                            meta.put("len", r);
                            Session ss = sessionRef.get();
                            if (ss == null || !ss.isOpen()) break;
                            ss.getAsyncRemote().sendText(meta.toString());
                            ss.getAsyncRemote().sendBinary(ByteBuffer.wrap(buf, 0, r));
                            Thread.sleep(50);
                        }
                        JSONObject end = new JSONObject();
                        end.put("type", "upload_end");
                        end.put("fileId", fileId);
                        Session ss = sessionRef.get();
                        if (ss != null && ss.isOpen()) ss.getAsyncRemote().sendText(end.toString());
                        System.out.println("Subida de archivo terminada: " + ruta);
                    } catch (Exception e) {
                        System.err.println("Error subiendo archivo: " + e.getMessage());
                    }
                }, "uploadfile-worker").start();
                System.out.println("Subida de archivo iniciada (binario) para: " + ruta);
            } else if (line.startsWith("/pdf ")) {
                String title = line.substring(5).trim();
                if (title.isEmpty()) {
                    System.out.println("Usa: /pdf Titulo del PDF");
                    continue;
                }
                JSONObject pdf = new JSONObject();
                pdf.put("type", "create_pdf");
                pdf.put("title", title);
                if (s != null && s.isOpen()) s.getAsyncRemote().sendText(pdf.toString());
            } else if (line.equals("/quit")) {
                Session ss = sessionRef.get();
                if (ss != null && ss.isOpen()) {
                    try {
                        ss.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
                break;
            } else {
                System.out.println("Comando desconocido. Usa /say, /upload, /pdf o /quit");
            }
        }


        System.out.println("Cliente terminando");
        System.exit(0);
    }
}
