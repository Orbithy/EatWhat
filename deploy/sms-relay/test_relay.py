import argparse
import asyncio
import contextlib
import io
import pathlib
import socket
import subprocess
import sys
import unittest

import relay


class ForwardingTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.upstream_connections = 0

        async def responder(reader, writer):
            self.upstream_connections += 1
            data = await reader.read()
            writer.write(b"reply:" + data)
            await writer.drain()
            writer.close()
            await writer.wait_closed()

        self.upstream = await asyncio.start_server(responder, "127.0.0.1", 0)
        port = self.upstream.sockets[0].getsockname()[1]
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            self.relay_port = reservation.getsockname()[1]
        args = argparse.Namespace(listen_host="127.0.0.1", listen_port=self.relay_port,
                                  allowed_source="127.0.0.1", target_host="127.0.0.1", target_port=port,
                                  max_connections=32, connect_seconds=1, idle_seconds=2, max_seconds=4)
        self.args = args
        self.output = contextlib.redirect_stdout(io.StringIO())
        self.output.__enter__()
        self.task = asyncio.create_task(relay.serve(args))
        for _ in range(100):
            try:
                reader, writer = await asyncio.open_connection("127.0.0.1", self.relay_port)
                writer.close()
                await writer.wait_closed()
                await reader.read()
                break
            except ConnectionRefusedError:
                await asyncio.sleep(0.01)
        else:
            self.fail("relay did not start")

    async def asyncTearDown(self):
        self.task.cancel()
        await asyncio.gather(self.task, return_exceptions=True)
        self.upstream.close()
        await self.upstream.wait_closed()
        self.output.__exit__(None, None, None)

    async def exchange(self, data):
        reader, writer = await asyncio.open_connection("127.0.0.1", self.relay_port)
        writer.write(data)
        await writer.drain()
        writer.write_eof()
        received = await asyncio.wait_for(reader.read(), 3)
        writer.close()
        await writer.wait_closed()
        return received

    async def test_request_half_close_keeps_response_readable(self):
        self.assertEqual(b"reply:hello", await self.exchange(b"hello"))

    async def test_concurrent_binary_streams_are_complete(self):
        payloads = [bytes([i]) * 131072 for i in range(8)]
        results = await asyncio.gather(*(self.exchange(data) for data in payloads))
        self.assertEqual([b"reply:" + data for data in payloads], results)

    async def test_other_source_cannot_open_upstream(self):
        self.task.cancel()
        await asyncio.gather(self.task, return_exceptions=True)
        self.args.allowed_source = "127.0.0.2"
        self.task = asyncio.create_task(relay.serve(self.args))
        await asyncio.sleep(0.02)
        before = self.upstream_connections
        reader, writer = await asyncio.open_connection("127.0.0.1", self.relay_port)
        self.assertEqual(b"", await asyncio.wait_for(reader.read(), 1))
        writer.close()
        await writer.wait_closed()
        self.assertEqual(before, self.upstream_connections)


class CommandPolicyTest(unittest.TestCase):
    def test_cli_refuses_other_destination(self):
        r = subprocess.run([sys.executable, str(pathlib.Path(relay.__file__)),
                            "--listen-host", "127.0.0.1", "--listen-port", "0",
                            "--allowed-source", "127.0.0.1", "--target-host", "example.com"],
                           capture_output=True, text=True)
        self.assertEqual(2, r.returncode)


if __name__ == "__main__":
    unittest.main()
