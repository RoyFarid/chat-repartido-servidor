package com.chat.server;

import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

@ServerEndpoint("/chat")
public class ChatEndpoint {

    private static final Set<Session> sesiones =
            Collections.synchronizedSet(new HashSet<>());

    @OnOpen
    public void onOpen(Session session) {
        sesiones.add(session);
        System.out.println("Cliente conectado. Total: " + sesiones.size());
    }

    @OnMessage
    public void onMessage(String mensaje, Session session) {
        try {
            JSONObject data = new JSONObject(mensaje);
            String tipo = data.optString("type", "");
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
                String name = data.optString("name", "unknown");
                System.out.printf("Subida terminada (simulada) del archivo %s%n", name);
                JSONObject done = new JSONObject();
                done.put("type", "system");
                done.put("event", "upload_done");
                done.put("file", name);
                broadcast(done.toString());
            } else if ("create_pdf".equals(tipo)) {
                String title = data.optString("title", "Sin título");
                System.out.printf("Solicitud de creación de PDF: %s (simulado)%n", title);
                // avisar inmediatamente
                JSONObject notify = new JSONObject();
                notify.put("type", "system");
                notify.put("event", "pdf_creating");
                notify.put("title", title);
                broadcast(notify.toString());
                // lanzar trabajo simulado en otro hilo
                new Thread(() -> {
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
                }, "pdf-worker").start();
            }
        } catch (Exception e) {
            System.out.println("Error procesando mensaje: " + e.getMessage());
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
                        s.getBasicRemote().sendText(mensaje);
                    } catch (IOException e) {
                        System.err.println("Error enviando mensaje a cliente: " + e.getMessage());
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
