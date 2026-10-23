"""Loopback-only forwarding to this fixture's own host loopback HTTP services.

Docker Desktop cannot route a container's 127.0.0.1 to the host. Fixed paths and
headers only; no redirects, arbitrary destination, writes or fabricated results.
"""
import http.client,json,threading
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

def start():
    config=Path('/fixture-input/desktop-adapter.json')
    if not config.is_file():return
    values=json.loads(config.read_text())
    for role in ['business','management']:
        port=values[role+'Port'];assert type(port) is int and 1024<=port<=65535
        def handler(role,port):
            class Handler(BaseHTTPRequestHandler):
                def log_message(self,*args):pass
                def do_GET(self):
                    path=urlsplit(self.path)
                    allowed=(role=='business' and path.path in ['/actuator/health/readiness','/internal/control/v1/snapshot','/internal/cluster/v1/probe','/internal/cluster/v1/enrollment-proof'] and not path.query or role=='management' and path.path.startswith('/internal/deployment/v1/fences/') and path.query.startswith('manifest='))
                    if not allowed:self.send_error(403);return
                    connection=http.client.HTTPConnection('host.docker.internal',port,timeout=4)
                    try:
                        name='X-Control-Node-Token' if role=='business' else 'X-Deployment-Token'
                        headers={name:self.headers[name]} if name in self.headers else {}
                        connection.request('GET',self.path,headers=headers);reply=connection.getresponse();body=reply.read(65537)
                        if len(body)>65536:raise ValueError()
                        self.send_response(reply.status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
                    except Exception:self.send_error(502)
                    finally:connection.close()
            return Handler
        server=ThreadingHTTPServer(('127.0.0.1',port),handler(role,port))
        threading.Thread(target=server.serve_forever,daemon=True).start()
