FROM python@sha256:70729b46c69b4f1e97c4822c1af3df53a1476cf5ddc6c087c0c10bc3a5678c2f
RUN printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d && chmod 755 /usr/sbin/policy-rc.d \
    && apt-get update \
    && if apt-cache show docker-cli >/dev/null 2>&1; then docker_package=docker-cli; else docker_package=docker.io; fi \
    && if apt-cache show docker-compose-v2 >/dev/null 2>&1; then compose_package=docker-compose-v2; else compose_package=docker-compose; fi \
    && apt-get install -y --no-install-recommends openssh-server "$docker_package" "$compose_package" \
    && docker compose version \
    && rm -rf /var/lib/apt/lists/* /etc/ssh/ssh_host_* \
    && mkdir -p /run/sshd /fixture
COPY ssh-exec.py entrypoint.py sshd_config desktop_adapter.py fixture_proxy.py member_adapter.py member-worker.py member_network.py /fixture/
RUN chmod 755 /fixture/ssh-exec.py && chmod 644 /fixture/entrypoint.py /fixture/sshd_config
ENTRYPOINT ["python3","-B","/fixture/entrypoint.py"]
