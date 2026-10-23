#!/usr/bin/env python3
"""An owned nftables table for one Docker bridge and one destination only."""
import json
import re
import subprocess
import time


TABLE = "eatwhat_sms_relay"
NETWORK = "eatwhat-site-16343d72_default"


def rules(bridge, subnet, exists):
    import ipaddress
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,15}", bridge):
        raise ValueError("unexpected bridge name")
    subnet = str(ipaddress.IPv4Network(subnet))
    prefix = f"flush table ip {TABLE}\n" if exists else ""
    return prefix + f'''table ip {TABLE} {{
 chain prerouting {{
  type nat hook prerouting priority -105; policy accept;
  iifname "{bridge}" ip saddr {subnet} ip daddr 23.149.108.7 tcp dport 443 counter dnat to 23.149.108.7:39857
 }}
}}
'''


def main():
    deadline = time.monotonic() + 60
    while True:
        r = subprocess.run(["docker", "network", "inspect", NETWORK], capture_output=True, text=True)
        if r.returncode == 0:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError("business Docker network unavailable")
        time.sleep(1)
    network = json.loads(r.stdout)[0]
    bridge = network["Options"].get("com.docker.network.bridge.name", "br-" + network["Id"][:12])
    subnets = [c["Subnet"] for c in network["IPAM"]["Config"] if ":" not in c["Subnet"]]
    if len(subnets) != 1:
        raise RuntimeError("unexpected IPv4 subnet count")
    exists = subprocess.run(["nft", "list", "table", "ip", TABLE], capture_output=True).returncode == 0
    batch = rules(bridge, subnets[0], exists)
    subprocess.run(["nft", "-c", "-f", "-"], input=batch, text=True, check=True)
    subprocess.run(["nft", "-f", "-"], input=batch, text=True, check=True)
    print(json.dumps({"event": "sms_route_ready", "bridge": bridge, "table": TABLE}))


if __name__ == "__main__":
    main()
