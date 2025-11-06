Mini README para el avance (MVP)

Qué contiene este repo (avance):
- Servidor Java WebSocket en `src/main/java/com/chat/server` (clases `ChatServer` y `ChatEndpoint`).
- Cliente de consola en Python: `client.py` (multihilo, comandos: /say, /upload, /pdf, /quit).
 - Cliente de consola Java: `src/main/java/com/chat/client/ChatClient.java` (multihilo, comandos: /say, /upload, /pdf, /quit).
 - Cliente de consola en Python (alternativa rápida): `client.py` (multihilo, comandos: /say, /upload, /pdf, /quit).

Requisitos (rápido):
- Java 17+ (JDK instalado). Para compilar/ejecutar el servidor necesitas Maven; si aún no lo tienes, puedes ejecutar el cliente y simular con otro cliente o usar un IDE que corra la clase `ChatServer`.
- Python 3.8+
- Paquetes Python: ver `requeriments.txt` (instalar con `pip install -r requeriments.txt`).

Cómo usar (sin Maven)
1) Ejecuta el servidor desde tu IDE (run la clase `com.chat.server.ChatServer`).
   - Alternativa: si tienes Maven instalado, desde la carpeta del proyecto:
     "C:\Users\LENOVO\Downloads\Apache\apache-maven-3.9.11\bin\mvn.cmd" clean install
     "C:\Users\LENOVO\Downloads\Apache\apache-maven-3.9.11\bin\mvn.cmd" -Dexec.mainClass="com.chat.server.ChatServer" exec:java

2) En otra terminal, instala dependencias Python si no lo hiciste:

```bash
pip install -r requeriments.txt
```

3) Ejecuta el cliente Python (puedes abrir múltiples clientes para simular varios usuarios):

```bash
python client.py
```

Java client (recomendado si todo el equipo usa Java)

- Desde un IDE (IntelliJ/Eclipse): importa el proyecto como Maven y ejecuta la clase `com.chat.client.ChatClient`.
- Si tienes Maven instalado, desde la carpeta del proyecto puedes ejecutar (usa la ruta completa si mvn no está en PATH):

```cmd
"C:\Users\LENOVO\Downloads\Apache\apache-maven-3.9.11\bin\mvn.cmd" -Dexec.mainClass="com.chat.client.ChatClient" exec:java
```

Nota: el client Java usa la API `jakarta.websocket` y `org.json` (ya están en el `pom.xml`). Ejecutarlo desde un IDE es lo más sencillo si aún no tienes Maven en PATH.

4) Comandos en el cliente:
- /say texto      => envía mensaje de chat
- /upload name    => simula subida en un hilo (manda 3 chunks y luego upload_end)
- /pdf Titulo     => solicita creación de PDF (servidor simula 3s en hilo)
- /quit           => cerrar cliente

Mensajes esperados en todos los clientes:
- Chat: {type: "chat", user: "Ana", text: "hola"}
- Upload: notifications system con event `upload_receiving` y luego `upload_done`
- PDF: `pdf_creating` inmediato y `pdf_ready` ~3s después

Checklist rápido por persona
- Persona 1 (servidor): `ChatEndpoint` maneja chat, upload_chunk, upload_end, create_pdf. (Hecho y editado)
- Persona 2 (cliente): `client.py` implementa recepción en hilo y comandos básicos. (Hecho)
- Persona 3 (upload): cliente lanza hilo que manda chunks y server broadcastea upload_done. (Hecho)
- Persona 4 (pdf): cliente envía create_pdf; servidor lanza thread que simula 3s y broadcastea pdf_ready. (Hecho)

Si quieres, genero un pequeño script `.bat` para ejecutar el cliente varias veces en Windows, o puedo añadir instrucciones para ejecutar todo con Docker. Dime qué prefieres.