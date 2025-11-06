import threading
import time
import json
import sys

try:
    import websocket
except ImportError:
    print("El paquete 'websocket-client' no está instalado. Instálalo con: pip install websocket-client")
    sys.exit(1)


def send_json(ws, obj):
    try:
        ws.send(json.dumps(obj))
    except Exception as e:
        print(f"Error enviando mensaje: {e}")


def listener(ws):
    try:
        while True:
            msg = ws.recv()
            if msg is None:
                print("Conexión cerrada por el servidor")
                break
            try:
                data = json.loads(msg)
                t = data.get('type', 'unknown')
                if t == 'chat':
                    print(f"[{data.get('user','Anon')}] {data.get('text','')}")
                elif t == 'system':
                    ev = data.get('event')
                    if ev == 'upload_receiving':
                        print(f"[SYSTEM] Recibiendo parte {data.get('part')} de {data.get('file')}")
                    elif ev == 'upload_done':
                        print(f"[SYSTEM] Subida terminada: {data.get('file')}")
                    elif ev == 'pdf_creating':
                        print(f"[SYSTEM] Creando PDF: {data.get('title')}")
                    elif ev == 'pdf_ready':
                        print(f"[SYSTEM] PDF listo: {data.get('title')}")
                    else:
                        print(f"[SYSTEM] {data}")
                else:
                    print(f"RECV: {data}")
            except Exception:
                print("RECV (no-JSON):", msg)
    except Exception as e:
        print("Listener error:", e)


def upload_worker(ws, name):
    for i in range(3):
        payload = {"type": "upload_chunk", "name": name, "part": i+1}
        send_json(ws, payload)
        time.sleep(1)
    send_json(ws, {"type": "upload_end", "name": name})


def main():
    server = "ws://localhost:8080/ws/chat"
    user = input("Nombre de usuario: ") or "Anon"

    print(f"Conectando a {server} ...")
    ws = websocket.WebSocket()
    try:
        ws.connect(server)
    except Exception as e:
        print("No se pudo conectar al servidor:", e)
        return

    # opción: enviar un join
    send_json(ws, {"type": "system", "event": "join", "user": user})

    t = threading.Thread(target=listener, args=(ws,), daemon=True)
    t.start()

    print("Comandos: /say texto | /upload nombre.ext | /pdf Titulo | /quit")
    try:
        while True:
            line = input('> ').strip()
            if not line:
                continue
            if line.startswith('/say '):
                text = line[len('/say '):]
                send_json(ws, {"type": "chat", "user": user, "text": text})
            elif line.startswith('/upload '):
                name = line[len('/upload '):].strip()
                if not name:
                    print("Usa: /upload nombre.ext")
                    continue
                th = threading.Thread(target=upload_worker, args=(ws, name), daemon=True)
                th.start()
                print(f"Subida simulada iniciada en hilo para {name}")
            elif line.startswith('/pdf '):
                title = line[len('/pdf '):].strip()
                if not title:
                    print("Usa: /pdf Titulo del PDF")
                    continue
                send_json(ws, {"type": "create_pdf", "title": title})
            elif line == '/quit':
                print("Cerrando conexión...")
                try:
                    ws.close()
                except Exception:
                    pass
                break
            else:
                print("Comando desconocido. Usa /say, /upload, /pdf o /quit")
    except KeyboardInterrupt:
        try:
            ws.close()
        except Exception:
            pass


if __name__ == '__main__':
    main()
