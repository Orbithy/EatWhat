#!/usr/bin/env python3
"""Source-restricted opaque TCP forwarding; never terminates or logs TLS."""
import argparse
import asyncio
import concurrent.futures
import ipaddress
import json


async def serve(args):
    asyncio.get_running_loop().set_default_executor(concurrent.futures.ThreadPoolExecutor(max_workers=4))
    allowed = ipaddress.ip_address(args.allowed_source)
    active = 0

    async def pump(reader, writer):
        while True:
            data = await asyncio.wait_for(reader.read(65536), args.idle_seconds)
            if not data:
                if writer.can_write_eof():
                    writer.write_eof()
                    await writer.drain()
                return
            writer.write(data)
            await writer.drain()

    async def handle(reader, writer):
        nonlocal active
        remote = writer.get_extra_info("peername")
        if not remote or ipaddress.ip_address(remote[0]) != allowed or active >= args.max_connections:
            writer.close()
            await writer.wait_closed()
            return
        active += 1
        upstream = None
        tasks = []
        outcome = "closed"
        try:
            upstream_reader, upstream = await asyncio.wait_for(
                asyncio.open_connection(args.target_host, args.target_port), args.connect_seconds)
            tasks = [asyncio.create_task(pump(reader, upstream)),
                     asyncio.create_task(pump(upstream_reader, writer))]
            await asyncio.wait_for(asyncio.gather(*tasks), args.max_seconds)
        except (OSError, TimeoutError, asyncio.TimeoutError):
            outcome = "network_error"
        finally:
            for task in tasks:
                task.cancel()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
            for stream in [upstream, writer]:
                if stream:
                    stream.close()
                    try:
                        await stream.wait_closed()
                    except OSError:
                        pass
            active -= 1
            print(json.dumps({"event": "sms_relay_connection", "outcome": outcome}), flush=True)

    server = await asyncio.start_server(handle, args.listen_host, args.listen_port,
                                        limit=65536, backlog=args.max_connections)
    print(json.dumps({"event": "sms_relay_ready", "tls_passthrough": True}), flush=True)
    async with server:
        await server.serve_forever()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--listen-host", required=True)
    p.add_argument("--listen-port", type=int, required=True)
    p.add_argument("--allowed-source", required=True)
    p.add_argument("--target-host", default="dypnsapi.aliyuncs.com")
    p.add_argument("--target-port", type=int, default=443)
    p.add_argument("--max-connections", type=int, default=32)
    p.add_argument("--connect-seconds", type=float, default=5)
    p.add_argument("--idle-seconds", type=float, default=60)
    p.add_argument("--max-seconds", type=float, default=300)
    args = p.parse_args()
    ipaddress.ip_address(args.listen_host)
    ipaddress.ip_address(args.allowed_source)
    if args.target_host != "dypnsapi.aliyuncs.com" or args.target_port != 443:
        p.error("only the Aliyun PNVS HTTPS endpoint is permitted")
    asyncio.run(serve(args))


if __name__ == "__main__":
    main()
