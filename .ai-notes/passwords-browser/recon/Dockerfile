FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y systemd systemd-sysv snapd dbus-user-session dbus-x11 xvfb xdg-desktop-portal xdg-desktop-portal-gtk python3 sudo libglib2.0-bin flatpak && apt-get clean
RUN useradd -m -s /bin/bash u && echo 'u ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/u
STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
