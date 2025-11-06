package com.chat.server;

import org.glassfish.tyrus.server.Server;

import java.util.Scanner;

public class ChatServer {

    public static void main(String[] args) { 
        Server server = new Server("localhost", 8080, "/ws", ChatEndpoint.class);
        try {
            server.start();
            System.out.println("Servidor WebSocket activo en ws://localhost:8080/ws/chat");
            System.out.println("Presiona ENTER para detener...");
            new Scanner(System.in).nextLine(); // esperar input
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            server.stop();
            System.out.println("Servidor detenido.");
        }
    }
}
