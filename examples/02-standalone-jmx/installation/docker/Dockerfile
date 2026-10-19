ARG UBUNTU_IMAGE=ubuntu:24.04
FROM ${UBUNTU_IMAGE}

# Provision the test VM's prerequisites here. The real installer installs no packages.
ENV container=docker
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        systemd systemd-sysv dbus openjdk-17-jre-headless ca-certificates jq logrotate \
    && ln -s /usr/lib/jvm/java-17-openjdk-$(dpkg --print-architecture) /opt/test-java \
    && mkdir -p /etc/systemd/system/multi-user.target.wants \
    && ln -s ../collector-install-bootstrap.service \
        /etc/systemd/system/multi-user.target.wants/collector-install-bootstrap.service \
    && rm -rf /var/lib/apt/lists/* \
    && truncate -s 0 /etc/machine-id

COPY collector-install-bootstrap.service /etc/systemd/system/
COPY start-systemd.sh install-on-boot.sh verify-installation.sh /usr/local/bin/
RUN chmod 0755 /usr/local/bin/*.sh

STOPSIGNAL SIGRTMIN+3
ENTRYPOINT ["/usr/local/bin/start-systemd.sh"]
CMD []
